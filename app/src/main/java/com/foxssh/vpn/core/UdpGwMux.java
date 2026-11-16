package com.foxssh.vpn.core;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * کلاینت پروتکل badvpn-udpgw: همهٔ دیتاگرام‌های UDP را روی «یک» کانال TCP
 * (direct-tcpip روی SSH به 127.0.0.1:پورت badvpn سرور) چندگانه می‌کند.
 *
 * قالب (طبق سورس badvpn): هر بسته = طول ۱۶بیتی little-endian + بدنه؛
 * بدنه = flags(1) + conid(2, little-endian) + آدرس (IPv4: ۴بایت، IPv6: ۱۶بایت) + پورت (۲بایت big-endian) + داده.
 * سرور برای هر conid «یک مقصد» نگه می‌دارد؛ پس هر (منبع، مقصد) conid جدا می‌گیرد.
 */
public final class UdpGwMux implements Closeable {

    public interface Sink {
        long sinkId();

        /** پاسخ از مقصد (ip/port همان مقصد اصلی درخواست است). */
        void deliver(byte[] ip, int port, byte[] payload);
    }

    static final int FLAG_KEEPALIVE = 1;
    static final int FLAG_REBIND = 2;
    static final int FLAG_DNS = 4;
    static final int FLAG_IPV6 = 8;

    private static final long OPEN_RETRY_MS = 20_000;
    private static final long BROKEN_RETRY_MS = 2_000;
    private static final byte[] KEEPALIVE_FRAME = {3, 0, (byte) FLAG_KEEPALIVE, 0, 0};

    private static final class Entry {
        final Sink sink;
        final byte[] ip;
        final int port;
        final int conid;
        final String key;

        Entry(Sink sink, byte[] ip, int port, int conid, String key) {
            this.sink = sink;
            this.ip = ip;
            this.port = port;
            this.conid = conid;
            this.key = key;
        }
    }

    private final OpenerSource openers;
    private final int udpgwPort;
    private final int maxConnections;
    private final Object lock = new Object();
    private final LinkedHashMap<String, Entry> byKey = new LinkedHashMap<>(16, 0.75f, true);
    private final HashMap<Integer, Entry> byConid = new HashMap<>();
    private Duplex channel;
    private OutputStream channelOut;
    private ScheduledFuture<?> keepalive;
    private long retryAt;
    private int nextConid = 0;
    private volatile boolean closed;

    /**
     * @param udpgwPort پورت badvpn-udpgw روی خود سرور (۰ یعنی غیرفعال)
     */
    public UdpGwMux(OpenerSource openers, int udpgwPort, int maxConnections) {
        this.openers = openers;
        this.udpgwPort = udpgwPort;
        this.maxConnections = Math.max(8, Math.min(maxConnections, 1000));
    }

    public boolean isConfigured() {
        return udpgwPort > 0 && udpgwPort < 65536;
    }

    /** true یعنی دیتاگرام روی کانال نوشته شد (نه لزوماً به مقصد رسید). */
    public boolean send(Sink sink, byte[] ip, int port, byte[] payload) {
        if (!isConfigured() || closed || payload.length > 60000 || (ip.length != 4 && ip.length != 16)) return false;
        synchronized (lock) {
            if (!ensureOpenLocked()) return false;
            String key = sink.sinkId() + "|" + hex(ip) + "|" + port;
            Entry e = byKey.get(key);
            if (e == null) {
                while (byKey.size() >= maxConnections) {
                    Iterator<Entry> it = byKey.values().iterator();
                    Entry old = it.next();
                    it.remove();
                    byConid.remove(old.conid);
                }
                e = new Entry(sink, ip.clone(), port, allocConidLocked(), key);
                byKey.put(key, e);
                byConid.put(e.conid, e);
            }
            int flags = (ip.length == 16 ? FLAG_IPV6 : 0) | (port == 53 ? FLAG_DNS : 0);
            try {
                channelOut.write(encodeFrame(flags, e.conid, ip, port, payload));
                channelOut.flush();
                return true;
            } catch (IOException ex) {
                closeChannelLocked(BROKEN_RETRY_MS);
                return false;
            }
        }
    }

    /** همهٔ conidهای یک منبع (association) را آزاد می‌کند. */
    public void forget(Sink sink) {
        synchronized (lock) {
            Iterator<Entry> it = byKey.values().iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (e.sink.sinkId() == sink.sinkId()) {
                    it.remove();
                    byConid.remove(e.conid);
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) {
            closeChannelLocked(0);
        }
    }

    // ───────────── داخلی ─────────────

    private boolean ensureOpenLocked() {
        if (channel != null) return true;
        long now = System.currentTimeMillis();
        if (now < retryAt) return false;
        ChannelOpener opener = openers.get();
        if (opener == null) {
            retryAt = now + BROKEN_RETRY_MS;
            return false;
        }
        try {
            final Duplex ch = opener.open("127.0.0.1", udpgwPort);
            channel = ch;
            channelOut = ch.out();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop(ch);
                }
            }, "fox-udpgw-reader");
            t.setDaemon(true);
            t.start();
            keepalive = Timers.EX.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        if (channel != ch) return;
                        try {
                            channelOut.write(KEEPALIVE_FRAME);
                            channelOut.flush();
                        } catch (IOException e) {
                            closeChannelLocked(BROKEN_RETRY_MS);
                        }
                    }
                }
            }, 10, 10, TimeUnit.SECONDS);
            return true;
        } catch (IOException e) {
            retryAt = now + OPEN_RETRY_MS;
            return false;
        }
    }

    private void closeChannelLocked(long retryDelayMs) {
        if (keepalive != null) {
            keepalive.cancel(false);
            keepalive = null;
        }
        if (channel != null) {
            channel.close();
            channel = null;
            channelOut = null;
        }
        byKey.clear();
        byConid.clear();
        if (retryDelayMs > 0) retryAt = System.currentTimeMillis() + retryDelayMs;
    }

    private int allocConidLocked() {
        for (int i = 0; i < 65536; i++) {
            nextConid = (nextConid + 1) & 0xFFFF;
            if (!byConid.containsKey(nextConid)) return nextConid;
        }
        throw new IllegalStateException("no free conid");
    }

    private void readLoop(Duplex ch) {
        DataInputStream in = new DataInputStream(new BufferedInputStream(ch.in(), 16384));
        try {
            while (!closed) {
                int lo = in.readUnsignedByte();
                int hi = in.readUnsignedByte();
                int len = lo | (hi << 8);
                byte[] body = new byte[len];
                in.readFully(body);
                if (len < 3) continue;
                int flags = body[0] & 0xFF;
                int conid = (body[1] & 0xFF) | ((body[2] & 0xFF) << 8);
                if ((flags & FLAG_KEEPALIVE) != 0) continue;
                int addrLen = (flags & FLAG_IPV6) != 0 ? 18 : 6;
                if (len < 3 + addrLen) continue;
                byte[] payload = Arrays.copyOfRange(body, 3 + addrLen, len);
                Entry e;
                synchronized (lock) {
                    e = byConid.get(conid);
                    if (e != null) byKey.get(e.key); // علامت «اخیراً استفاده‌شده» برای LRU
                }
                if (e != null) e.sink.deliver(e.ip, e.port, payload);
            }
        } catch (IOException ignored) {
            // کانال بسته شد
        } finally {
            synchronized (lock) {
                if (channel == ch) {
                    closeChannelLocked(BROKEN_RETRY_MS);
                } else {
                    ch.close();
                }
            }
        }
    }

    static byte[] encodeFrame(int flags, int conid, byte[] ip, int port, byte[] payload) {
        int body = 3 + ip.length + 2 + payload.length;
        byte[] f = new byte[2 + body];
        f[0] = (byte) body;
        f[1] = (byte) (body >>> 8);
        f[2] = (byte) flags;
        f[3] = (byte) conid;
        f[4] = (byte) (conid >>> 8);
        System.arraycopy(ip, 0, f, 5, ip.length);
        f[5 + ip.length] = (byte) (port >>> 8);
        f[6 + ip.length] = (byte) port;
        System.arraycopy(payload, 0, f, 7 + ip.length, payload.length);
        return f;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
