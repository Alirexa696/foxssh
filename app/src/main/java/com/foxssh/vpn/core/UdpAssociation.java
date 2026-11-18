package com.foxssh.vpn.core;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * یک UDP ASSOCIATE از SOCKS5. بستهٔ tun2socks (hev) را می‌گیرد و
 *  - اگر مقصد در لیست بایپس بود: مستقیم می‌فرستد،
 *  - وگرنه از udpgw روی تونل SSH،
 *  - و اگر udpgw در دسترس نبود و مقصد پورت ۵۳ بود: DNS روی TCP از تونل.
 */
final class UdpAssociation implements UdpGwMux.Sink, Closeable {
    private static final AtomicLong SEQ = new AtomicLong();

    private final long id = SEQ.incrementAndGet();
    private final DatagramSocket local;
    private final BypassRules rules;
    private final UdpGwMux mux;
    private final OpenerSource openers;
    private final ExecutorService pool;
    private volatile SocketAddress client;
    private DatagramSocket direct;
    private volatile boolean closed;

    UdpAssociation(BypassRules rules, UdpGwMux mux, OpenerSource openers, ExecutorService pool) throws IOException {
        this.rules = rules;
        this.mux = mux;
        this.openers = openers;
        this.pool = pool;
        this.local = new DatagramSocket(0, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
    }

    int localPort() {
        return local.getLocalPort();
    }

    void start() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        });
    }

    @Override
    public long sinkId() {
        return id;
    }

    @Override
    public void deliver(byte[] ip, int port, byte[] payload) {
        SocketAddress to = client;
        if (closed || to == null) return;
        byte[] out = new byte[4 + ip.length + 2 + payload.length];
        out[3] = (byte) (ip.length == 4 ? 1 : 4);
        System.arraycopy(ip, 0, out, 4, ip.length);
        out[4 + ip.length] = (byte) (port >>> 8);
        out[5 + ip.length] = (byte) port;
        System.arraycopy(payload, 0, out, 6 + ip.length, payload.length);
        try {
            local.send(new DatagramPacket(out, out.length, to));
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        local.close();
        DatagramSocket d;
        synchronized (this) {
            d = direct;
        }
        if (d != null) d.close();
        if (mux != null) mux.forget(this);
    }

    // ───────────── داخلی ─────────────

    private void receiveLoop() {
        byte[] buf = new byte[65535];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                p.setLength(buf.length);
                local.receive(p);
            } catch (IOException e) {
                return;
            }
            client = p.getSocketAddress();
            try {
                handle(buf, p.getLength());
            } catch (RuntimeException | IOException ignored) {
                // دیتاگرام خراب؛ نادیده
            }
        }
    }

    private void handle(byte[] b, int len) throws IOException {
        if (len < 10 || b[2] != 0) return; // FRAG پشتیبانی نمی‌شود
        int atyp = b[3] & 0xFF;
        int off;
        byte[] ip;
        if (atyp == 1) {
            ip = Arrays.copyOfRange(b, 4, 8);
            off = 8;
        } else if (atyp == 4) {
            if (len < 22) return;
            ip = Arrays.copyOfRange(b, 4, 20);
            off = 20;
        } else if (atyp == 3) {
            int dl = b[4] & 0xFF;
            if (len < 5 + dl + 2) return;
            String domain = new String(b, 5, dl, "US-ASCII");
            try {
                ip = InetAddress.getByName(domain).getAddress();
            } catch (UnknownHostException e) {
                return;
            }
            off = 5 + dl;
        } else {
            return;
        }
        if (len < off + 2) return;
        int port = ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
        byte[] payload = Arrays.copyOfRange(b, off + 2, len);
        route(ip, port, payload);
    }

    private void route(final byte[] ip, final int port, final byte[] payload) throws IOException {
        InetAddress dst = InetAddress.getByAddress(ip);
        if (rules.shouldBypass(dst)) {
            sendDirect(dst, port, payload);
            return;
        }
        if (mux != null && mux.send(this, ip, port, payload)) return;
        if (port == 53) {
            final String host = dst.getHostAddress();
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    ChannelOpener o = openers.get();
                    if (o == null) return;
                    try {
                        byte[] resp = DnsOverTcp.query(o, host, 53, payload, 8000);
                        deliver(ip, port, resp);
                    } catch (IOException e) {
                        CoreLog.log("DNS روی TCP ✗ " + e);
                    }
                }
            });
        }
    }

    private void sendDirect(InetAddress dst, int port, byte[] payload) throws IOException {
        DatagramSocket d;
        synchronized (this) {
            if (direct == null) {
                final DatagramSocket nd = new DatagramSocket();
                direct = nd;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        directLoop(nd);
                    }
                });
            }
            d = direct;
        }
        d.send(new DatagramPacket(payload, payload.length, dst, port));
    }

    private void directLoop(DatagramSocket d) {
        byte[] buf = new byte[65535];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                p.setLength(buf.length);
                d.receive(p);
            } catch (IOException e) {
                return;
            }
            deliver(p.getAddress().getAddress(), p.getPort(), Arrays.copyOfRange(buf, 0, p.getLength()));
        }
    }
}
