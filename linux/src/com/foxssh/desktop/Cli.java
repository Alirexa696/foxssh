package com.foxssh.desktop;

import java.io.BufferedReader;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.foxssh.vpn.core.BypassRules;

/**
 * رابط خط فرمان «foxssh ...» (بدون پنجره): همان کارهای برنامهٔ گرافیکی - اتصال/قطع، مدیریت کانفیگ‌ها،
 * DNS، بایپس ایران، لاگ - به‌علاوهٔ حالت «سیستمی» که کانفیگ‌ها را در /etc/foxssh نگه می‌دارد
 * و سرویس foxssh-auto.service هنگام بوت از آن‌ها استفاده می‌کند.
 *
 * دو دامنه (scope):
 *   user   (پیش‌فرض برای کاربر عادی؛ با --no-system برای root هم) ~/.config/foxssh/settings.properties - همان فایلی که برنامهٔ گرافیکی می‌خواند
 *   system (پیش‌فرض برای root، یا با --system)  /etc/foxssh/foxssh.conf  و  /etc/foxssh/profiles.d/*.conf
 * دستورهایی که به root نیاز دارند خودکار با pkexec بالا می‌روند.
 */
public final class Cli {
    private static final String VERSION = readVersion();
    private static final Path ETC = Paths.get(env("FOXSSH_ETC", "/etc/foxssh"));
    private static final Path RUN = Paths.get(env("FOXSSH_RUN", "/run/foxssh"));
    private static final String CTL = env("FOXSSH_CTL", "/usr/lib/foxssh/foxssh-ctl");
    private static final String AUTO_UNIT = "foxssh-auto.service";
    private static final String MAIN_UNIT = "foxssh.service";
    static final int EX_USAGE = 2, EX_DOWN = 3, EX_CONFIG = 78;

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? d : v;
    }

    private static String readVersion() {
        try (InputStream in = Cli.class.getResourceAsStream("/version.txt")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
        }
        return "dev";
    }

    /** خطای قابل‌نمایش به کاربر (پیام کوتاه، بدون stack trace). */
    private static final class CliError extends Exception {
        final int code;

        CliError(String m) { this(m, 1); }

        CliError(String m, int code) {
            super(m);
            this.code = code;
        }
    }

    // ───────────── پارس آرگومان‌ها ─────────────

    private static final Map<String, String> SHORT = new HashMap<>();
    private static final Set<String> VALUE_OPTS = new HashSet<>(Arrays.asList(
            "name", "host", "port", "user", "pass", "pass-file", "udpgw", "local", "remote", "dynamic",
            "dns1", "dns2", "lines", "timeout"));
    private static final Set<String> FLAG_OPTS = new HashSet<>(Arrays.asList(
            "system", "no-system", "json", "follow", "quiet", "no-wait", "ask-pass", "pass-stdin",
            "any-local", "any-remote", "no-any-local", "no-any-remote", "clear-tunnels", "show-pass",
            "bypass", "no-bypass", "help", "version", "yes", "elevated", "no-update"));

    static {
        SHORT.put("L", "local");
        SHORT.put("R", "remote");
        SHORT.put("D", "dynamic");
        SHORT.put("p", "port");
        SHORT.put("u", "user");
        SHORT.put("n", "lines");
        SHORT.put("f", "follow");
        SHORT.put("j", "json");
        SHORT.put("q", "quiet");
        SHORT.put("y", "yes");
        SHORT.put("h", "help");
        SHORT.put("V", "version");
    }

    private static final class Opts {
        final Map<String, List<String>> vals = new LinkedHashMap<>();
        final Set<String> flags = new HashSet<>();
        final List<String> pos = new ArrayList<>();

        boolean has(String f) { return flags.contains(f); }

        String get(String k) {
            List<String> l = vals.get(k);
            return l == null || l.isEmpty() ? null : l.get(l.size() - 1);
        }

        List<String> all(String k) {
            List<String> l = vals.get(k);
            return l == null ? Collections.<String>emptyList() : l;
        }

        boolean hasAny(String... k) {
            for (String s : k) if (vals.containsKey(s) || flags.contains(s)) return true;
            return false;
        }
    }

    private static Opts parse(List<String> args) throws CliError {
        Opts o = new Opts();
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("--")) {
                o.pos.addAll(args.subList(i + 1, args.size()));
                break;
            }
            if (!a.startsWith("-") || a.equals("-")) {
                o.pos.add(a);
                continue;
            }
            String key, val = null;
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                key = eq > 0 ? a.substring(2, eq) : a.substring(2);
                if (eq > 0) val = a.substring(eq + 1);
            } else {
                String sk = a.substring(1, 2);
                key = SHORT.get(sk);
                if (key == null || a.length() < 2) throw new CliError("unknown option: " + a, EX_USAGE);
                if (a.length() > 2) val = a.substring(2).startsWith("=") ? a.substring(3) : a.substring(2); // -L8080:h:80
            }
            if (VALUE_OPTS.contains(key)) {
                if (val == null) {
                    if (i + 1 >= args.size()) throw new CliError("option needs a value: " + a, EX_USAGE);
                    val = args.get(++i);
                }
                List<String> l = o.vals.get(key);
                if (l == null) o.vals.put(key, l = new ArrayList<>());
                l.add(val);
            } else if (FLAG_OPTS.contains(key) || key.equals("follow") || key.equals("json") || key.equals("quiet")
                    || key.equals("yes") || key.equals("help") || key.equals("version")) {
                if (val != null) throw new CliError("option takes no value: " + a, EX_USAGE);
                o.flags.add(key);
            } else {
                throw new CliError("unknown option: " + a + "  (see: foxssh --help)", EX_USAGE);
            }
        }
        return o;
    }

    // ───────────── نقطهٔ ورود ─────────────

    public static void main(String[] argv) {
        int code;
        try {
            code = run(new ArrayList<>(Arrays.asList(argv)));
        } catch (CliError e) {
            if (!e.getMessage().isEmpty()) System.err.println("foxssh: " + e.getMessage());
            code = e.code;
        } catch (Exception e) {
            System.err.println("foxssh: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            code = 1;
        }
        System.out.flush();
        System.exit(code);
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    private static int run(List<String> args) throws Exception {
        List<String> orig = new ArrayList<>(args);
        boolean elevated = args.remove("--elevated");
        if (args.isEmpty()) {
            usage(System.out);
            return 0;
        }
        String cmd = args.remove(0);
        if (cmd.equals("-h") || cmd.equals("--help") || cmd.equals("help")) {
            usage(System.out);
            return 0;
        }
        if (cmd.equals("-V") || cmd.equals("--version") || cmd.equals("version")) {
            System.out.println("foxssh " + VERSION);
            return 0;
        }
        Opts o = parse(args);
        if (o.has("help")) {
            usage(System.out);
            return 0;
        }
        if (o.has("version")) {
            System.out.println("foxssh " + VERSION);
            return 0;
        }
        if (o.has("system") && o.has("no-system")) throw new CliError("--system and --no-system cannot be combined", EX_USAGE);
        boolean system = o.has("system") || (isRoot() && !o.has("no-system"));

        // دستورهایی که روی /etc می‌نویسند/می‌خوانند یا سرویس را کنترل می‌کنند باید root باشند
        boolean needRoot = cmd.equals("service") || cmd.equals("emit-config")
                || (system && Arrays.asList("list", "ls", "show", "add", "edit", "remove", "rm", "select", "use", "import",
                "dns", "bypass", "lang", "connect", "up").contains(cmd));
        if (needRoot && !isRoot()) {
            if (elevated) throw new CliError("root privileges are required");
            return elevate(orig, cmd);
        }

        switch (cmd) {
            case "connect": case "up": return cmdConnect(o, system);
            case "disconnect": case "down": case "stop": return cmdDisconnect(o);
            case "status": return cmdStatus(o);
            case "logs": case "log": return cmdLogs(o);
            case "list": case "ls": return cmdList(o, system);
            case "show": return cmdShow(o, system);
            case "add": return cmdAdd(o, system);
            case "edit": return cmdEdit(o, system);
            case "remove": case "rm": return cmdRemove(o, system);
            case "select": case "use": return cmdSelect(o, system);
            case "import": return cmdImport(o, system);
            case "dns": return cmdDns(o, system);
            case "bypass": return cmdBypass(o, system);
            case "lang": return cmdLang(o, system);
            case "update-list": return ctlPrint("update-list", null);
            case "forget-keys": return ctlPrint("forget-keys", null);
            case "service": return cmdService(o);
            case "emit-config": return cmdEmitConfig(o);
            default:
                throw new CliError("unknown command: " + cmd + "  (see: foxssh --help)", EX_USAGE);
        }
    }

    private static void usage(java.io.PrintStream out) {
        out.println("Fox SSH " + VERSION + " - system-wide SSH tunnel VPN\n"
                + "\n"
                + "Usage: foxssh                         open the GUI\n"
                + "       foxssh --hidden                start the GUI hidden in the tray\n"
                + "       foxssh COMMAND [options]\n"
                + "\n"
                + "Connection:\n"
                + "  connect [NAME]        connect (NAME = profile name/id; default profile if omitted)\n"
                + "  disconnect            disconnect (alias: down, stop)\n"
                + "  status [--json]       show state; exit code 0 = connected, 3 = not connected\n"
                + "  logs [-n N] [-f]      show engine log (-f to follow)\n"
                + "\n"
                + "Profiles:\n"
                + "  list                  list profiles (* = default)\n"
                + "  show NAME             show one profile (--show-pass to reveal the password)\n"
                + "  add [NAME] --host H --user U (--pass P | --pass-file F | --pass-stdin | --ask-pass)\n"
                + "  edit NAME [same options as add]\n"
                + "  remove NAME           delete a profile\n"
                + "  select NAME           make NAME the default profile\n"
                + "  import FILE           import a profile file (key=value format, see foxssh(1))\n"
                + "\n"
                + "Profile options (add / edit / connect):\n"
                + "  --name N  --host H  -p, --port P  -u, --user U  --udpgw P\n"
                + "  -L SRC:HOST:PORT      local forward   (repeatable)\n"
                + "  -R SRC:HOST:PORT      remote forward  (repeatable)\n"
                + "  -D PORT               dynamic SOCKS forward (repeatable)\n"
                + "  --any-local / --any-remote   listen on 0.0.0.0 instead of 127.0.0.1\n"
                + "  --clear-tunnels       (edit) drop existing forwards first\n"
                + "\n"
                + "Settings:\n"
                + "  dns [D1 [D2]]        show / set DNS servers\n"
                + "  bypass [on|off|list|add X|remove X]   Iran bypass (X = IP, CIDR or domain)\n"
                + "  lang [fa|en]         show / set language of engine messages\n"
                + "  update-list          update the Iran IP list now\n"
                + "  forget-keys          forget saved SSH server keys\n"
                + "\n"
                + "Boot service (profile read from /etc/foxssh):\n"
                + "  service enable [NAME]   enable + start foxssh-auto.service (auto-connect at boot)\n"
                + "  service disable | start | stop | restart | status\n"
                + "\n"
                + "Scope:\n"
                + "  --system   use /etc/foxssh (default when run as root / sudo)\n"
                + "  --no-system   use ~/.config/foxssh (shared with the GUI; default for normal users)\n"
                + "  Commands that need root re-run themselves through pkexec automatically.\n"
                + "\n"
                + "Examples:\n"
                + "  foxssh add --name work --host example.com --user me --ask-pass -D 1080\n"
                + "  foxssh connect work\n"
                + "  sudo foxssh add --name office --host vpn.example.com -u me --pass-file /root/pw -L 8080:intranet:80\n"
                + "  sudo foxssh service enable office\n"
                + "  foxssh status --json");
    }

    // ───────────── بالا بردن دسترسی ─────────────

    private static int elevate(List<String> orig, String cmd) throws Exception {
        if (!new File(CTL).canExecute()) throw new CliError("this command needs root; run it with sudo");
        List<String> c = new ArrayList<>();
        if (new File("/usr/bin/pkexec").canExecute() || new File("/bin/pkexec").canExecute()) c.add("pkexec");
        else c.add("sudo");
        c.add(CTL);
        c.add("cli");
        for (int i = 0; i < orig.size(); i++) {
            String a = orig.get(i);
            c.add(absolutizeArg(orig, i, a, cmd));
        }
        c.add("--elevated");
        Process p = new ProcessBuilder(c).inheritIO().start();
        return p.waitFor();
    }

    /** pkexec مسیر جاری را عوض می‌کند؛ پس مسیر فایل‌های نسبی را مطلق می‌کنیم. */
    private static String absolutizeArg(List<String> orig, int i, String a, String cmd) {
        boolean isPath = false;
        if (i > 0 && orig.get(i - 1).equals("--pass-file")) isPath = true;
        if (a.startsWith("--pass-file=")) return "--pass-file=" + new File(a.substring(12)).getAbsolutePath();
        if (cmd.equals("import") && i > 0 && !a.startsWith("-") && orig.indexOf(cmd) < i) isPath = true;
        return isPath ? new File(a).getAbsolutePath() : a;
    }

    // ───────────── دامنه‌ها (کاربر / سیستم) ─────────────

    private interface Scope {
        String label();

        Store settings();

        List<Store.Profile> profiles() throws IOException;

        void save(Store.Profile p) throws IOException;

        void delete(Store.Profile p) throws IOException;

        String selectedKey();

        void select(Store.Profile p) throws IOException;
    }

    private static final class UserScope implements Scope {
        final Store st = new Store();

        public String label() { return "user"; }

        public Store settings() { return st; }

        public List<Store.Profile> profiles() { return st.profiles(); }

        public void save(Store.Profile p) throws IOException {
            st.saveProfile(p);
            if (!Files.isReadable(st.path())) throw new IOException("could not write " + st.path());
        }

        public void delete(Store.Profile p) { st.deleteProfile(p.id); }

        public String selectedKey() {
            Store.Profile s = st.selected();
            return s == null ? "" : s.id;
        }

        public void select(Store.Profile p) { st.setSelected(p.id); }
    }

    private static final class SysScope implements Scope {
        final Store st = new Store(ETC.resolve("foxssh.conf"));
        final Path dir = ETC.resolve("profiles.d");

        public String label() { return "system"; }

        public Store settings() { return st; }

        public List<Store.Profile> profiles() throws IOException {
            List<Store.Profile> out = new ArrayList<>();
            if (!Files.isDirectory(dir)) return out;
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.conf")) {
                for (Path f : ds) files.add(f);
            }
            Collections.sort(files);
            for (Path f : files) {
                Properties pr = new Properties();
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    pr.load(r);
                }
                String fn = f.getFileName().toString();
                out.add(fromProps(fn.substring(0, fn.length() - 5), pr, f.toString()));
            }
            return out;
        }

        public void save(Store.Profile p) throws IOException {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            if (p.id.isEmpty()) {
                String base = sanitize(p.name.isEmpty() ? p.host : p.name);
                String id = base;
                int n = 2;
                while (Files.exists(dir.resolve(id + ".conf"))) id = base + "-" + n++;
                p.id = id;
            }
            Path f = dir.resolve(p.id + ".conf");
            p.src = f.toString();
            Path tmp = dir.resolve("." + p.id + ".tmp");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(tmp, toConf(p).getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        public void delete(Store.Profile p) throws IOException {
            Files.deleteIfExists(dir.resolve(p.id + ".conf"));
            if (selectedKey().equals(p.id)) st.put("selected", null);
        }

        public String selectedKey() { return st.get("selected", ""); }

        public void select(Store.Profile p) throws IOException { st.put("selected", p.id); }
    }

    private static Scope scope(boolean system) {
        return system ? new SysScope() : new UserScope();
    }

    private static String sanitize(String s) {
        String r = s.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|-+$", "");
        return r.isEmpty() ? "profile" : r.length() > 60 ? r.substring(0, 60) : r;
    }

    // ───────────── فرمت فایل کانفیگ (برای /etc) ─────────────

    private static Store.Profile fromProps(String id, Properties pr, String src) {
        Store.Profile f = new Store.Profile();
        f.id = id;
        f.src = src;
        f.name = pr.getProperty("name", id).trim();
        f.host = pr.getProperty("host", "").trim();
        f.user = pr.getProperty("user", "").trim();
        f.pass = pr.getProperty("pass", "");
        f.port = Store.toInt(pr.getProperty("port", "22"), 22);
        f.udpgw = Store.toInt(pr.getProperty("udpgw", "7300"), 7300);
        f.anyLocal = bool(pr.getProperty("anyLocal", pr.getProperty("any_local", "false")));
        f.anyRemote = bool(pr.getProperty("anyRemote", pr.getProperty("any_remote", "false")));
        List<Tunnel> t = new ArrayList<>(Tunnel.parseList(pr.getProperty("tunnels", "")));
        addSpecs(t, 'L', pr.getProperty("local", ""));
        addSpecs(t, 'R', pr.getProperty("remote", ""));
        addSpecs(t, 'D', pr.getProperty("dynamic", ""));
        f.tunnels = Tunnel.encodeList(t);
        return f;
    }

    private static boolean bool(String s) {
        s = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("on");
    }

    private static void addSpecs(List<Tunnel> out, char type, String csv) {
        for (String s : csv.split("[,;]")) {
            if (s.trim().isEmpty()) continue;
            try {
                out.add(parseSpec(type, s.trim()));
            } catch (CliError ignored) {
            }
        }
    }

    /** L/R: «SRC:HOST:PORT»، D: «PORT». */
    private static Tunnel parseSpec(char type, String s) throws CliError {
        Tunnel t = null;
        try {
            if (type == 'D') {
                t = Tunnel.make('D', Integer.parseInt(s), "");
            } else {
                int i = s.indexOf(':');
                if (i > 0 && (s.indexOf('[') >= 0 || s.chars().filter(ch -> ch == ':').count() == 2)) t = Tunnel.make(type, Integer.parseInt(s.substring(0, i)), s.substring(i + 1));
            }
        } catch (NumberFormatException ignored) {
        }
        if (t == null) {
            throw new CliError("invalid forward '" + s + "' - expected "
                    + (type == 'D' ? "-D PORT" : "-" + type + " SRCPORT:HOST:PORT"), EX_USAGE);
        }
        return t;
    }

    private static String esc(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case ' ': b.append(i == 0 ? "\\ " : " "); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String toConf(Store.Profile p) {
        List<String> l = new ArrayList<>(), r = new ArrayList<>(), d = new ArrayList<>();
        for (Tunnel t : Tunnel.parseList(p.tunnels)) {
            if (t.type == 'L') l.add(t.srcPort + ":" + t.dest);
            else if (t.type == 'R') r.add(t.srcPort + ":" + t.dest);
            else d.add(String.valueOf(t.srcPort));
        }
        StringBuilder b = new StringBuilder();
        b.append("# Fox SSH profile - edit freely (key=value). Keep this file readable by root only (chmod 600).\n");
        b.append("name=").append(esc(p.name.isEmpty() ? p.id : p.name)).append('\n');
        b.append("host=").append(esc(p.host)).append('\n');
        b.append("port=").append(p.port).append('\n');
        b.append("user=").append(esc(p.user)).append('\n');
        b.append("pass=").append(esc(p.pass)).append('\n');
        b.append("udpgw=").append(p.udpgw).append('\n');
        if (!l.isEmpty()) b.append("local=").append(String.join(",", l)).append('\n');
        if (!r.isEmpty()) b.append("remote=").append(String.join(",", r)).append('\n');
        if (!d.isEmpty()) b.append("dynamic=").append(String.join(",", d)).append('\n');
        if (p.anyLocal) b.append("anyLocal=true\n");
        if (p.anyRemote) b.append("anyRemote=true\n");
        return b.toString();
    }

    // ───────────── پیدا کردن/ساختن کانفیگ ─────────────

    private static Store.Profile find(Scope sc, String key) throws Exception {
        List<Store.Profile> l = sc.profiles();
        for (Store.Profile p : l) if (p.id.equals(key)) return p;
        List<Store.Profile> hit = new ArrayList<>();
        for (Store.Profile p : l) if (p.name.equalsIgnoreCase(key) || p.title().equalsIgnoreCase(key) || p.id.equalsIgnoreCase(key)) hit.add(p);
        if (hit.size() == 1) return hit.get(0);
        if (hit.size() > 1) throw new CliError("'" + key + "' matches more than one profile; use its id (see: foxssh list)");
        throw new CliError("no such profile: " + key + " (" + sc.label() + " scope)"
                + (sc instanceof UserScope && !isRoot() ? "  - for /etc profiles add --system" : ""));
    }

    private static Store.Profile defaultProfile(Scope sc) throws Exception {
        List<Store.Profile> l = sc.profiles();
        String k = sc.selectedKey();
        if (!k.isEmpty()) for (Store.Profile p : l) if (p.id.equals(k) || p.name.equalsIgnoreCase(k)) return p;
        if (l.size() == 1) return l.get(0);
        if (l.isEmpty()) throw new CliError("no profiles in " + sc.label() + " scope - add one: foxssh add --host H --user U --ask-pass");
        throw new CliError("no default profile - pass a NAME or run: foxssh select NAME");
    }

    private static String readPassword(Opts o, boolean required) throws Exception {
        if (o.get("pass") != null) return o.get("pass");
        if (o.get("pass-file") != null) {
            List<String> ls = Files.readAllLines(Paths.get(o.get("pass-file")), StandardCharsets.UTF_8);
            if (ls.isEmpty()) throw new CliError("password file is empty: " + o.get("pass-file"));
            return ls.get(0);
        }
        if (o.has("pass-stdin")) {
            String s = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            if (s == null) throw new CliError("no password on stdin");
            return s;
        }
        if (o.has("ask-pass") || required) {
            String fromEnv = System.getenv("FOXSSH_PASS");
            if (!o.has("ask-pass") && fromEnv != null && !fromEnv.isEmpty()) return fromEnv;
            Console c = System.console();
            if (c != null) {
                char[] pw = c.readPassword("SSH password: ");
                if (pw != null && pw.length > 0) return new String(pw);
            }
            if (o.has("ask-pass") || required) throw new CliError("password required: use --pass-file, --pass-stdin, FOXSSH_PASS, or run in a terminal with --ask-pass");
        }
        return null;
    }

    /** پرچم‌ها را روی کانفیگ اعمال می‌کند (برای add / edit / connect). */
    private static void apply(Store.Profile p, Opts o, boolean edit) throws Exception {
        if (o.get("name") != null) p.name = o.get("name").trim();
        if (o.get("host") != null) p.host = o.get("host").trim();
        if (o.get("user") != null) p.user = o.get("user").trim();
        if (o.get("port") != null) p.port = port(o.get("port"), "port");
        if (o.get("udpgw") != null) p.udpgw = port(o.get("udpgw"), "udpgw");
        String pw = readPassword(o, false);
        if (pw != null) p.pass = pw;
        List<Tunnel> t = o.has("clear-tunnels") ? new ArrayList<Tunnel>() : new ArrayList<>(Tunnel.parseList(p.tunnels));
        for (String s : o.all("local")) t.add(parseSpec('L', s));
        for (String s : o.all("remote")) t.add(parseSpec('R', s));
        for (String s : o.all("dynamic")) t.add(parseSpec('D', s));
        p.tunnels = Tunnel.encodeList(t);
        if (o.has("any-local")) p.anyLocal = true;
        if (o.has("no-any-local")) p.anyLocal = false;
        if (o.has("any-remote")) p.anyRemote = true;
        if (o.has("no-any-remote")) p.anyRemote = false;
    }

    private static int port(String s, String what) throws CliError {
        try {
            int v = Integer.parseInt(s.trim());
            if (v >= 1 && v <= 65535) return v;
        } catch (NumberFormatException ignored) {
        }
        throw new CliError("invalid " + what + ": " + s, EX_USAGE);
    }

    private static void validate(Store.Profile p) throws CliError {
        if (p.host.isEmpty() || p.host.matches(".*[\\s;|].*")) throw new CliError("invalid or missing --host", EX_USAGE);
        if (p.user.isEmpty()) throw new CliError("missing --user", EX_USAGE);
        if (p.pass.isEmpty()) throw new CliError("missing password (--pass-file / --pass-stdin / --ask-pass)", EX_USAGE);
    }

    // ───────────── مدیریت کانفیگ‌ها ─────────────

    private static int cmdList(Opts o, boolean system) throws Exception {
        Scope sc = scope(system);
        List<Store.Profile> l = sc.profiles();
        String sel = sc.selectedKey();
        Store.Profile def = null;
        try {
            def = defaultProfile(sc);
        } catch (CliError ignored) {
        }
        if (o.has("json")) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                Store.Profile p = l.get(i);
                if (i > 0) b.append(',');
                b.append("{\"id\":").append(js(p.id)).append(",\"name\":").append(js(p.title())).append(",\"host\":").append(js(p.host))
                        .append(",\"port\":").append(p.port).append(",\"user\":").append(js(p.user))
                        .append(",\"default\":").append(def != null && def.id.equals(p.id)).append('}');
            }
            System.out.println(b.append(']'));
            return 0;
        }
        if (l.isEmpty()) {
            System.out.println("(no profiles in " + sc.label() + " scope)");
            return 0;
        }
        System.out.printf("  %-16s %-22s %-32s %-14s %s%n", "ID", "NAME", "SERVER", "USER", "FORWARDS");
        for (Store.Profile p : l) {
            StringBuilder fw = new StringBuilder();
            for (Tunnel t : Tunnel.parseList(p.tunnels)) {
                if (fw.length() > 0) fw.append(' ');
                fw.append(t.type == 'D' ? "-D" + t.srcPort : "-" + t.type + t.srcPort + ":" + t.dest);
            }
            System.out.printf("%s %-16s %-22s %-32s %-14s %s%n", def != null && def.id.equals(p.id) ? "*" : " ", p.id, p.title(),
                    p.host + ":" + p.port, p.user, fw);
        }
        if (sel.isEmpty() && def == null) System.out.println("\n(no default profile - foxssh select NAME)");
        return 0;
    }

    private static int cmdShow(Opts o, boolean system) throws Exception {
        if (o.pos.isEmpty()) throw new CliError("usage: foxssh show NAME", EX_USAGE);
        Scope sc = scope(system);
        Store.Profile p = find(sc, o.pos.get(0));
        System.out.println("name:    " + p.title());
        System.out.println("id:      " + p.id);
        System.out.println("scope:   " + sc.label() + (p.src.isEmpty() ? "" : "  (" + p.src + ")"));
        System.out.println("server:  " + p.host + ":" + p.port);
        System.out.println("user:    " + p.user);
        System.out.println("pass:    " + (o.has("show-pass") ? p.pass : p.pass.isEmpty() ? "(none)" : "********"));
        System.out.println("udpgw:   " + p.udpgw);
        for (Tunnel t : Tunnel.parseList(p.tunnels)) System.out.println("forward: " + (t.type == 'D' ? "-D " + t.srcPort : "-" + t.type + " " + t.srcPort + ":" + t.dest));
        if (p.anyLocal) System.out.println("any-local:  yes (0.0.0.0)");
        if (p.anyRemote) System.out.println("any-remote: yes (0.0.0.0)");
        return 0;
    }

    private static int cmdAdd(Opts o, boolean system) throws Exception {
        Scope sc = scope(system);
        Store.Profile p = new Store.Profile();
        if (!o.pos.isEmpty() && o.get("name") == null) p.name = o.pos.get(0);
        apply(p, o, false);
        if (p.pass.isEmpty()) {
            String pw = readPassword(o, true);
            if (pw != null) p.pass = pw;
        }
        if (p.name.isEmpty()) p.name = p.host;
        validate(p);
        for (Store.Profile e : sc.profiles()) {
            if (e.title().equalsIgnoreCase(p.title())) throw new CliError("a profile named '" + p.title() + "' already exists - use: foxssh edit " + e.id);
        }
        boolean first = sc.profiles().isEmpty();
        sc.save(p);
        if (system && (first || sc.selectedKey().isEmpty())) sc.select(p);
        System.out.println("added '" + p.title() + "' (" + sc.label() + ", id " + p.id + ")" + (p.src.isEmpty() ? "" : " → " + p.src));
        return 0;
    }

    private static int cmdEdit(Opts o, boolean system) throws Exception {
        if (o.pos.isEmpty()) throw new CliError("usage: foxssh edit NAME [options]", EX_USAGE);
        Scope sc = scope(system);
        Store.Profile p = find(sc, o.pos.get(0));
        apply(p, o, true);
        validate(p);
        sc.save(p);
        System.out.println("updated '" + p.title() + "'");
        return 0;
    }

    private static int cmdRemove(Opts o, boolean system) throws Exception {
        if (o.pos.isEmpty()) throw new CliError("usage: foxssh remove NAME", EX_USAGE);
        Scope sc = scope(system);
        Store.Profile p = find(sc, o.pos.get(0));
        sc.delete(p);
        System.out.println("removed '" + p.title() + "'");
        return 0;
    }

    private static int cmdSelect(Opts o, boolean system) throws Exception {
        if (o.pos.isEmpty()) throw new CliError("usage: foxssh select NAME", EX_USAGE);
        Scope sc = scope(system);
        Store.Profile p = find(sc, o.pos.get(0));
        sc.select(p);
        System.out.println("default profile: " + p.title() + " (" + sc.label() + ")");
        return 0;
    }

    private static int cmdImport(Opts o, boolean system) throws Exception {
        if (o.pos.isEmpty()) throw new CliError("usage: foxssh import FILE", EX_USAGE);
        Properties pr = new Properties();
        Path f = Paths.get(o.pos.get(0));
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            pr.load(r);
        } catch (IOException e) {
            throw new CliError("cannot read " + f + ": " + e.getMessage());
        }
        String base = f.getFileName().toString().replaceAll("\\.conf$", "");
        Store.Profile p = fromProps("", pr, "");
        if (pr.getProperty("name") == null) p.name = base;
        apply(p, o, true); // پرچم‌ها روی مقادیر فایل غالب‌اند
        validate(p);
        Scope sc = scope(system);
        for (Store.Profile e : sc.profiles()) {
            if (e.title().equalsIgnoreCase(p.title())) throw new CliError("a profile named '" + p.title() + "' already exists");
        }
        boolean first = sc.profiles().isEmpty();
        sc.save(p);
        if (system && first) sc.select(p);
        System.out.println("imported '" + p.title() + "' (" + sc.label() + ", id " + p.id + ")");
        return 0;
    }

    // ───────────── تنظیمات ─────────────

    private static int cmdDns(Opts o, boolean system) throws Exception {
        Scope sc = scope(system);
        Store st = sc.settings();
        if (o.pos.isEmpty() && o.get("dns1") == null) {
            System.out.println(st.dns1() + (st.dns2().isEmpty() ? "" : " " + st.dns2()));
            return 0;
        }
        String d1 = o.get("dns1") != null ? o.get("dns1") : o.pos.get(0);
        String d2 = o.get("dns2") != null ? o.get("dns2") : o.pos.size() > 1 ? o.pos.get(1) : "";
        for (String d : new String[]{d1, d2}) {
            if (!d.isEmpty() && !d.matches("[0-9a-fA-F:.]+")) throw new CliError("invalid DNS address: " + d, EX_USAGE);
        }
        if (d1.isEmpty()) throw new CliError("usage: foxssh dns D1 [D2]", EX_USAGE);
        st.put("dns1", d1);
        st.put("dns2", d2);
        System.out.println("DNS: " + d1 + (d2.isEmpty() ? "" : " " + d2) + " (" + sc.label() + ")");
        return 0;
    }

    private static int cmdBypass(Opts o, boolean system) throws Exception {
        Scope sc = scope(system);
        Store st = sc.settings();
        String sub = o.pos.isEmpty() ? "status" : o.pos.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                System.out.println("bypass: " + (st.bypass() ? "on" : "off") + "  (" + st.manual().size() + " manual entries)");
                return 0;
            case "on": case "off":
                st.put("bypass", String.valueOf(sub.equals("on")));
                System.out.println("Iran bypass " + sub + " (" + sc.label() + ")");
                return 0;
            case "list":
                for (String s : st.manual()) System.out.println(s);
                return 0;
            case "add": case "remove": case "rm":
                if (o.pos.size() < 2) throw new CliError("usage: foxssh bypass " + sub + " IP|CIDR|DOMAIN ...", EX_USAGE);
                int rc = 0;
                for (String e : o.pos.subList(1, o.pos.size())) {
                    if (sub.equals("add")) {
                        String err = st.addManual(e);
                        if (err != null) {
                            System.err.println("foxssh: " + e + ": " + err);
                            rc = 1;
                        } else System.out.println("added " + BypassRules.normalizeEntry(e));
                    } else {
                        st.removeManual(BypassRules.normalizeEntry(e));
                        System.out.println("removed " + e);
                    }
                }
                return rc;
            default:
                throw new CliError("usage: foxssh bypass [on|off|list|add X|remove X]", EX_USAGE);
        }
    }

    private static int cmdLang(Opts o, boolean system) throws Exception {
        Scope sc = scope(system);
        if (o.pos.isEmpty()) {
            System.out.println(sc.settings().lang());
            return 0;
        }
        String l = o.pos.get(0).toLowerCase(Locale.ROOT);
        if (!l.equals("fa") && !l.equals("en")) throw new CliError("language must be fa or en", EX_USAGE);
        sc.settings().put("lang", l);
        System.out.println("language: " + l);
        return 0;
    }

    // ───────────── اتصال / قطع ─────────────

    private static int cmdConnect(Opts o, boolean system) throws Exception {
        Scope sc = scope(system);
        Store.Profile p;
        if (!o.pos.isEmpty()) {
            p = find(sc, o.pos.get(0));
            apply(p, o, true);
        } else if (o.get("host") != null) { // اتصال یک‌باره بدون ذخیره
            p = new Store.Profile();
            apply(p, o, false);
            if (p.pass.isEmpty()) {
                String pw = readPassword(o, true);
                if (pw != null) p.pass = pw;
            }
            if (p.name.isEmpty()) p.name = p.host;
        } else {
            p = defaultProfile(sc);
            apply(p, o, true);
        }
        validate(p);
        Store st = sc.settings();
        String d1 = o.get("dns1") != null ? o.get("dns1") : st.dns1();
        String d2 = o.get("dns2") != null ? o.get("dns2") : st.dns2();
        boolean bypass = o.has("no-bypass") ? false : o.has("bypass") ? true : st.bypass();
        String cfg = Store.engineConfig(p, p.user, p.pass, d1, d2, bypass, st.manual(), st.lang());
        boolean quiet = o.has("quiet");
        if (!quiet) System.out.println("Connecting to " + p.title() + " (" + p.host + ":" + p.port + ") …");
        String err = ctl("start", cfg);
        if (err != null) throw new CliError(err);
        if (o.has("no-wait")) return 0;
        return waitConnected(o, quiet);
    }

    private static int waitConnected(Opts o, boolean quiet) throws Exception {
        int timeout = o.get("timeout") == null ? 60 : Math.max(5, Store.toInt(o.get("timeout"), 60));
        long end = System.currentTimeMillis() + timeout * 1000L, started = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            Properties s = readStatus();
            String state = s.getProperty("state", "");
            if (state.equals("connected")) {
                if (!quiet) {
                    System.out.println("✓ Connected  (SOCKS 127.0.0.1:" + s.getProperty("socksPort", "?") + ")");
                    String b = s.getProperty("banner", "").trim();
                    if (!b.isEmpty()) {
                        System.out.println("\nServer message:");
                        for (String ln : b.split("\r?\n")) System.out.println("  " + ln);
                    }
                }
                return 0;
            }
            if (state.equals("stopped") || (!unitActive(MAIN_UNIT) && !unitActive(AUTO_UNIT) && System.currentTimeMillis() - started > 4000)) {
                String e = s.getProperty("error", "");
                throw new CliError("connection failed" + (e.isEmpty() ? "" : ": " + e) + "\n" + tail(logLines(), 8));
            }
            Thread.sleep(300);
        }
        throw new CliError("timed out after " + timeout + "s - still connecting; see: foxssh logs");
    }

    private static int cmdDisconnect(Opts o) throws Exception {
        String err = ctl("stop", null);
        if (err != null) throw new CliError(err);
        if (!o.has("quiet")) System.out.println("Disconnected.");
        return 0;
    }

    private static int cmdStatus(Opts o) throws Exception {
        boolean manual = unitActive(MAIN_UNIT), auto = unitActive(AUTO_UNIT);
        boolean active = manual || auto;
        Properties s = readStatus();
        if (!active) { // فایل وضعیتِ باقی‌مانده از اتصال قبلی را نشان نده (فقط آخرین خطا)
            Properties e = new Properties();
            e.setProperty("error", s.getProperty("error", ""));
            s = e;
        }
        String state = active ? s.getProperty("state", "connecting") : "disconnected";
        boolean connected = active && state.equals("connected");
        long since = 0;
        try {
            since = Long.parseLong(s.getProperty("since", "0"));
        } catch (NumberFormatException ignored) {
        }
        long up = connected && since > 0 ? (System.currentTimeMillis() - since) / 1000 : 0;
        long tx = num(s.getProperty("tx")), rx = num(s.getProperty("rx"));
        String unit = auto ? AUTO_UNIT : manual ? MAIN_UNIT : "";
        if (o.has("json")) {
            System.out.println("{\"state\":" + js(state) + ",\"connected\":" + connected
                    + ",\"profile\":" + js(s.getProperty("profile", "")) + ",\"server\":" + js(s.getProperty("server", ""))
                    + ",\"socksPort\":" + (connected ? num(s.getProperty("socksPort")) : 0)
                    + ",\"uptimeSeconds\":" + up + ",\"txBytes\":" + tx + ",\"rxBytes\":" + rx
                    + ",\"service\":" + js(unit) + ",\"error\":" + js(s.getProperty("error", "") ) + "}");
        } else {
            System.out.println("State:    " + state);
            if (active) {
                if (!s.getProperty("profile", "").isEmpty()) System.out.println("Profile:  " + s.getProperty("profile"));
                if (!s.getProperty("server", "").isEmpty()) System.out.println("Server:   " + s.getProperty("server"));
                if (connected) {
                    System.out.println("Uptime:   " + String.format("%02d:%02d:%02d", up / 3600, up / 60 % 60, up % 60));
                    System.out.println("Traffic:  ↑ " + human(tx) + "  ↓ " + human(rx));
                    System.out.println("SOCKS:    127.0.0.1:" + num(s.getProperty("socksPort")));
                }
                System.out.println("Service:  " + unit + (auto ? "  (boot service)" : ""));
            } else if (!s.getProperty("error", "").isEmpty()) {
                System.out.println("Last error: " + s.getProperty("error"));
            }
        }
        return connected ? 0 : EX_DOWN;
    }

    private static int cmdLogs(Opts o) throws Exception {
        int n = o.get("lines") == null ? 50 : Math.max(1, Store.toInt(o.get("lines"), 50));
        List<String> l = logLines();
        if (l.isEmpty() && !o.has("follow")) {
            System.out.println("(no log yet; it fills after connecting)");
            return 0;
        }
        for (String s : l.subList(Math.max(0, l.size() - n), l.size())) System.out.println(s);
        if (!o.has("follow")) return 0;
        int seen = l.size();
        while (true) {
            Thread.sleep(700);
            List<String> now = logLines();
            if (now.size() < seen) seen = 0; // لاگ با اتصال جدید خالی شده
            for (int i = seen; i < now.size(); i++) System.out.println(now.get(i));
            System.out.flush();
            seen = now.size();
        }
    }

    // ───────────── سرویس بوت ─────────────

    private static int cmdService(Opts o) throws Exception {
        String sub = o.pos.isEmpty() ? "status" : o.pos.get(0);
        switch (sub) {
            case "enable": {
                Scope sc = new SysScope();
                Store.Profile p = o.pos.size() > 1 ? find(sc, o.pos.get(1)) : defaultProfile(sc);
                validate(p);
                sc.select(p);
                sh("systemctl", "daemon-reload");
                int rc = sh("systemctl", "enable", "--now", AUTO_UNIT);
                if (rc == 0) System.out.println("Auto-connect enabled with profile '" + p.title() + "'. Check: foxssh status");
                return rc;
            }
            case "disable": return sh("systemctl", "disable", "--now", AUTO_UNIT);
            case "start": case "stop": case "restart": return sh("systemctl", sub, AUTO_UNIT);
            case "status": {
                String en = out("systemctl", "is-enabled", AUTO_UNIT), ac = out("systemctl", "is-active", AUTO_UNIT);
                System.out.println(AUTO_UNIT + ": " + (en.isEmpty() ? "not installed" : en) + ", " + (ac.isEmpty() ? "inactive" : ac));
                try {
                    System.out.println("profile: " + defaultProfile(new SysScope()).title());
                } catch (CliError e) {
                    System.out.println("profile: (" + e.getMessage() + ")");
                }
                return 0;
            }
            default:
                throw new CliError("usage: foxssh service enable [NAME] | disable | start | stop | restart | status", EX_USAGE);
        }
    }

    /** برای foxssh-prepare (ExecStartPre سرویس بوت): متن config موتور را از /etc روی stdout می‌نویسد. */
    private static int cmdEmitConfig(Opts o) throws Exception {
        Scope sc = new SysScope();
        Store.Profile p;
        try {
            p = o.pos.isEmpty() ? defaultProfile(sc) : find(sc, o.pos.get(0));
            validate(p);
        } catch (CliError e) {
            System.err.println("foxssh-auto: " + e.getMessage());
            return EX_CONFIG;  // خطای پیکربندی؛ systemd دوباره تلاش نمی‌کند
        }
        Store st = sc.settings();
        System.out.print(Store.engineConfig(p, p.user, p.pass, st.dns1(), st.dns2(), st.bypass(), st.manual(), st.lang()));
        return 0;
    }

    // ───────────── ابزارهای سیستم ─────────────

    /** اجرای foxssh-ctl (با pkexec اگر root نیستیم)؛ null = موفق، وگرنه متن خطا. */
    private static String ctl(String cmd, String stdin) {
        try {
            List<String> c = new ArrayList<>();
            if (!isRoot()) c.add("pkexec");
            c.add(CTL);
            c.add(cmd);
            ProcessBuilder pb = new ProcessBuilder(c).redirectErrorStream(true);
            Process pr = pb.start();
            try (OutputStream os = pr.getOutputStream()) {
                if (stdin != null) os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = pr.waitFor();
            if (code == 126 || code == 127) return "authentication was cancelled or denied";
            return code == 0 ? null : (out.isEmpty() ? "failed (exit code " + code + ")" : out);
        } catch (Exception e) {
            return "could not run " + CTL + ": " + e.getMessage();
        }
    }

    private static int ctlPrint(String cmd, String stdin) {
        String err = ctl(cmd, stdin);
        if (err != null) {
            System.err.println("foxssh: " + err);
            return 1;
        }
        System.out.println("done");
        return 0;
    }

    private static int sh(String... c) throws Exception {
        return new ProcessBuilder(c).inheritIO().start().waitFor();
    }

    private static String out(String... c) {
        try {
            Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return s.startsWith("Failed") || s.startsWith("Unit ") ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean unitActive(String unit) {
        try {
            return new ProcessBuilder("systemctl", "is-active", "--quiet", unit).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Properties readStatus() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(RUN.resolve("status.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
        }
        return p;
    }

    private static List<String> logLines() {
        try {
            return Files.readAllLines(RUN.resolve("engine.log"), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String tail(List<String> l, int n) {
        return String.join("\n", l.subList(Math.max(0, l.size() - n), l.size()));
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] u = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    private static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
