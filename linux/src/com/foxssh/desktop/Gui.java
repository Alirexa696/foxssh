package com.foxssh.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.ComponentOrientation;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** برنامهٔ گرافیکی Fox VPN برای لینوکس. تونل سیستم را از طریق pkexec و سرویس foxssh راه می‌اندازد. */
public final class Gui {
    private static final String CTL = "/usr/lib/foxssh/foxssh-ctl";
    private static final Path RUN = Paths.get("/run/foxssh");
    private static final int LOG_H = 210;

    // نقاط تزریق برای تست ظاهر (در حالت عادی دست نمی‌خورند)
    static BooleanSupplier activeFn = Gui::systemctlActive;
    static Supplier<Properties> statusFn = Gui::readStatusFile;
    static volatile String demoIp;

    private final Store store = new Store();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fox-poll");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService ipExec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fox-ip");
        t.setDaemon(true);
        return t;
    });

    private JFrame frame;
    private Ui.PowerButton power;
    private Ui.Pill proto;
    private JLabel status, stats, profileName, ipLabel, ipValue, error;
    private Ui.Chevron logArrow;
    private JTextArea logArea;
    private JScrollPane logScroll;
    private boolean logOpen;
    private volatile boolean busy;
    private volatile String localError = "";
    private boolean bannerShown;
    private String lastEngineError = "";
    private String lastLogText = "";

    // IP
    private volatile String ip = "…";
    private volatile int ipState = -1;
    private volatile int ipGen;
    private volatile long ipFailAt;

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> new Gui().show());
    }

    // ───────────── ظاهر ─────────────

    static void applyTheme() {
        try {
            UIManager.put("control", Ui.CARD);
            UIManager.put("info", Ui.FIELD);
            UIManager.put("nimbusBase", new Color(0x1B2845));
            UIManager.put("nimbusBlueGrey", new Color(0x22324F));
            UIManager.put("nimbusLightBackground", Ui.FIELD);
            UIManager.put("nimbusSelectionBackground", Ui.ACCENT);
            UIManager.put("nimbusSelectedText", Color.WHITE);
            UIManager.put("nimbusFocus", Ui.ACCENT);
            UIManager.put("nimbusOrange", Ui.ACCENT);
            UIManager.put("text", Ui.TEXT);
            UIManager.put("nimbusDisabledText", Ui.MUTED);
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(i.getName())) {
                    UIManager.setLookAndFeel(i.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        Font f = Ui.font(14f, Font.PLAIN);
        for (Object k : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (k.toString().endsWith(".font")) UIManager.put(k, new javax.swing.plaf.FontUIResource(f));
        }
        UIManager.put("OptionPane.background", Ui.CARD);
        UIManager.put("Panel.background", Ui.CARD);
        UIManager.put("OptionPane.messageForeground", Ui.TEXT);
        UIManager.put("Label.foreground", Ui.TEXT);
        UIManager.put("PopupMenu.background", Ui.CARD);
        UIManager.put("MenuItem.background", Ui.CARD);
        UIManager.put("MenuItem.foreground", Ui.TEXT);
    }

    JPanel buildMain() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);

        // ── نوار بالا: همبرگری (چپ) + لوگو و عنوان (وسط)
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Ui.BAR);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        Ui.Hamburger burger = new Ui.Hamburger();
        burger.addActionListener(e -> showMenu(burger));
        bar.add(burger, BorderLayout.WEST);
        JPanel title = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));
        title.setOpaque(false);
        title.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        URL logo = Gui.class.getResource("/logo.png");
        if (logo != null) {
            title.add(new JLabel(new ImageIcon(new ImageIcon(logo).getImage().getScaledInstance(34, 34, Image.SCALE_SMOOTH))));
        }
        title.add(Ui.label("Fox VPN", Ui.TEXT, 21f, Font.BOLD));
        bar.add(title, BorderLayout.CENTER);
        bar.add(Box.createRigidArea(new Dimension(48, 44)), BorderLayout.EAST);
        root.add(bar, BorderLayout.NORTH);

        // ── بدنه
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Ui.BG);
        body.setBorder(BorderFactory.createEmptyBorder(14, 22, 10, 22));

        power = new Ui.PowerButton();
        power.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        power.addActionListener(e -> onPower());
        body.add(power);

        status = Ui.label("قطع", Ui.MUTED, 22f, Font.BOLD);
        status.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        body.add(status);
        stats = Ui.label(" ", Ui.MUTED, 12f, Font.PLAIN);
        stats.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        body.add(Box.createVerticalStrut(2));
        body.add(stats);
        body.add(Box.createVerticalStrut(16));

        // کارت پروفایل: پروتکل + نام کانفیگ + فلش
        Ui.Card profileCard = new Ui.Card();
        profileCard.setLayout(new BorderLayout(10, 0));
        proto = new Ui.Pill("SSH");
        profileName = Ui.label("—", Ui.TEXT, 16f, Font.BOLD);
        JLabel chev = Ui.label("‹", Ui.MUTED, 22f, Font.PLAIN);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(chev);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(profileName);
        right.add(proto);
        profileCard.add(right, BorderLayout.EAST);
        profileCard.add(left, BorderLayout.WEST);
        profileCard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        profileCard.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { showProfileMenu(profileCard); }
        });
        fixCard(profileCard, 58);
        body.add(profileCard);
        body.add(Box.createVerticalStrut(10));

        // کارت IP
        Ui.Card ipCard = new Ui.Card();
        ipCard.setLayout(new BorderLayout());
        ipLabel = Ui.label("آی‌پی شما", Ui.MUTED, 12f, Font.PLAIN);
        ipValue = Ui.label("…", Ui.TEXT, 18f, Font.BOLD);
        ipValue.setFont(Ui.font(18f, Font.BOLD));
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        ipLabel.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        ipValue.setAlignmentX(JComponent.RIGHT_ALIGNMENT);
        col.add(ipLabel);
        col.add(ipValue);
        ipCard.add(col, BorderLayout.EAST);
        fixCard(ipCard, 70);
        body.add(ipCard);

        error = Ui.label(" ", Ui.BAD, 12f, Font.PLAIN);
        error.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        error.setHorizontalAlignment(JLabel.CENTER);
        body.add(Box.createVerticalStrut(10));
        body.add(error);
        body.add(Box.createVerticalGlue());
        root.add(body, BorderLayout.CENTER);

        // ── کشوی لاگ
        JPanel drawer = new JPanel(new BorderLayout());
        drawer.setBackground(Ui.BAR);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        head.add(Ui.label("لاگ‌ها", Ui.TEXT, 14f, Font.BOLD), BorderLayout.EAST);
        logArrow = new Ui.Chevron();
        head.add(logArrow, BorderLayout.WEST);
        head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        head.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleLog(); }
        });
        drawer.add(head, BorderLayout.NORTH);
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(Ui.font(12f, Font.PLAIN));
        logArea.setBackground(Ui.BG);
        logArea.setForeground(Ui.TEXT);
        logArea.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        logArea.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        logScroll.setPreferredSize(new Dimension(100, LOG_H));
        logScroll.setVisible(false);
        drawer.add(logScroll, BorderLayout.CENTER);
        root.add(drawer, BorderLayout.SOUTH);

        root.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        ipValue.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        return root;
    }

    private static void fixCard(JComponent c, int h) {
        c.setPreferredSize(new Dimension(300, h));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setMinimumSize(new Dimension(100, h));
        c.setAlignmentX(JComponent.CENTER_ALIGNMENT);
    }

    void show() {
        applyTheme();
        frame = new JFrame("Fox VPN");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        URL logo = Gui.class.getResource("/logo.png");
        if (logo != null) frame.setIconImage(new ImageIcon(logo).getImage());
        frame.setContentPane(buildMain());
        frame.setMinimumSize(new Dimension(360, 540));
        frame.setSize(380, 600);
        frame.setLocationRelativeTo(null);
        reloadProfiles();
        frame.setVisible(true);
        poller.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    JFrame frame() { return frame; }

    JDialog dialogForTest(String which) {
        JDialog d;
        if (which.equals("profiles")) {
            d = new ProfilesDialog(frame, store, () -> { });
        } else if (which.equals("edit")) {
            d = new ProfileEditDialog(new JDialog(frame), store, store.selected());
        } else {
            d = new SettingsDialog(frame, store, (c, r) -> { });
        }
        d.setModal(false);
        return d;
    }

    void openLogForTest() {
        if (!logOpen) toggleLog();
    }

    // ───────────── منوها ─────────────

    private void showMenu(JComponent anchor) {
        JPopupMenu m = new JPopupMenu();
        m.setBorder(BorderFactory.createLineBorder(new Color(0x2B3A5C)));
        m.add(item("کانفیگ‌ها", () -> new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true)));
        m.add(item("تنظیمات", () -> new SettingsDialog(frame, store, Gui::ctlAsync).setVisible(true)));
        m.add(item(logOpen ? "بستن لاگ‌ها" : "لاگ‌ها", this::toggleLog));
        m.show(anchor, 0, anchor.getHeight());
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Ui.font(14f, Font.PLAIN));
        i.setBackground(Ui.CARD);
        i.setForeground(Ui.TEXT);
        i.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        i.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        i.setHorizontalAlignment(JMenuItem.RIGHT);
        i.addActionListener(e -> r.run());
        return i;
    }

    private void showProfileMenu(JComponent anchor) {
        if (isActiveNow()) return;
        JPopupMenu m = new JPopupMenu();
        Store.Profile sel = store.selected();
        for (final Store.Profile p : store.profiles()) {
            boolean on = sel != null && sel.id.equals(p.id);
            m.add(item((on ? "✓  " : "") + p.title() + "   (" + p.host + ")", () -> {
                store.setSelected(p.id);
                reloadProfiles();
            }));
        }
        m.addSeparator();
        m.add(item("مدیریت کانفیگ‌ها…", () -> new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true)));
        m.show(anchor, 0, anchor.getHeight());
    }

    private void toggleLog() {
        logOpen = !logOpen;
        logScroll.setVisible(logOpen);
        logArrow.setUp(!logOpen);
        if (frame != null) {
            frame.setSize(frame.getWidth(), frame.getHeight() + (logOpen ? LOG_H : -LOG_H));
            frame.revalidate();
        }
        refreshLog();
    }

    private void refreshLog() {
        if (!logOpen) return;
        String t;
        try {
            t = new String(Files.readAllBytes(RUN.resolve("engine.log")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            t = "(هنوز لاگی وجود ندارد؛ بعد از اتصال پر می‌شود)";
        }
        if (!localError.isEmpty()) t += "\nGUI: " + localError;
        if (!t.equals(lastLogText)) {
            lastLogText = t;
            logArea.setText(t);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    private void reloadProfiles() {
        Store.Profile p = store.selected();
        profileName.setText(p == null ? "افزودن کانفیگ" : p.title());
    }

    // ───────────── اتصال / قطع ─────────────

    private void onPower() {
        if (busy) return;
        if (isActiveNow()) {
            bannerShown = false;
            runCtl("stop", null);
            return;
        }
        Store.Profile p = store.selected();
        if (p == null) {
            JOptionPane.showMessageDialog(frame, "اول یک کانفیگ اضافه کنید.");
            new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true);
            return;
        }
        if (p.user.isEmpty() || p.pass.isEmpty() || p.host.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "نام کاربری، رمز و آدرس سرور این کانفیگ کامل نیست.");
            new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true);
            return;
        }
        bannerShown = false;
        localError = "";
        runCtl("start", store.engineConfig(p, p.user, p.pass));
    }

    private volatile boolean activeCache;

    private boolean isActiveNow() {
        return activeCache;
    }

    private void runCtl(final String cmd, final String stdin) {
        busy = true;
        render(null);
        Thread t = new Thread(() -> {
            String err = ctl(cmd, stdin);
            busy = false;
            if (err != null) localError = err;
        }, "fox-ctl");
        t.setDaemon(true);
        t.start();
    }

    /** اجرای foxssh-ctl با pkexec؛ null = موفق. */
    private static String ctl(String cmd, String stdin) {
        try {
            Process pr = new ProcessBuilder("pkexec", CTL, cmd).redirectErrorStream(true).start();
            try (OutputStream o = pr.getOutputStream()) {
                if (stdin != null) o.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int code = pr.waitFor();
            if (code == 126 || code == 127) return "احراز هویت لغو شد یا مجوز داده نشد";
            return code == 0 ? null : (out.isEmpty() ? "خطا (کد " + code + ")" : out);
        } catch (Exception e) {
            return "اجرای pkexec ممکن نشد: " + e.getMessage();
        }
    }

    static void ctlAsync(String cmd, java.util.function.Consumer<String> done) {
        Thread t = new Thread(() -> {
            String r = ctl(cmd, null);
            SwingUtilities.invokeLater(() -> done.accept(r));
        });
        t.setDaemon(true);
        t.start();
    }

    // ───────────── وضعیت ─────────────

    private static boolean systemctlActive() {
        try {
            Process p = new ProcessBuilder("systemctl", "is-active", "--quiet", "foxssh.service").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Properties readStatusFile() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(RUN.resolve("status.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
        }
        return p;
    }

    private void poll() {
        try {
            final boolean active = activeFn.getAsBoolean();
            final Properties all = statusFn.get();
            activeCache = active;
            boolean connected = active && "connected".equals(all.getProperty("state", ""));
            trackIp(connected ? 1 : 0);
            SwingUtilities.invokeLater(() -> {
                render(active ? all : errorOnly(all));
                refreshLog();
            });
        } catch (Exception ignored) {
        }
    }

    private Properties errorOnly(Properties s) {
        lastEngineError = s == null ? "" : s.getProperty("error", "");
        return null;
    }

    private void render(Properties st) {
        boolean active = st != null;
        String state = active ? st.getProperty("state", "connecting") : "stopped";
        if (busy && !active) state = "connecting";
        if (active && "connected".equals(state)) lastEngineError = "";
        boolean connected = active && "connected".equals(state);
        boolean working = busy || (active && !connected);

        power.setState(connected ? Ui.PowerButton.ON : working ? Ui.PowerButton.BUSY : Ui.PowerButton.OFF);
        power.setEnabled(!busy);
        status.setText(connected ? "متصل" : "reconnecting".equals(state) ? "اتصال مجدد…" : working ? "در حال اتصال…" : "قطع");
        status.setForeground(connected ? Ui.OK : working ? Ui.ACCENT : Ui.MUTED);
        proto.setFill(connected ? new Color(0x1E8E4E) : new Color(0x2B3A5C));

        if (connected) {
            long since = parse(st.getProperty("since"));
            long sec = Math.max(0, (System.currentTimeMillis() - since) / 1000);
            stats.setText(String.format(Locale.US, "%02d:%02d:%02d   •   ↑ %s   ↓ %s",
                    sec / 3600, (sec / 60) % 60, sec % 60, fmt(parse(st.getProperty("tx"))), fmt(parse(st.getProperty("rx")))));
        } else {
            stats.setText(" ");
        }
        Store.Profile p = store.selected();
        profileName.setText(p == null ? "افزودن کانفیگ" : p.title());
        ipLabel.setText(connected ? "آی‌پی سرور (VPN)" : "آی‌پی شما");
        ipValue.setText(ip);
        ipValue.setForeground(connected ? Ui.OK : Ui.TEXT);

        String err = !localError.isEmpty() ? localError : lastEngineError;
        error.setText(!active && !busy && !err.isEmpty() ? "<html><div style='text-align:center;width:280px'>" + esc(err) + "</div></html>" : " ");

        String banner = active ? clean(st.getProperty("banner", "")) : "";
        if (connected && !banner.isEmpty() && !bannerShown) {
            bannerShown = true; // فقط یک بار در هر اتصال
            showBanner(banner);
        }
    }

    private void showBanner(String text) {
        JTextArea a = new JTextArea(text, 10, 32);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBackground(Ui.FIELD);
        a.setForeground(Ui.TEXT);
        a.setFont(Ui.font(14f, Font.PLAIN));
        a.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        a.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        JOptionPane.showMessageDialog(frame, new JScrollPane(a), "پیام سرور", JOptionPane.PLAIN_MESSAGE);
    }

    // ───────────── IP ─────────────

    private static final String[] IP_SOURCES = {
            "https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com", "https://ipinfo.io/ip"};

    private void trackIp(int state) {
        if (demoIp != null) {
            ip = demoIp;
            return;
        }
        long now = System.currentTimeMillis();
        if (state != ipState) {
            ipState = state;
            ip = "…";
            scheduleIp(state == 1 ? 2000 : 300);
        } else if ("نامشخص".equals(ip) && now - ipFailAt > 8000) {
            ipFailAt = now;
            scheduleIp(0);
        }
    }

    private void scheduleIp(long delayMs) {
        final int gen = ++ipGen;
        ipExec.schedule(() -> {
            String r = fetchIp();
            if (gen != ipGen) return;
            ip = r == null ? "نامشخص" : r;
            ipFailAt = System.currentTimeMillis();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static String fetchIp() {
        for (String src : IP_SOURCES) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(src).openConnection(Proxy.NO_PROXY);
                h.setConnectTimeout(5000);
                h.setReadTimeout(5000);
                h.setRequestProperty("User-Agent", "curl/8");
                try {
                    if (h.getResponseCode() != 200) continue;
                    String s = new String(h.getInputStream().readNBytes(100), StandardCharsets.US_ASCII).trim();
                    if (s.matches("[0-9a-fA-F:.]{7,45}")) return s;
                } finally {
                    h.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String clean(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").replace("\r", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").trim();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        return String.format(Locale.US, "%.2f GB", v / 1024);
    }

    // ───────────── دیالوگ کانفیگ‌ها ─────────────

    private static final class ProfilesDialog extends JDialog {
        private final Store store;
        private final Runnable onChange;
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);

        ProfilesDialog(JFrame owner, Store store, Runnable onChange) {
            super(owner, "کانفیگ‌ها", true);
            this.store = store;
            this.onChange = onChange;
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBackground(Ui.BG);
            p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            list.setFont(Ui.font(15f, Font.PLAIN));
            list.setFixedCellHeight(36);
            p.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel b = new JPanel(new GridLayout(1, 3, 8, 0));
            b.setOpaque(false);
            Ui.RoundButton add = Ui.button("+ افزودن", Ui.ACCENT);
            Ui.RoundButton edit = Ui.button("ویرایش", Ui.DARK_BTN);
            Ui.RoundButton pick = Ui.button("انتخاب", Ui.DARK_BTN);
            b.add(add);
            b.add(edit);
            b.add(pick);
            p.add(b, BorderLayout.SOUTH);
            setContentPane(p);
            applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            setSize(420, 380);
            setLocationRelativeTo(owner);
            add.addActionListener(e -> edit(null));
            edit.addActionListener(e -> {
                int i = list.getSelectedIndex();
                if (i >= 0) edit(store.profiles().get(i));
            });
            pick.addActionListener(e -> {
                int i = list.getSelectedIndex();
                if (i >= 0) {
                    store.setSelected(store.profiles().get(i).id);
                    onChange.run();
                    dispose();
                }
            });
            reload();
        }

        private void reload() {
            model.clear();
            for (Store.Profile f : store.profiles()) model.addElement(f.title() + "   (" + f.host + ":" + f.port + ")");
        }

        private void edit(Store.Profile existing) {
            new ProfileEditDialog(this, store, existing).setVisible(true);
            reload();
            onChange.run();
        }
    }

    private static final class ProfileEditDialog extends JDialog {
        ProfileEditDialog(JDialog owner, Store store, Store.Profile existing) {
            super(owner, existing == null ? "کانفیگ جدید" : "ویرایش کانفیگ", true);
            final Store.Profile f = existing != null ? existing : new Store.Profile();
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(Ui.BG);
            p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
            final JTextField name = Ui.field(), host = Ui.field(), port = Ui.field(), user = Ui.field(), udpgw = Ui.field();
            final JPasswordField pass = Ui.password();
            name.setText(f.name);
            host.setText(f.host);
            port.setText(String.valueOf(f.port));
            user.setText(f.user);
            pass.setText(f.pass);
            udpgw.setText(String.valueOf(f.udpgw));
            name.setHorizontalAlignment(JTextField.RIGHT);
            addRow(p, "نام کانفیگ", name);
            addRow(p, "آدرس سرور", host);
            addRow(p, "پورت SSH", port);
            addRow(p, "نام کاربری", user);
            addRow(p, "رمز عبور", pass);
            addRow(p, "پورت badvpn روی سرور (۰ = غیرفعال)", udpgw);
            JPanel b = new JPanel(new GridLayout(1, existing == null ? 1 : 2, 8, 0));
            b.setOpaque(false);
            Ui.RoundButton save = Ui.button("ذخیره", Ui.ACCENT);
            b.add(save);
            if (existing != null) {
                Ui.RoundButton del = Ui.button("حذف", Ui.DARK_BTN);
                del.setForeground(Ui.BAD);
                del.addActionListener(e -> {
                    store.deleteProfile(f.id);
                    dispose();
                });
                b.add(del);
            }
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            p.add(Box.createVerticalStrut(12));
            p.add(b);
            save.addActionListener(e -> {
                if (host.getText().trim().isEmpty() || user.getText().trim().isEmpty() || pass.getPassword().length == 0) {
                    JOptionPane.showMessageDialog(this, "آدرس سرور، نام کاربری و رمز را وارد کنید.");
                    return;
                }
                f.name = name.getText().trim();
                f.host = host.getText().trim();
                f.port = toInt(port.getText(), 22);
                f.user = user.getText().trim();
                f.pass = new String(pass.getPassword());
                f.udpgw = toInt(udpgw.getText(), 7300);
                store.saveProfile(f);
                dispose();
            });
            setContentPane(p);
            applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            for (JComponent c : new JComponent[]{host, port, user, pass, udpgw}) c.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            pack();
            setSize(400, getHeight() + 10);
            setLocationRelativeTo(owner);
        }

        private static void addRow(JPanel p, String label, JComponent c) {
            JPanel row = new JPanel(new BorderLayout(0, 3));
            row.setOpaque(false);
            row.add(Ui.label(label, Ui.MUTED, 12f, Font.PLAIN), BorderLayout.NORTH);
            row.add(c, BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
            p.add(row);
            p.add(Box.createVerticalStrut(6));
        }
    }

    private static int toInt(String s, int d) {
        try {
            int v = Integer.parseInt(s.trim());
            return v >= 0 && v < 65536 ? v : d;
        } catch (Exception e) {
            return d;
        }
    }

    // ───────────── دیالوگ تنظیمات ─────────────

    private interface CtlRunner {
        void run(String cmd, java.util.function.Consumer<String> done);
    }

    private static final class SettingsDialog extends JDialog {
        private final DefaultListModel<String> model = new DefaultListModel<>();

        SettingsDialog(JFrame owner, Store store, CtlRunner ctl) {
            super(owner, "تنظیمات", true);
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(Ui.BG);
            p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

            final JTextField d1 = Ui.field(), d2 = Ui.field();
            d1.setText(store.dns1());
            d2.setText(store.dns2());
            d1.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            d2.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            ProfileEditDialog.addRow(p, "دی‌ان‌اس اول", d1);
            ProfileEditDialog.addRow(p, "دی‌ان‌اس دوم (اختیاری)", d2);
            final JCheckBox bypass = new JCheckBox("ترافیک ایران مستقیم برود", store.bypass());
            bypass.setOpaque(false);
            bypass.setForeground(Ui.TEXT);
            p.add(bypass);
            p.add(Box.createVerticalStrut(10));

            p.add(Ui.label("موارد دستی مستقیم (IP / CIDR / دامنه):", Ui.MUTED, 12f, Font.PLAIN));
            for (String s : store.manual()) model.addElement(s);
            final JList<String> list = new JList<>(model);
            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            JScrollPane sp = new JScrollPane(list);
            sp.setPreferredSize(new Dimension(300, 110));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
            p.add(sp);
            JPanel add = new JPanel(new BorderLayout(6, 0));
            add.setOpaque(false);
            final JTextField entry = Ui.field();
            entry.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            Ui.RoundButton addB = Ui.button("افزودن", Ui.ACCENT);
            Ui.RoundButton delB = Ui.button("حذف", Ui.DARK_BTN);
            addB.setPreferredSize(new Dimension(92, 40));
            delB.setPreferredSize(new Dimension(76, 40));
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            btns.setOpaque(false);
            btns.add(addB);
            btns.add(delB);
            add.add(entry, BorderLayout.CENTER);
            add.add(btns, BorderLayout.WEST);
            add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            p.add(Box.createVerticalStrut(6));
            p.add(add);
            addB.addActionListener(e -> {
                String err = store.addManual(entry.getText());
                if (err != null) {
                    JOptionPane.showMessageDialog(this, err);
                    return;
                }
                entry.setText("");
                model.clear();
                for (String s : store.manual()) model.addElement(s);
            });
            delB.addActionListener(e -> {
                String s = list.getSelectedValue();
                if (s != null) {
                    store.removeManual(s);
                    model.removeElement(s);
                }
            });

            p.add(Box.createVerticalStrut(12));
            Ui.RoundButton upd = Ui.button("به‌روزرسانی لیست ایران", Ui.DARK_BTN);
            Ui.RoundButton forget = Ui.button("پاک‌کردن کلیدهای ذخیره‌شدهٔ سرورها", Ui.DARK_BTN);
            Ui.RoundButton save = Ui.button("ذخیره", Ui.ACCENT);
            for (Ui.RoundButton b : new Ui.RoundButton[]{upd, forget, save}) {
                b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
                b.setAlignmentX(JComponent.CENTER_ALIGNMENT);
                p.add(b);
                p.add(Box.createVerticalStrut(8));
            }
            upd.addActionListener(e -> ctl.run("update-list", r ->
                    JOptionPane.showMessageDialog(this, r == null ? "لیست به‌روز شد (از اتصال بعدی اعمال می‌شود)" : r)));
            forget.addActionListener(e -> ctl.run("forget-keys", r ->
                    JOptionPane.showMessageDialog(this, r == null ? "پاک شد" : r)));
            save.addActionListener(e -> {
                if (!ok(d1.getText()) || (!d2.getText().trim().isEmpty() && !ok(d2.getText()))) {
                    JOptionPane.showMessageDialog(this, "DNS نامعتبر است (مثلاً 1.1.1.1)");
                    return;
                }
                store.setNet(d1.getText().trim(), d2.getText().trim(), bypass.isSelected());
                dispose();
            });

            JScrollPane outer = new JScrollPane(p, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            outer.setBorder(null);
            setContentPane(outer);
            applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            setSize(440, 600);
            setLocationRelativeTo(owner);
        }

        private static boolean ok(String s) {
            return com.foxssh.vpn.core.CidrSet.parseIp(s.trim()) >= 0;
        }
    }
}
