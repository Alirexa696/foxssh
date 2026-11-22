package com.foxssh.desktop;

import com.foxssh.vpn.core.BypassRules;
import com.foxssh.vpn.core.ChannelOpener;
import com.foxssh.vpn.core.CoreLog;
import com.foxssh.vpn.core.OpenerSource;
import com.foxssh.vpn.core.Socks5Server;
import com.foxssh.vpn.core.UdpGwMux;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * موتور بدون‌پنجره (با کاربر سیستمی foxssh اجرا می‌شود): SSH + SOCKS5 محلی + بایپس ایران.
 * وضعیت را در فایل status می‌نویسد تا برنامهٔ گرافیکی بخواند.
 */
public final class Engine {
    private final Path configFile, statusFile, logFile, stateDir, sharedDir, runDir;
    private final AtomicLong tx = new AtomicLong(), rx = new AtomicLong();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private volatile boolean running = true;
    private volatile DSshTunnel tunnel;
    private volatile int socksPort;
    private volatile String state = "connecting", error = "", banner = "";
    private volatile long since;

    private Engine(Path config, Path runDir, Path stateDir, Path sharedDir) {
        this.configFile = config;
        this.runDir = runDir;
        this.statusFile = runDir.resolve("status.properties");
        this.logFile = runDir.resolve("engine.log");
        this.stateDir = stateDir;
        this.sharedDir = sharedDir;
    }

    public static void main(String[] a) throws Exception {
        Path config = Paths.get("/run/foxssh/config.properties");
        Path run = Paths.get("/run/foxssh");
        Path state = Paths.get("/var/lib/foxssh");
        Path shared = Paths.get("/usr/share/foxssh");
        boolean updateOnly = false;
        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--config": config = Paths.get(a[++i]); break;
                case "--run": run = Paths.get(a[++i]); break;
                case "--state": state = Paths.get(a[++i]); break;
                case "--shared": shared = Paths.get(a[++i]); break;
                case "--update-list": updateOnly = true; break;
                default: break;
            }
        }
        if (updateOnly) {
            try {
                System.out.println("OK " + IranList.update(state));
            } catch (IOException e) {
                System.out.println("ERR " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        final Engine e = new Engine(config, run, state, shared);
        Runtime.getRuntime().addShutdownHook(new Thread(e::shutdown));
        System.exit(e.run());
    }

    private int run() {
        try {
            Files.write(logFile, new byte[0]);
            setPerm(logFile);
        } catch (IOException ignored) {
        }
        CoreLog.sink = this::log;
        Properties cfg = new Properties();
        try (java.io.Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            cfg.load(r);
        } catch (IOException e) {
            return fail("فایل تنظیمات خوانده نشد: " + e.getMessage());
        }
        final String host = cfg.getProperty("host", "").trim();
        final int port = toInt(cfg.getProperty("port"), 22);
        final String user = cfg.getProperty("user", "");
        final String pass = cfg.getProperty("pass", "");
        int udpgw = toInt(cfg.getProperty("udpgw"), 7300);
        boolean bypass = !"false".equals(cfg.getProperty("bypass", "true"));
        List<String> manual = new ArrayList<>();
        for (String s : cfg.getProperty("manual", "").split(";")) if (!s.trim().isEmpty()) manual.add(s.trim());
        // رمز را از حافظهٔ فایل پاک می‌کنیم (فقط در RAM می‌ماند)
        try {
            Files.deleteIfExists(configFile);
        } catch (IOException ignored) {
        }

        Socks5Server socks = null;
        UdpGwMux mux = null;
        try {
            if (host.isEmpty() || user.isEmpty()) return fail("اطلاعات سرور ناقص است");
            log("اتصال به " + host + ":" + port + " با کاربر " + user);
            // تبدیل نام به IP «قبل» از تغییر مسیرها؛ برای اتصال مجدد هم از همین IP استفاده می‌شود
            final String ip = InetAddress.getByName(host).getHostAddress();
            writeStatus();

            final BypassRules rules = new BypassRules();
            rules.setEnabled(bypass);
            rules.setIranList(IranList.load(stateDir, sharedDir));
            rules.setManualEntries(manual);
            rules.resolveDomains();
            log("لیست ایران: " + rules.iranCount() + " رنج، بایپس " + (bypass ? "روشن" : "خاموش"));
            if (IranList.needsUpdate(stateDir)) {
                Thread t = new Thread(() -> {
                    try {
                        int n = IranList.update(stateDir);
                        rules.setIranList(IranList.load(stateDir, sharedDir));
                        log("لیست ایران به‌روز شد: " + n + " رنج");
                    } catch (IOException ex) {
                        log("به‌روزرسانی لیست ایران نشد: " + ex.getMessage());
                    }
                }, "fox-iran-update");
                t.setDaemon(true);
                t.start();
            }

            Path hk = stateDir.resolve("known_hosts.properties");
            DSshTunnel t = new DSshTunnel(hk, tx, rx);
            t.connect(ip, host + ":" + port, port, user, pass);
            tunnel = t;
            banner = t.banner();
            log("SSH وصل شد؛ طول بنر: " + banner.length());

            OpenerSource src = () -> {
                DSshTunnel c = tunnel;
                return c != null && c.isAlive() ? (ChannelOpener) c : null;
            };
            mux = new UdpGwMux(src, udpgw, 200);
            socks = new Socks5Server(src, rules, mux);
            socksPort = socks.start();
            log("SOCKS محلی روی 127.0.0.1:" + socksPort + "، udpgw=" + udpgw);
            writeStatus();

            // منتظر می‌مانیم اسکریپت راه‌انداز تونل و مسیرها را بسازد
            Path up = runDir.resolve("vpn-up");
            long deadline = System.currentTimeMillis() + 60000;
            while (running && !Files.exists(up)) {
                if (System.currentTimeMillis() > deadline) return fail("راه‌اندازی تونل سیستم زمان‌بر شد");
                Thread.sleep(200);
            }
            since = System.currentTimeMillis();
            state = "connected";
            writeStatus();
            selfTest(socksPort);

            monitor(host, ip, port, user, pass, rules, hk);
            return 0;
        } catch (InterruptedException e) {
            return 0;
        } catch (Throwable e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            if (m.contains("HostKey has been changed")) {
                m = "کلید سرور تغییر کرده است. اگر سرور را دوباره نصب کرده‌اید، در تنظیمات «پاک‌کردن کلیدهای ذخیره‌شده» را بزنید.";
            }
            return fail(m);
        } finally {
            if (socks != null) socks.close();
            if (mux != null) mux.close();
            DSshTunnel t = tunnel;
            tunnel = null;
            if (t != null) t.close();
        }
    }

    private void monitor(String host, String ip, int port, String user, String pass, BypassRules rules, Path hk)
            throws InterruptedException {
        int backoff = 2, tick = 0;
        while (running) {
            Thread.sleep(1000);
            tick++;
            if (tick % 600 == 0) rules.resolveDomains();
            DSshTunnel cur = tunnel;
            if (cur != null && cur.isAlive()) {
                if (!"connected".equals(state)) {
                    state = "connected";
                    backoff = 2;
                }
                writeStatus();
                continue;
            }
            state = "reconnecting";
            writeStatus();
            DSshTunnel n = new DSshTunnel(hk, tx, rx);
            try {
                n.connect(ip, host + ":" + port, port, user, pass);
                tunnel = n;
                if (cur != null) cur.close();
                log("اتصال SSH دوباره برقرار شد");
            } catch (Exception e) {
                n.close();
                log("اتصال مجدد ناموفق: " + e.getMessage());
                for (int i = 0; i < backoff && running; i++) Thread.sleep(1000);
                backoff = Math.min(backoff * 2, 30);
            }
        }
    }

    /** بررسی خودکار تونل: HEAD به 1.1.1.1 از طریق SOCKS خودمان (نتیجه در لاگ). */
    private void selfTest(final int socksPort) {
        Thread th = new Thread(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", socksPort), 3000);
                s.setSoTimeout(20000);
                OutputStream o = s.getOutputStream();
                DataInputStream in = new DataInputStream(s.getInputStream());
                o.write(new byte[]{5, 1, 0});
                in.readFully(new byte[2]);
                o.write(new byte[]{5, 1, 0, 1, 1, 1, 1, 1, 0, 80});
                byte[] r = new byte[10];
                in.readFully(r);
                if (r[1] != 0) {
                    log("تست تونل ✗ (کد SOCKS " + r[1] + ") — AllowTcpForwarding سرور را بررسی کنید");
                    return;
                }
                o.write("HEAD / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                o.flush();
                StringBuilder line = new StringBuilder();
                int c;
                while ((c = in.read()) != -1 && c != '\n' && line.length() < 100) line.append((char) c);
                log("تست تونل ✓ پاسخ 1.1.1.1: " + line.toString().trim());
            } catch (Exception e) {
                log("تست تونل ✗ " + e);
            }
        }, "fox-selftest");
        th.setDaemon(true);
        th.start();
    }

    private int fail(String msg) {
        error = msg;
        state = "stopped";
        log("خطا: " + msg);
        writeStatus();
        return 1;
    }

    private void shutdown() {
        running = false;
        if (!"stopped".equals(state)) {
            state = "stopped";
            writeStatus();
        }
    }

    // ───────────── فایل‌ها ─────────────

    private synchronized void writeStatus() {
        Properties p = new Properties();
        p.setProperty("state", state);
        p.setProperty("error", error);
        p.setProperty("banner", banner);
        p.setProperty("socksPort", String.valueOf(socksPort));
        p.setProperty("since", String.valueOf(since));
        p.setProperty("tx", String.valueOf(tx.get()));
        p.setProperty("rx", String.valueOf(rx.get()));
        p.setProperty("listUpdated", String.valueOf(IranList.lastUpdate(stateDir)));
        try {
            Path tmp = runDir.resolve("status.tmp");
            try (java.io.Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, null);
            }
            setPerm(tmp);
            Files.move(tmp, statusFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
        }
    }

    private synchronized void log(String s) {
        String line = fmt.format(new Date()) + "  " + s + "\n";
        try {
            Files.write(logFile, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static void setPerm(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (Exception ignored) {
        }
    }

    private static int toInt(String s, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            return v >= 0 && v < 65536 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }
}
