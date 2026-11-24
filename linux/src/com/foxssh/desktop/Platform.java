package com.foxssh.desktop;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/** مسیرها و تشخیص سیستم‌عامل (لینوکس / ویندوز). */
final class Platform {
    static final boolean WIN = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private Platform() {
    }

    private static Path winLocal() {
        String v = System.getenv("LOCALAPPDATA");
        return v != null && !v.isEmpty() ? Paths.get(v, "FoxVPN") : Paths.get(System.getProperty("user.home"), "AppData", "Local", "FoxVPN");
    }

    /** تنظیمات کاربر (کانفیگ‌ها و زبان). */
    static Path configDir() {
        if (WIN) {
            String v = System.getenv("APPDATA");
            return v != null && !v.isEmpty() ? Paths.get(v, "FoxVPN") : winLocal();
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isEmpty() ? Paths.get(xdg) : Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve("foxssh");
    }

    /** فایل‌های وضعیت و لاگ موتور. */
    static Path runDir() {
        return WIN ? winLocal().resolve("run") : Paths.get("/run/foxssh");
    }

    /** کلیدهای میزبان و لیست ایران. */
    static Path stateDir() {
        return WIN ? winLocal().resolve("state") : Paths.get("/var/lib/foxssh");
    }

    /** پوشهٔ برنامهٔ نصب‌شده (ویندوز): کنار FoxVPN.exe. */
    static Path exeDir() {
        try {
            String cmd = ProcessHandle.current().info().command().orElse("");
            if (!cmd.isEmpty() && cmd.toLowerCase().endsWith("foxvpn.exe")) return Paths.get(cmd).getParent();
        } catch (Exception ignored) {
        }
        String cp = System.getProperty("java.class.path", "");
        String first = cp.split(File.pathSeparator)[0];
        Path p = Paths.get(first).toAbsolutePath().getParent();
        return p != null && p.getParent() != null && p.getFileName().toString().equals("app") ? p.getParent() : p;
    }

    /** جستجوی فایل همراه برنامه (hev-socks5-tunnel.exe، wintun.dll، iran_ipv4.txt). */
    static Path find(String name) {
        Path d = exeDir();
        for (Path c : new Path[]{d.resolve("tools"), d, d.resolve("app"), d.resolve("app").resolve("tools")}) {
            if (c.resolve(name).toFile().isFile()) return c.resolve(name);
        }
        return null;
    }
}
