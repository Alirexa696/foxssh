package com.foxssh.desktop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.foxssh.vpn.core.BypassRules;

/** تنظیمات و کانفیگ‌های کاربر در ~/.config/foxssh/settings.properties (دسترسی 600). */
final class Store {
    static final class Profile {
        String id = "", name = "", host = "", user = "", pass = "";
        int port = 22, udpgw = 7300;

        String title() { return name.isEmpty() ? host : name; }
    }

    private final Path file;
    private final Properties p = new Properties();

    Store() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isEmpty() ? Paths.get(xdg) : Paths.get(System.getProperty("user.home"), ".config");
        file = base.resolve("foxssh").resolve("settings.properties");
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, null);
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
        }
    }

    // ───── کانفیگ‌ها ─────

    synchronized List<Profile> profiles() {
        List<Profile> out = new ArrayList<>();
        for (String id : p.getProperty("ids", "").split(",")) {
            if (id.isEmpty()) continue;
            Profile f = new Profile();
            f.id = id;
            String k = "p." + id + ".";
            f.name = p.getProperty(k + "name", "");
            f.host = p.getProperty(k + "host", "");
            f.user = p.getProperty(k + "user", "");
            f.pass = p.getProperty(k + "pass", "");
            f.port = toInt(p.getProperty(k + "port"), 22);
            f.udpgw = toInt(p.getProperty(k + "udpgw"), 7300);
            out.add(f);
        }
        return out;
    }

    synchronized Profile selected() {
        List<Profile> l = profiles();
        String sel = p.getProperty("selected", "");
        for (Profile f : l) if (f.id.equals(sel)) return f;
        return l.isEmpty() ? null : l.get(0);
    }

    synchronized void setSelected(String id) {
        p.setProperty("selected", id);
        save();
    }

    synchronized void saveProfile(Profile f) {
        List<Profile> l = profiles();
        boolean isNew = f.id.isEmpty();
        if (isNew) f.id = UUID.randomUUID().toString().substring(0, 8);
        String k = "p." + f.id + ".";
        p.setProperty(k + "name", f.name);
        p.setProperty(k + "host", f.host);
        p.setProperty(k + "user", f.user);
        p.setProperty(k + "pass", f.pass);
        p.setProperty(k + "port", String.valueOf(f.port));
        p.setProperty(k + "udpgw", String.valueOf(f.udpgw));
        if (isNew) {
            StringBuilder ids = new StringBuilder();
            for (Profile o : l) ids.append(o.id).append(',');
            ids.append(f.id);
            p.setProperty("ids", ids.toString());
            p.setProperty("selected", f.id);
        }
        save();
    }

    synchronized void deleteProfile(String id) {
        StringBuilder ids = new StringBuilder();
        for (Profile o : profiles()) {
            if (!o.id.equals(id)) {
                if (ids.length() > 0) ids.append(',');
                ids.append(o.id);
            }
        }
        for (String f : new String[]{"name", "host", "user", "pass", "port", "udpgw"}) p.remove("p." + id + "." + f);
        p.setProperty("ids", ids.toString());
        if (p.getProperty("selected", "").equals(id)) p.remove("selected");
        save();
    }

    // ───── تنظیمات ─────

    String dns1() { return p.getProperty("dns1", "1.1.1.1"); }
    String dns2() { return p.getProperty("dns2", "8.8.8.8"); }
    boolean bypass() { return !"false".equals(p.getProperty("bypass", "true")); }

    synchronized void setNet(String d1, String d2, boolean bypass) {
        p.setProperty("dns1", d1);
        p.setProperty("dns2", d2);
        p.setProperty("bypass", String.valueOf(bypass));
        save();
    }

    synchronized List<String> manual() {
        List<String> out = new ArrayList<>();
        for (String s : p.getProperty("manual", "").split(";")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    /** null = موفق؛ وگرنه متن خطا */
    synchronized String addManual(String raw) {
        if (BypassRules.classify(raw) == BypassRules.KIND_INVALID) return "ورودی نامعتبر است";
        String n = BypassRules.normalizeEntry(raw);
        List<String> l = manual();
        if (l.contains(n)) return "قبلاً اضافه شده";
        l.add(n);
        p.setProperty("manual", String.join(";", l));
        save();
        return null;
    }

    synchronized void removeManual(String e) {
        List<String> l = manual();
        l.remove(e);
        p.setProperty("manual", String.join(";", l));
        save();
    }

    /** متن کانفیگ برای موتور (از stdin به foxssh-ctl داده می‌شود). */
    String engineConfig(Profile f, String user, String pass) {
        Properties c = new Properties();
        c.setProperty("host", f.host);
        c.setProperty("port", String.valueOf(f.port));
        c.setProperty("user", user);
        c.setProperty("pass", pass);
        c.setProperty("udpgw", String.valueOf(f.udpgw));
        c.setProperty("dns1", dns1());
        c.setProperty("dns2", dns2());
        c.setProperty("bypass", String.valueOf(bypass()));
        c.setProperty("manual", String.join(";", manual()));
        java.io.StringWriter w = new java.io.StringWriter();
        try {
            c.store(w, null);
        } catch (IOException ignored) {
        }
        return w.toString();
    }

    private static int toInt(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return d;
        }
    }
}
