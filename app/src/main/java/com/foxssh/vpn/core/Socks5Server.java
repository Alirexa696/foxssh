package com.foxssh.vpn.core;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * سرور SOCKS5 محلی (فقط 127.0.0.1، بدون احراز هویت) که tun2socks به آن وصل می‌شود.
 * CONNECT  → مستقیم (بایپس) یا کانال direct-tcpip روی SSH
 * UDP ASSOCIATE → UdpAssociation
 */
public final class Socks5Server implements Closeable {
    private final OpenerSource openers;
    private final BypassRules rules;
    private final UdpGwMux mux;
    private final ExecutorService pool;
    private ServerSocket server;
    private volatile boolean closed;

    public Socks5Server(OpenerSource openers, BypassRules rules, UdpGwMux mux) {
        this.openers = openers;
        this.rules = rules;
        this.mux = mux;
        final AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "fox-socks-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** روی پورت آزاد 127.0.0.1 شروع می‌کند و پورت را برمی‌گرداند. */
    public int start() throws IOException {
        server = new ServerSocket(0, 256, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        return server.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            final Socket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                return;
            }
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(c);
                    }
                });
            } catch (RuntimeException e) {
                closeQuietly(c);
                return;
            }
        }
    }

    private void handle(Socket c) {
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(c.getInputStream(), 1024));
            OutputStream out = c.getOutputStream();

            if (in.readUnsignedByte() != 5) return;
            int nMethods = in.readUnsignedByte();
            boolean noAuth = false;
            for (int i = 0; i < nMethods; i++) {
                if (in.readUnsignedByte() == 0) noAuth = true;
            }
            if (!noAuth) {
                out.write(new byte[]{5, (byte) 0xFF});
                out.flush();
                return;
            }
            out.write(new byte[]{5, 0});
            out.flush();

            if (in.readUnsignedByte() != 5) return;
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte(); // RSV
            int atyp = in.readUnsignedByte();
            String host;
            InetAddress ip = null;
            if (atyp == 1) {
                byte[] a = new byte[4];
                in.readFully(a);
                ip = InetAddress.getByAddress(a);
                host = ip.getHostAddress();
            } else if (atyp == 4) {
                byte[] a = new byte[16];
                in.readFully(a);
                ip = InetAddress.getByAddress(a);
                host = ip.getHostAddress();
            } else if (atyp == 3) {
                int len = in.readUnsignedByte();
                byte[] d = new byte[len];
                in.readFully(d);
                host = new String(d, "US-ASCII");
            } else {
                reply(out, 8);
                return;
            }
            int port = in.readUnsignedShort();

            if (cmd == 1) {
                doConnect(c, in, out, host, ip, port);
            } else if (cmd == 3) {
                doUdpAssociate(c, in, out);
            } else {
                reply(out, 7);
            }
        } catch (IOException | RuntimeException ignored) {
            // کلاینت رفت یا درخواست خراب بود
        } finally {
            closeQuietly(c);
        }
    }

    private void doConnect(Socket c, InputStream cin, OutputStream cout, String host, InetAddress ip, int port)
            throws IOException {
        boolean direct = ip != null ? rules.shouldBypass(ip) : rules.shouldBypassHost(host);
        final Duplex remote;
        try {
            if (direct) {
                remote = SocketDuplex.connect(host, port, 10000);
            } else {
                ChannelOpener o = openers.get();
                if (o == null) {
                    reply(cout, 3);
                    return;
                }
                remote = o.open(host, port);
            }
        } catch (IOException e) {
            reply(cout, mapError(e));
            return;
        }
        reply(cout, 0);
        c.setSoTimeout(0);
        relay(cin, cout, c, remote);
    }

    private void relay(final InputStream cin, OutputStream cout, Socket c, final Duplex remote) {
        Future<?> up = pool.submit(new Runnable() {
            @Override
            public void run() {
                copy(cin, remote.out());
                remote.shutdownOutput();
                // اگر طرف مقابل تا ۳۰ ثانیه نبست، کانال را آزاد می‌کنیم
                Timers.EX.schedule(new Runnable() {
                    @Override
                    public void run() {
                        remote.close();
                    }
                }, 30, TimeUnit.SECONDS);
            }
        });
        copy(remote.in(), cout);
        try {
            c.shutdownOutput();
        } catch (IOException ignored) {
        }
        try {
            up.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        remote.close();
    }

    private void doUdpAssociate(Socket c, DataInputStream in, OutputStream out) throws IOException {
        UdpAssociation a = new UdpAssociation(rules, mux, openers, pool);
        try {
            int p = a.localPort();
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (p >>> 8), (byte) p});
            out.flush();
            a.start();
            c.setSoTimeout(0);
            // تا وقتی کلاینت اتصال کنترل TCP را نبسته، association زنده است
            while (in.read() != -1) {
                // داده‌ای روی کانال کنترل انتظار نمی‌رود
            }
        } finally {
            a.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static void reply(OutputStream out, int rep) throws IOException {
        out.write(new byte[]{5, (byte) rep, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    private static int mapError(IOException e) {
        if (e instanceof ConnectException) return 5;
        if (e instanceof SocketTimeoutException) return 6;
        if (e instanceof UnknownHostException) return 4;
        if (e instanceof EOFException) return 1;
        return 1;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
