package com.foxssh.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * کنترل تونل سیستم در ویندوز (معادل foxssh-run + foxssh-ctl در لینوکس).
 * برنامه با دسترسی Administrator اجرا می‌شود: موتور SSH (Engine) به‌صورت پردازش جدا، hev-socks5-tunnel (با درایور Wintun)
 * و مسیرها/DNS با PowerShell. مسیرها روی کارت Wintun هستند و با بسته‌شدن آن خودکار پاک می‌شوند.
 */
final class WinCtl {
    private static final Path RUN = Platform.runDir();
    private static final Path STATE = Platform.stateDir();
    private static final String ADAPTER = "FoxVPN";
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static volatile Process engine, hev;
    private static volatile String hostRouteIp = "";

    private WinCtl() {
    }

    // ───────────── دسترسی Administrator ─────────────

    static boolean isAdmin() {
        try {
            Process p = new ProcessBuilder("fltmc").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** برنامه را دوباره با پنجرهٔ UAC (Run as administrator) اجرا می‌کند. true = درخواست فرستاده شد. */
    static boolean relaunchElevated(String[] args) {
        try {
            String exe = ProcessHandle.current().info().command().orElse("");
            if (exe.isEmpty()) return false;
            StringBuilder a = new StringBuilder();
            for (String s : args) a.append(" '").append(s.replace("'", "''")).append("'");
            String ps = "Start-Process -FilePath '" + exe.replace("'", "''") + "' -Verb RunAs"
                    + (args.length > 0 ? " -ArgumentList " + a.toString().trim().replace(" '", ",'").replaceFirst("^,", "") : "");
            new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps).start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ───────────── وضعیت ─────────────

    static boolean active() {
        Process e = engine;
        return e != null && e.isAlive();
    }

    // ───────────── دستورها (معادل foxssh-ctl) ─────────────

    /** null = موفق؛ در غیر این‌صورت متن خطا. اتصال در پس‌زمینه ادامه پیدا می‌کند. */
    static String start(String cfgText) {
        synchronized (LOCK) {
            teardown();
            try {
                Files.createDirectories(RUN);
                Files.createDirectories(STATE);
                for (String f : new String[]{"vpn-up", "status.properties", "engine.log", "hev.log", "engine.out"}) {
                    Files.deleteIfExists(RUN.resolve(f));
                }
            } catch (IOException e) {
                return e.getMessage();
            }
            Path hevExe = Platform.find("hev-socks5-tunnel.exe");
            Path wintun = Platform.find("wintun.dll");
            Path iran = Platform.find("iran_ipv4.txt");
            if (hevExe == null || wintun == null) {
                return t("فایل‌های تونل (hev-socks5-tunnel.exe و wintun.dll) پیدا نشد؛ برنامه را دوباره نصب کنید.",
                        "Tunnel files (hev-socks5-tunnel.exe, wintun.dll) not found; please reinstall.");
            }
            final String[] net = defaultRoute();
            if (net == null) return t("اتصال اینترنت پیدا نشد (مسیر پیش‌فرض ندارد).", "No internet connection found (no default route).");
            try {
                Files.write(RUN.resolve("config.properties"), (cfgText + "\nbindIp=" + net[2] + "\n").getBytes(StandardCharsets.UTF_8));
                String javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
                if (!Files.exists(Paths.get(javaw))) javaw = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
                List<String> cmd = new ArrayList<>();
                cmd.add(javaw);
                cmd.add("-Xmx128m");
                cmd.add("-Dfile.encoding=UTF-8");
                cmd.add("-cp");
                cmd.add(System.getProperty("java.class.path"));
                cmd.add("com.foxssh.desktop.Engine");
                cmd.add("--config");
                cmd.add(RUN.resolve("config.properties").toString());
                cmd.add("--run");
                cmd.add(RUN.toString());
                cmd.add("--state");
                cmd.add(STATE.toString());
                if (iran != null) {
                    cmd.add("--shared");
                    cmd.add(iran.getParent().toString());
                }
                final Process ep = new ProcessBuilder(cmd).redirectErrorStream(true)
                        .redirectOutput(RUN.resolve("engine.out").toFile()).start();
                engine = ep;
                Properties cp = new Properties();
                try (Reader r = new java.io.StringReader(cfgText)) {
                    cp.load(r);
                }
                final String dns1 = cp.getProperty("dns1", "1.1.1.1").trim().isEmpty() ? "1.1.1.1" : cp.getProperty("dns1", "1.1.1.1").trim();
                final String dns2 = cp.getProperty("dns2", "").trim();
                Thread th = new Thread(() -> bringUp(ep, hevExe, net, dns1, dns2), "fox-win-up");
                th.setDaemon(true);
                th.start();
            } catch (IOException e) {
                teardown();
                return e.getMessage();
            }
            return null;
        }
    }

    static String stop() {
        synchronized (LOCK) {
            teardown();
            writeStopped("");
        }
        return null;
    }

    static String updateList() {
        try {
            Files.createDirectories(STATE);
            IranList.update(STATE);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    static String forgetKeys() {
        try {
            Files.deleteIfExists(STATE.resolve("known_hosts.properties"));
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    // ───────────── راه‌اندازی (پس‌زمینه) ─────────────

    private static void bringUp(Process ep, Path hevExe, String[] net, String dns1, String dns2) {
        try {
            // ۱) منتظر آماده‌شدن SOCKS و IP سرور
            int port = 0;
            String serverIp = "";
            for (int i = 0; i < 600; i++) {
                if (!ep.isAlive()) {
                    cleanupAfterEngine(ep);
                    return;
                }
                Properties st = readStatus();
                port = toInt(st.getProperty("socksPort"));
                serverIp = st.getProperty("serverIp", "");
                if (port > 0 && !serverIp.isEmpty()) break;
                port = 0;
                Thread.sleep(100);
            }
            if (port == 0) {
                fail(ep, t("SOCKS آماده نشد", "SOCKS did not become ready"));
                return;
            }
            // ۲) اتصال به خود سرور SSH از کارت اصلی (جلوگیری از حلقه)
            // مسیر واقعی فعلی به سرور را از خود ویندوز می‌پرسیم (قبل از ساخت تونل):
            //   سرور در همان شبکهٔ محلی (on-link) → مسیر connected (/24 و ...) از 0/1 دقیق‌تر است و نیازی به مسیر اضافه نیست
            //   سرور دور → مسیر /32 از طریق همان گیت‌وی/کارتی که ویندوز خودش انتخاب می‌کند
            String best = ps("Find-NetRoute -RemoteIPAddress '" + serverIp + "' -ErrorAction SilentlyContinue"
                    + " | Where-Object { $_.NextHop -ne $null } | Select-Object -First 1"
                    + " | ForEach-Object { '{0} {1}' -f $_.NextHop, $_.InterfaceIndex }").trim();
            String[] bp = best.split("\\s+");
            String gw = net[0], ifx = net[1];
            boolean onLink = false;
            if (bp.length == 2 && bp[1].matches("\\d+")) {
                if (bp[0].equals("0.0.0.0") || bp[0].equals("::")) onLink = true;
                else if (bp[0].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    gw = bp[0];
                    ifx = bp[1];
                }
            }
            if (onLink) {
                log("route: " + serverIp + " on-link (if " + bp[1] + ") — no extra route needed");
            } else {
                String ro = run("route", "add", serverIp, "mask", "255.255.255.255", gw, "metric", "1", "if", ifx).trim();
                hostRouteIp = serverIp;
                log("route add " + serverIp + " via " + gw + " (if " + ifx + "): " + ro);
            }

            // ۳) tun2socks با Wintun
            String yml = "tunnel:\n  name: " + ADAPTER + "\n  mtu: 1500\n  ipv4: 198.18.0.1\n"
                    + "socks5:\n  port: " + port + "\n  address: 127.0.0.1\n  udp: 'udp'\n"
                    + "misc:\n  task-stack-size: 81920\n  tcp-buffer-size: 65536\n  connect-timeout: 15000\n"
                    + "  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n";
            Path ymlFile = RUN.resolve("hev.yml");
            Files.write(ymlFile, yml.getBytes(StandardCharsets.UTF_8));
            Process hp = new ProcessBuilder(hevExe.toString(), ymlFile.toString())
                    .directory(hevExe.getParent().toFile()).redirectErrorStream(true)
                    .redirectOutput(RUN.resolve("hev.log").toFile()).start();
            hev = hp;
            String idx = "";
            for (int i = 0; i < 40 && idx.isEmpty(); i++) {
                if (!hp.isAlive()) {
                    fail(ep, t("tun2socks اجرا نشد (درایور Wintun؟). hev.log را ببینید.", "tun2socks failed to start (Wintun driver?). See hev.log."));
                    return;
                }
                idx = ps("$a = Get-NetAdapter -Name '" + ADAPTER + "' -ErrorAction SilentlyContinue; if ($a) { $a.ifIndex }").trim();
                if (!idx.matches("\\d+")) {
                    idx = "";
                    Thread.sleep(500);
                }
            }
            if (idx.isEmpty()) {
                fail(ep, t("کارت مجازی تونل ساخته نشد", "Tunnel adapter was not created"));
                return;
            }

            // ۴) مسیرها + DNS
            String dns = "'" + dns1 + "'" + (dns2.isEmpty() ? "" : ",'" + dns2 + "'");
            String script = "$ErrorActionPreference='Stop'; $i=" + idx + ";"
                    + "Set-NetIPInterface -InterfaceIndex $i -AddressFamily IPv4 -AutomaticMetric Disabled -InterfaceMetric 1;"
                    + "New-NetRoute -DestinationPrefix '0.0.0.0/1' -InterfaceIndex $i -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null;"
                    + "New-NetRoute -DestinationPrefix '128.0.0.0/1' -InterfaceIndex $i -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null;"
                    + "Set-DnsClientServerAddress -InterfaceIndex $i -ServerAddresses (" + dns + ");"
                    + "Clear-DnsClientCache";
            String out = ps(script);
            if (out.toLowerCase().contains("exception") || out.toLowerCase().contains("error")) {
                fail(ep, t("تنظیم مسیرها/DNS ناموفق: ", "Route/DNS setup failed: ") + out.trim());
                return;
            }
            log("routes+DNS ok (if " + idx + ")");
            Files.write(RUN.resolve("vpn-up"), new byte[0]);

            // ۵) نگهبان: هرکدام مرد، همه‌چیز پاک شود
            while (ep.isAlive() && hp.isAlive()) Thread.sleep(500);
            if (!hp.isAlive() && ep.isAlive() && engine == ep) {
                fail(ep, t("tun2socks متوقف شد", "tun2socks stopped"));
            } else {
                cleanupAfterEngine(ep);
            }
        } catch (InterruptedException e) {
            // خاموش‌شدن
        } catch (Exception e) {
            fail(ep, String.valueOf(e.getMessage()));
        }
    }

    private static void cleanupAfterEngine(Process ep) {
        synchronized (LOCK) {
            if (engine != ep) return;
            Properties st = readStatus();
            String err = st.getProperty("error", "");
            teardown();
            writeStopped(err);
        }
    }

    private static void fail(Process ep, String msg) {
        log(msg);
        synchronized (LOCK) {
            if (engine != ep) return;
            teardown();
            writeStopped(msg);
        }
    }

    // ───────────── پاک‌سازی ─────────────

    private static void teardown() {
        Process h = hev, e = engine;
        hev = null;
        engine = null;
        if (h != null) h.destroyForcibly();
        if (e != null) e.destroy();
        try {
            if (h != null) h.waitFor(3, TimeUnit.SECONDS);
            if (e != null && !e.waitFor(4, TimeUnit.SECONDS)) e.destroyForcibly();
        } catch (InterruptedException ignored) {
        }
        String ip = hostRouteIp;
        hostRouteIp = "";
        if (!ip.isEmpty()) run("route", "delete", ip);
        try {
            Files.deleteIfExists(RUN.resolve("vpn-up"));
            Files.deleteIfExists(RUN.resolve("config.properties"));
        } catch (IOException ignored) {
        }
    }

    private static void writeStopped(String err) {
        Properties p = readStatus();
        p.setProperty("state", "stopped");
        p.setProperty("error", err == null ? "" : err);
        p.setProperty("socksPort", "0");
        try {
            Files.createDirectories(RUN);
            try (java.io.Writer w = Files.newBufferedWriter(RUN.resolve("status.properties"), StandardCharsets.UTF_8)) {
                p.store(w, null);
            }
        } catch (IOException ignored) {
        }
    }

    // ───────────── ابزارها ─────────────

    static Properties readStatus() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(RUN.resolve("status.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
        }
        return p;
    }

    /** {gateway, ifIndex, localIp} مسیر پیش‌فرض واقعی (بدون کارت تونل). */
    private static String[] defaultRoute() {
        String out = ps("$r = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -AddressFamily IPv4 -ErrorAction SilentlyContinue"
                + " | Where-Object { $_.NextHop -ne '0.0.0.0' -and $_.InterfaceAlias -ne '" + ADAPTER + "' }"
                + " | Sort-Object RouteMetric | Select-Object -First 1;"
                + "if ($r) { $a = Get-NetIPAddress -InterfaceIndex $r.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue"
                + " | Where-Object { $_.PrefixOrigin -ne 'WellKnown' } | Select-Object -First 1;"
                + " Write-Output ('{0} {1} {2}' -f $r.NextHop, $r.InterfaceIndex, $a.IPAddress) }").trim();
        String[] p = out.split("\\s+");
        if (p.length == 3 && p[0].matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && p[1].matches("\\d+") && p[2].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) return p;
        return null;
    }

    private static String ps(String script) {
        return run("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script);
    }

    private static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor(60, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) {
            return "Exception: " + e;
        }
    }

    private static synchronized void log(String s) {
        try {
            Files.write(RUN.resolve("engine.log"), (FMT.format(new Date()) + "  [win] " + s + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String t(String fa, String en) {
        return L.isFa() ? fa : en;
    }
}
