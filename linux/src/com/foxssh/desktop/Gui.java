package com.foxssh.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JRadioButton;
import javax.swing.JSeparator;
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
import java.awt.Dialog;
import java.awt.Window;
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
    private static final int LOG_H = 200;
    private static final int WIN_W = 500, WIN_H = 428;

    // نقاط تزریق برای تست ظاهر (در حالت عادی دست نمی‌خورند)
    static BooleanSupplier activeFn = Gui::systemctlActive;
    static Supplier<Properties> statusFn = Gui::readStatusFile;
    static volatile String demoIp;
    static volatile String demoCc;

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
    private Ui.PortPill proto;
    private Ui.CheckBadge badge;
    private Ui.GlowLine glowLine;
    private JLabel status, stats, profileName, ipLabel, ipValue, country, error;
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
    private volatile String ipCc = "";
    private JLabel flag;
    private static final java.util.Map<String, ImageIcon> FLAGS = new java.util.HashMap<>();
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
        applyFonts();
        UIManager.put("OptionPane.background", Ui.CARD);
        UIManager.put("Panel.background", Ui.CARD);
        UIManager.put("OptionPane.messageForeground", Ui.TEXT);
        UIManager.put("Label.foreground", Ui.TEXT);
        UIManager.put("PopupMenu.background", Ui.CARD);
        UIManager.put("MenuItem.background", Ui.CARD);
        UIManager.put("MenuItem.foreground", Ui.TEXT);
    }

    /** فونت پیش‌فرض بر اساس زبان: فارسی = وزیرمتن، انگلیسی = Poppins. */
    static void applyFonts() {
        Font f = L.isFa() ? Ui.font(14f, Font.PLAIN) : Ui.latin(14f, Font.PLAIN);
        for (Object k : java.util.Collections.list(UIManager.getDefaults().keys())) {
            if (k.toString().endsWith(".font")) UIManager.put(k, new javax.swing.plaf.FontUIResource(f));
        }
    }

    static void orient(java.awt.Component c) {
        c.applyComponentOrientation(L.isFa() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
    }

    private static final Color LINE = new Color(0x2B3A5C);

    /** ریشهٔ پنجره: پس‌زمینهٔ گرادیانی با گوشه‌های گرد و لبهٔ آبی درخشان. */
    private static final class RootPanel extends JPanel {
        static final int ARC = 34;

        RootPanel() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            Ui.antialias(g2);
            g2.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x11213F), 0, getHeight(), new Color(0x09132A)));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            g2.dispose();
        }

        @Override
        public void paint(java.awt.Graphics g) {
            int w = getWidth(), h = getHeight();
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            Ui.antialias(g2);
            g2.clip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, ARC, ARC));
            super.paint(g2);
            g2.dispose();
            java.awt.Graphics2D b = (java.awt.Graphics2D) g.create();
            Ui.antialias(b);
            b.setColor(Ui.alpha(Ui.GLOW, 60));
            b.setStroke(new java.awt.BasicStroke(5f));
            b.draw(new java.awt.geom.RoundRectangle2D.Float(2.5f, 2.5f, w - 5, h - 5, ARC - 3, ARC - 3));
            b.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x5B84C4), 0, h, new Color(0x34568F)));
            b.setStroke(new java.awt.BasicStroke(2f));
            b.draw(new java.awt.geom.RoundRectangle2D.Float(1, 1, w - 2, h - 2, ARC - 1, ARC - 1));
            b.dispose();
        }
    }

    private static JPanel transparent(java.awt.LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    JPanel buildMain() {
        JPanel root = new RootPanel();

        // ── هدر: لوگو و نام وسط، کنترل پنجره بالا-راست، همبرگر زیر آن
        JPanel brand = transparent(new FlowLayout(FlowLayout.CENTER, 6, 0));
        URL logo = Gui.class.getResource("/logo.png");
        if (logo != null) {
            Image img = new ImageIcon(logo).getImage().getScaledInstance(62, 62, Image.SCALE_SMOOTH);
            brand.add(new JLabel(new ImageIcon(img)));
        }
        brand.add(new Ui.Wordmark(34f));

        JPanel controls = transparent(new FlowLayout(FlowLayout.CENTER, 0, 0));
        Ui.WinGlyph minimize = new Ui.WinGlyph(Ui.WinGlyph.MIN);
        minimize.addActionListener(e -> frame.setState(JFrame.ICONIFIED));
        Ui.WinGlyph close = new Ui.WinGlyph(Ui.WinGlyph.CLOSE);
        close.addActionListener(e -> frame.dispose());
        controls.add(minimize);
        controls.add(new Ui.VDivider(18));
        controls.add(close);
        controls.setPreferredSize(new Dimension(72, 28));

        Ui.Hamburger burger = new Ui.Hamburger();
        burger.addActionListener(e -> showMenu(burger));
        Ui.Header bar = new Ui.Header(brand, controls, burger);
        bar.setPreferredSize(new Dimension(WIN_W, 80));

        // امکان جابه‌جایی پنجره با گرفتن هدر یا پس‌زمینه
        java.awt.event.MouseAdapter dragWindow = new java.awt.event.MouseAdapter() {
            private java.awt.Point offset;
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                offset = e.getLocationOnScreen();
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (offset == null || frame == null) return;
                java.awt.Point now = e.getLocationOnScreen();
                frame.setLocation(frame.getX() + now.x - offset.x, frame.getY() + now.y - offset.y);
                offset = now;
            }
        };
        for (JComponent c : new JComponent[]{bar, brand}) {
            c.addMouseListener(dragWindow);
            c.addMouseMotionListener(dragWindow);
        }
        root.add(bar, BorderLayout.NORTH);

        // ── بدنه
        MapPanel body = new MapPanel();
        body.addMouseListener(dragWindow);
        body.addMouseMotionListener(dragWindow);

        proto = new Ui.PortPill();

        JPanel col = transparent(null);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        power = new Ui.PowerButton();
        power.setPreferredSize(new Dimension(118, 118));
        power.setMaximumSize(new Dimension(118, 118));
        power.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        power.addActionListener(e -> onPower());
        col.add(power);

        JPanel statusRow = transparent(new FlowLayout(FlowLayout.CENTER, 10, 0));
        status = Ui.label(L.t("disconnected"), Ui.MUTED, 22f, Font.BOLD);
        badge = new Ui.CheckBadge();
        badge.setVisible(false);
        statusRow.add(status);
        statusRow.add(badge);
        statusRow.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        col.add(statusRow);

        glowLine = new Ui.GlowLine();
        glowLine.setMaximumSize(new Dimension(320, 8));
        glowLine.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        col.add(glowLine);

        stats = Ui.label(" ", Ui.SOFT, 12f, Font.PLAIN);
        stats.setFont(new Font("SansSerif", Font.PLAIN, 12)); // فلش‌های ↑↓ در فونت‌های برنامه نیست
        stats.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        col.add(stats);
        col.add(Box.createVerticalStrut(4));

        // کارت IP: مانیتور + آی‌پی | پرچم + کشور
        Ui.GlowCard ipCard = new Ui.GlowCard(26);
        ipCard.setLayout(new java.awt.GridBagLayout());
        JPanel left = transparent(new FlowLayout(FlowLayout.CENTER, 10, 0));
        left.add(new Ui.Icon(Ui.Icon.MONITOR, Ui.SOFT, 32, 30));
        JPanel texts = transparent(null);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        ipLabel = Ui.label(L.t("your_ip"), Ui.SOFT, 11f, Font.PLAIN);
        ipValue = Ui.label("…", Ui.TEXT, 17f, Font.BOLD);
        ipLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        ipValue.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        texts.add(ipLabel);
        texts.add(ipValue);
        left.add(texts);
        flag = new JLabel();
        country = Ui.label(" ", Ui.TEXT, 14f, Font.PLAIN);
        JPanel right = transparent(new FlowLayout(FlowLayout.CENTER, 10, 0));
        right.add(flag);
        right.add(country);
        java.awt.GridBagConstraints g0 = new java.awt.GridBagConstraints();
        g0.gridx = 0; g0.weightx = 0.62; g0.fill = java.awt.GridBagConstraints.NONE;
        ipCard.add(left, g0);
        java.awt.GridBagConstraints g1 = new java.awt.GridBagConstraints();
        g1.gridx = 1; g1.weightx = 0; g1.fill = java.awt.GridBagConstraints.VERTICAL; g1.insets = new java.awt.Insets(10, 0, 10, 0);
        ipCard.add(new Ui.VDivider(26), g1);
        java.awt.GridBagConstraints g2c = new java.awt.GridBagConstraints();
        g2c.gridx = 2; g2c.weightx = 0.38; g2c.fill = java.awt.GridBagConstraints.NONE;
        ipCard.add(right, g2c);
        ipCard.setPreferredSize(new Dimension(380, 56));
        ipCard.setMaximumSize(new Dimension(380, 56));
        ipCard.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        col.add(ipCard);
        col.add(Box.createVerticalStrut(6));

        // کارت کانفیگ (لیست کشویی)
        Ui.GlowCard profileCard = new Ui.GlowCard(24);
        profileCard.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 14));
        profileCard.setLayout(new BorderLayout(14, 0));
        profileCard.add(new Ui.Icon(Ui.Icon.DOC, Ui.SOFT, 24, 28), BorderLayout.WEST);
        profileName = Ui.label("—", Ui.TEXT, 14f, Font.PLAIN);
        profileCard.add(profileName, BorderLayout.CENTER);
        Ui.Chevron down = new Ui.Chevron(Ui.TEXT);
        down.setDir(Ui.Chevron.DOWN);
        profileCard.add(down, BorderLayout.EAST);
        profileCard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        profileCard.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { showProfileMenu(profileCard); }
        });
        profileCard.setPreferredSize(new Dimension(420, 44));
        profileCard.setMaximumSize(new Dimension(420, 44));
        profileCard.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        col.add(profileCard);

        error = Ui.label(" ", Ui.BAD, 12f, Font.PLAIN);
        error.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        col.add(error);

        body.content(col, proto);
        root.add(body, BorderLayout.CENTER);

        // ── کشوی لاگ (نوار پایین «View Logs»)
        JPanel drawer = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                Ui.antialias(g2);
                g2.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x13244A), 0, getHeight(), new Color(0x0B1630)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Ui.alpha(Ui.GLOW, 55));
                g2.setStroke(new java.awt.BasicStroke(5f));
                g2.drawLine(0, 1, getWidth(), 1);
                g2.setPaint(new java.awt.LinearGradientPaint(0, 0, getWidth(), 0, new float[]{0f, 0.5f, 1f},
                        new Color[]{new Color(0x3E68B0), new Color(0x5B8AD0), new Color(0x3E68B0)}));
                g2.setStroke(new java.awt.BasicStroke(1.8f));
                g2.drawLine(0, 1, getWidth(), 1);
                g2.dispose();
            }
        };
        drawer.setOpaque(false);
        JPanel head = transparent(new BorderLayout());
        head.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 20));
        head.setPreferredSize(new Dimension(WIN_W, 44));
        JPanel logTitle = transparent(new FlowLayout(FlowLayout.LEFT, 14, 10));
        logTitle.add(new Ui.Icon(Ui.Icon.DOC, Ui.SOFT, 22, 24));
        logTitle.add(Ui.label(L.t("logs"), Ui.SOFT, 14f, Font.PLAIN));
        head.add(logTitle, BorderLayout.WEST);
        logArrow = new Ui.Chevron(Ui.TEXT);
        logArrow.setDir(logOpen ? Ui.Chevron.DOWN : Ui.Chevron.RIGHT);
        head.add(logArrow, BorderLayout.EAST);
        head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter toggle = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { toggleLog(); }
        };
        head.addMouseListener(toggle);
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
        logScroll.setVisible(logOpen);
        drawer.add(logScroll, BorderLayout.CENTER);
        root.add(drawer, BorderLayout.SOUTH);

        root.applyComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        lastLogText = "";
        return root;
    }

    void show() {
        applyTheme();
        frame = new JFrame("Fox VPN");
        frame.setUndecorated(true);
        frame.setBackground(Ui.BG0);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        URL logo = Gui.class.getResource("/logo.png");
        if (logo != null) frame.setIconImage(new ImageIcon(logo).getImage());
        L.setFa(!"en".equals(store.lang()));
        applyFonts();
        frame.setContentPane(buildMain());
        frame.setResizable(false);
        fit();
        frame.setLocationRelativeTo(null);
        reloadProfiles();
        frame.setVisible(true);
        SwingUtilities.invokeLater(() -> System.err.println("[fox] power=" + power.getBounds() + " showing=" + power.isShowing()
                + " parent=" + power.getParent().getBounds()));
        poller.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    /** اندازه را برای «محتوا» تنظیم می‌کند (نه کل پنجره) تا عنوان‌بار پنجره جا را از دکمه‌ها نگیرد. */
    private void fit() {
        frame.getContentPane().setPreferredSize(new Dimension(WIN_W, WIN_H + (logOpen ? LOG_H : 0)));
        frame.pack();
        try { // گوشه‌های گرد پنجره (بدون نوار عنوان سیستم)
            frame.setShape(new java.awt.geom.RoundRectangle2D.Float(0, 0, frame.getWidth(), frame.getHeight(),
                    RootPanel.ARC, RootPanel.ARC));
        } catch (Exception ignored) {
        }
    }

    JFrame frame() { return frame; }

    /** تغییر زبان: ذخیره و ساخت دوبارهٔ رابط. */
    void switchLang(String lang) {
        store.setLang(lang);
        L.setFa(!"en".equals(lang));
        applyFonts();
        rebuild();
    }

    private void rebuild() {
        frame.setContentPane(buildMain());
        fit();
        frame.repaint();
        reloadProfiles();
        lastEngineError = "";
        refreshLog();
    }

    JDialog dialogForTest(String which) {
        JDialog d;
        if (which.equals("profiles")) {
            d = new ProfilesDialog(frame, store, () -> { });
        } else if (which.equals("edit")) {
            d = new ProfileEditDialog(frame, store, store.selected());
        } else if (which.equals("tunnels")) {
            Store.Profile p = store.selected();
            p.tunnels = "L|8080|example.com:80;R|2222|localhost:22;D|1080|";
            d = new TunnelsDialog(frame, store, p, false);
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

    JPopupMenu buildMenu() {
        Ui.PopupMenu m = new Ui.PopupMenu();
        m.add(row(L.t("menu_configs"), Ui.Icon.DOC, () -> new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true)));
        m.add(row(L.t("menu_tunnels"), Ui.Icon.TUNNELS, this::openTunnels));
        m.add(row(L.t("menu_settings"), Ui.Icon.GEAR, this::openSettings));
        m.add(row(logOpen ? L.t("menu_logs_close") : L.t("menu_logs"), Ui.Icon.FOLDER, this::toggleLog));
        m.addSeparator();
        m.add(row(L.t("menu_about"), Ui.Icon.INFO, () -> JOptionPane.showMessageDialog(frame, L.t("about_text"), L.t("menu_about"), JOptionPane.PLAIN_MESSAGE)));
        m.add(row(L.t("menu_exit"), Ui.Icon.EXIT, () -> System.exit(0)));
        return m;
    }

    private void showMenu(JComponent anchor) {
        JPopupMenu m = buildMenu();
        int w = m.getPreferredSize().width;
        m.show(anchor, anchor.getWidth() - w + 4, anchor.getHeight() + 2);
    }

    private static Ui.MenuRow row(String text, int icon, Runnable r) {
        Ui.MenuRow i = new Ui.MenuRow(text, icon, false, false);
        i.addActionListener(e -> r.run());
        return i;
    }

    private void openTunnels() {
        Store.Profile p = store.selected();
        if (p == null) {
            JOptionPane.showMessageDialog(frame, L.t("t_none"));
            return;
        }
        new TunnelsDialog(frame, store, p, true).setVisible(true);
    }

    private void openSettings() {
        SettingsDialog d;
        do {
            d = new SettingsDialog(frame, store, Gui::ctlAsync);
            d.setVisible(true);
            if (d.newLang != null) switchLang(d.newLang);
        } while (d.newLang != null);
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(Ui.pick(text, 14f, Font.PLAIN));
        i.setBackground(Ui.CARD);
        i.setForeground(Ui.TEXT);
        i.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 24));
        orient(i);
        i.addActionListener(e -> r.run());
        return i;
    }

    JPopupMenu buildProfileMenu() {
        Ui.PopupMenu m = new Ui.PopupMenu();
        Store.Profile sel = store.selected();
        for (final Store.Profile p : store.profiles()) {
            boolean on = sel != null && sel.id.equals(p.id);
            Ui.MenuRow r = new Ui.MenuRow(p.title() + " (" + p.host + ")", -1, true, on);
            r.addActionListener(e -> {
                store.setSelected(p.id);
                reloadProfiles();
            });
            m.add(r);
        }
        m.addSeparator();
        Ui.MenuRow manage = new Ui.MenuRow(L.t("manage_configs"), -1, false, false);
        manage.addActionListener(e -> new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true));
        m.add(manage);
        return m;
    }

    private void showProfileMenu(JComponent anchor) {
        if (isActiveNow()) return;
        JPopupMenu m = buildProfileMenu();
        int w = Math.max(m.getPreferredSize().width, 280);
        m.show(anchor, (anchor.getWidth() - w) / 2, anchor.getHeight() + 4);
    }

    private void toggleLog() {
        logOpen = !logOpen;
        logScroll.setVisible(logOpen);
        logArrow.setDir(logOpen ? Ui.Chevron.DOWN : Ui.Chevron.RIGHT);
        if (frame != null) {
            fit();
        }
        refreshLog();
    }

    private void refreshLog() {
        if (!logOpen) return;
        String t;
        try {
            t = new String(Files.readAllBytes(RUN.resolve("engine.log")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            t = L.t("log_empty");
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
        Ui.setText(profileName, p == null ? L.t("add_config") : p.title());
        proto.setPort(p == null ? 22 : p.port);
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
            JOptionPane.showMessageDialog(frame, L.t("add_first"));
            new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true);
            return;
        }
        if (p.user.isEmpty() || p.pass.isEmpty() || p.host.isEmpty()) {
            JOptionPane.showMessageDialog(frame, L.t("incomplete"));
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
            if (code == 126 || code == 127) return L.t("auth_cancel");
            return code == 0 ? null : (out.isEmpty() ? L.t("err_code") + code + ")" : out);
        } catch (Exception e) {
            return L.t("pkexec_fail") + e.getMessage();
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
        Ui.setText(status, connected ? L.t("connected") : "reconnecting".equals(state) ? L.t("reconnecting")
                : working ? L.t("connecting") : L.t("disconnected"));
        status.setForeground(connected ? Ui.GREEN : working ? Ui.CYAN : Ui.SOFT);
        badge.setVisible(connected);
        glowLine.setColor(connected ? Ui.GREEN : working ? Ui.CYAN : Ui.GLOW);

        if (connected) {
            long since = parse(st.getProperty("since"));
            long sec = Math.max(0, (System.currentTimeMillis() - since) / 1000);
            stats.setText(String.format(Locale.US, "%02d:%02d:%02d   ↑ %s   ↓ %s",
                    sec / 3600, (sec / 60) % 60, sec % 60, fmt(parse(st.getProperty("tx"))), fmt(parse(st.getProperty("rx")))));
        } else {
            stats.setText(" ");
        }
        reloadProfiles();
        Ui.setText(ipLabel, connected ? L.t("vpn_ip") : L.t("your_ip"));
        Ui.setText(ipValue, "unknown".equals(ip) ? L.t("unknown") : ip);
        flag.setIcon(flagIcon(ipCc));
        ipValue.setForeground(Ui.TEXT);
        Ui.setText(country, countryName(ipCc));

        String err = !localError.isEmpty() ? localError : lastEngineError;
        error.setText(!active && !busy && !err.isEmpty() ? "<html><div style='width:400px'>" + esc(err) + "</div></html>" : " ");

        String banner = active ? clean(st.getProperty("banner", "")) : "";
        if (connected && !banner.isEmpty() && !bannerShown) {
            bannerShown = true; // فقط یک بار در هر اتصال
            showBanner(banner);
        }
    }

    /** نام کشور از کد دوحرفی (فارسی در حالت فارسی، وگرنه انگلیسی). */
    private static String countryName(String cc) {
        if (cc == null || cc.length() != 2) return " ";
        Locale loc = new Locale("", cc.toUpperCase(Locale.ROOT));
        String n = L.isFa() ? loc.getDisplayCountry(new Locale("fa")) : "";
        if (n.isEmpty() || n.equalsIgnoreCase(cc)) n = loc.getDisplayCountry(Locale.ENGLISH);
        return n.isEmpty() ? cc.toUpperCase(Locale.ROOT) : n;
    }

    private static synchronized ImageIcon flagIcon(String cc) {
        if (cc == null || cc.length() != 2) return null;
        cc = cc.toLowerCase(Locale.ROOT);
        ImageIcon i = FLAGS.get(cc);
        if (i == null && !FLAGS.containsKey(cc)) {
            URL u = Gui.class.getResource("/flags/" + cc + ".png");
            if (u != null) i = new ImageIcon(new ImageIcon(u).getImage().getScaledInstance(34, 25, Image.SCALE_SMOOTH));
            FLAGS.put(cc, i);
        }
        return i;
    }

    /** پس‌زمینهٔ بدنه: نقشهٔ نقطه‌ای آبی جهان + هالهٔ نرم؛ محتوا (ستون مرکزی و برچسب SSH) روی آن چیده می‌شود. */
    private static final class MapPanel extends JPanel {
        private final Image map;
        private JComponent col, pill;

        MapPanel() {
            super(null);
            setOpaque(false);
            URL u = Gui.class.getResource("/worldmap.png");
            map = u == null ? null : new ImageIcon(u).getImage();
        }

        void content(JComponent col, JComponent pill) {
            this.col = col;
            this.pill = pill;
            add(pill);
            add(col);
        }

        @Override
        public void doLayout() {
            if (col == null) return;
            int w = getWidth(), h = getHeight();
            Dimension c = col.getPreferredSize();
            int cw = Math.min(w, Math.max(c.width, 420));
            col.setBounds((w - cw) / 2, 2, cw, Math.min(h - 2, c.height));
            Dimension p = pill.getPreferredSize();
            pill.setBounds(14, 6, p.width, p.height);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(WIN_W, col == null ? 300 : col.getPreferredSize().height + 6);
        }

        private java.awt.image.BufferedImage baked;
        private int bakedW;

        /** نقشه یک‌بار با رنگ آبی و شفافیت داخل پیکسل‌ها پخته می‌شود تا موقع نقاشی ترکیب (composite) لازم نباشد. */
        private void bake(int w) {
            int h = Math.max(1, w * map.getHeight(null) / map.getWidth(null));
            java.awt.image.BufferedImage t = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = t.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(map, 0, 0, w, h, null);
            g2.dispose();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int px = t.getRGB(x, y);
                    int al = (int) (((px >>> 24) & 0xFF) * 0.55f);
                    t.setRGB(x, y, (al << 24) | 0x5577B5);
                }
            }
            baked = t;
            bakedW = w;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            int w = getWidth(), h = getHeight();
            if (w < 10) return;
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            Ui.antialias(g2);
            float r = w * 0.55f;
            g2.setPaint(new java.awt.RadialGradientPaint(w / 2f, h * 0.42f, r, new float[]{0f, 1f},
                    new Color[]{Ui.alpha(Ui.GLOW, 46), Ui.alpha(Ui.GLOW, 0)}));
            g2.fillRect(0, 0, w, h);
            g2.dispose();
            if (map == null) return;
            if (baked == null || bakedW != w) bake(w);
            g.drawImage(baked, 0, Math.max(0, (h - baked.getHeight()) / 2 - 6), null);
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
        orient(a);
        JOptionPane.showMessageDialog(frame, new JScrollPane(a), L.t("banner_title"), JOptionPane.PLAIN_MESSAGE);
    }

    // ───────────── IP ─────────────

    private static final String[] IP_SOURCES = {
            "https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com", "https://ipinfo.io/ip"};

    private void trackIp(int state) {
        if (demoIp != null) {
            ip = demoIp;
            ipCc = demoCc == null ? "" : demoCc;
            return;
        }
        long now = System.currentTimeMillis();
        if (state != ipState) {
            ipState = state;
            ip = "…";
            ipCc = "";
            scheduleIp(state == 1 ? 2000 : 300);
        } else if ("unknown".equals(ip) && now - ipFailAt > 8000) {
            ipFailAt = now;
            scheduleIp(0);
        }
    }

    private void scheduleIp(long delayMs) {
        final int gen = ++ipGen;
        ipExec.schedule(() -> {
            String r = fetchIp();
            if (gen != ipGen) return;
            ip = r == null ? "unknown" : r;
            ipFailAt = System.currentTimeMillis();
            String cc = r == null ? null : fetchCountry(r);
            if (gen == ipGen) ipCc = cc == null ? "" : cc;
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

    private static String fetchCountry(String ip) {
        String[] urls = {"https://ipinfo.io/" + ip + "/country", "https://api.country.is/" + ip, "https://ipapi.co/" + ip + "/country/"};
        for (String src : urls) {
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(src).openConnection(Proxy.NO_PROXY);
                h.setConnectTimeout(5000);
                h.setReadTimeout(5000);
                h.setRequestProperty("User-Agent", "curl/8");
                try {
                    if (h.getResponseCode() != 200) continue;
                    String s = new String(h.getInputStream().readNBytes(300), StandardCharsets.UTF_8).trim();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z]{2})$|\"country\"\\s*:\\s*\"([A-Za-z]{2})\"").matcher(s);
                    if (m.find()) return (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase(Locale.ROOT);
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

    // ───────────── دیالوگ‌ها ─────────────

    private static JPanel vbox() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.BG);
        p.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        return p;
    }

    private static void ltr(JComponent... cs) {
        for (JComponent c : cs) c.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
    }

    private static void addRow(JPanel p, String label, JComponent c) {
        JPanel row = new JPanel(new BorderLayout(0, 3));
        row.setOpaque(false);
        row.add(Ui.label(label, Ui.MUTED, 12f, Font.PLAIN), BorderLayout.NORTH);
        row.add(c, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        row.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        p.add(row);
        p.add(Box.createVerticalStrut(6));
    }

    private static final class ProfilesDialog extends JDialog {
        private final Store store;
        private final Runnable onChange;
        private final DefaultListModel<String> model = new DefaultListModel<>();
        private final JList<String> list = new JList<>(model);

        ProfilesDialog(JFrame owner, Store store, Runnable onChange) {
            super(owner, L.t("configs"), true);
            this.store = store;
            this.onChange = onChange;
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBackground(Ui.BG);
            p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            list.setFont(Ui.pick("a", 14f, Font.PLAIN));
            list.setFixedCellHeight(36);
            p.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel b = new JPanel(new GridLayout(1, 3, 8, 0));
            b.setOpaque(false);
            Ui.RoundButton add = Ui.button(L.t("add"), Ui.ACCENT);
            Ui.RoundButton edit = Ui.button(L.t("edit"), Ui.DARK_BTN);
            Ui.RoundButton pick = Ui.button(L.t("select"), Ui.DARK_BTN);
            b.add(add);
            b.add(edit);
            b.add(pick);
            p.add(b, BorderLayout.SOUTH);
            setContentPane(p);
            orient(this);
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
        ProfileEditDialog(Window owner, Store store, Store.Profile existing) {
            super(owner, existing == null ? L.t("new_config") : L.t("edit_config"), Dialog.ModalityType.APPLICATION_MODAL);
            final Store.Profile f = existing != null ? existing : new Store.Profile();
            JPanel p = vbox();
            final JTextField name = Ui.field(), host = Ui.field(), port = Ui.field(), user = Ui.field(), udpgw = Ui.field();
            final JPasswordField pass = Ui.password();
            name.setText(f.name);
            host.setText(f.host);
            port.setText(String.valueOf(f.port));
            user.setText(f.user);
            pass.setText(f.pass);
            udpgw.setText(String.valueOf(f.udpgw));
            addRow(p, L.t("f_name"), name);
            addRow(p, L.t("f_host"), host);
            addRow(p, L.t("f_port"), port);
            addRow(p, L.t("f_user"), user);
            addRow(p, L.t("f_pass"), pass);
            addRow(p, L.t("f_udpgw"), udpgw);
            Ui.RoundButton tun = Ui.button(L.t("tunnels_btn"), Ui.DARK_BTN);
            tun.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            tun.setAlignmentX(JComponent.CENTER_ALIGNMENT);
            tun.addActionListener(e -> new TunnelsDialog(this, store, f, false).setVisible(true));
            p.add(Box.createVerticalStrut(4));
            p.add(tun);
            JPanel b = new JPanel(new GridLayout(1, existing == null ? 1 : 2, 8, 0));
            b.setOpaque(false);
            Ui.RoundButton save = Ui.button(L.t("save"), Ui.ACCENT);
            b.add(save);
            if (existing != null) {
                Ui.RoundButton del = Ui.button(L.t("delete"), Ui.DARK_BTN);
                del.setForeground(Ui.BAD);
                del.addActionListener(e -> {
                    store.deleteProfile(f.id);
                    dispose();
                });
                b.add(del);
            }
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            p.add(Box.createVerticalStrut(10));
            p.add(b);
            save.addActionListener(e -> {
                if (host.getText().trim().isEmpty() || user.getText().trim().isEmpty() || pass.getPassword().length == 0) {
                    JOptionPane.showMessageDialog(this, L.t("fill_fields"));
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
            orient(this);
            ltr(host, port, user, pass, udpgw);
            pack();
            setSize(400, getHeight() + 10);
            setLocationRelativeTo(owner);
        }
    }

    /** فوروارد پورت شبیه PuTTY: Local / Remote / Dynamic. */
    private static final class TunnelsDialog extends JDialog {
        private final DefaultListModel<Tunnel> model = new DefaultListModel<>();

        TunnelsDialog(Window owner, Store store, Store.Profile f, boolean persist) {
            super(owner, L.t("t_title"), Dialog.ModalityType.APPLICATION_MODAL);
            for (Tunnel t : Tunnel.parseList(f.tunnels)) model.addElement(t);
            JPanel p = vbox();

            final JCheckBox anyL = check(L.t("t_any_local"), f.anyLocal);
            final JCheckBox anyR = check(L.t("t_any_remote"), f.anyRemote);
            p.add(left(anyL));
            p.add(left(anyR));
            p.add(Box.createVerticalStrut(10));

            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.add(Ui.label(L.t("t_forwarded"), Ui.MUTED, 13f, Font.PLAIN), BorderLayout.CENTER);
            Ui.RoundButton rem = Ui.button(L.t("t_remove"), Ui.DARK_BTN);
            rem.setPreferredSize(new Dimension(96, 34));
            head.add(rem, BorderLayout.EAST);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            p.add(head);
            p.add(Box.createVerticalStrut(6));

            final JList<Tunnel> list = new JList<>(model);
            list.setBackground(Ui.FIELD);
            list.setForeground(Ui.TEXT);
            list.setFont(Ui.latin(14f, Font.PLAIN));
            list.setFixedCellHeight(28);
            list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
                @Override
                public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                    Tunnel t = (Tunnel) v;
                    String txt = String.valueOf(t.type) + t.srcPort + (t.type == 'D' ? "   (SOCKS)" : "    " + t.dest);
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, txt, i, sel, foc);
                    c.setFont(Ui.latin(14f, Font.PLAIN));
                    c.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                    return c;
                }
            });
            ltr(list);
            JScrollPane sp = new JScrollPane(list);
            sp.setPreferredSize(new Dimension(300, 120));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
            sp.setAlignmentX(JComponent.CENTER_ALIGNMENT);
            p.add(sp);
            p.add(Box.createVerticalStrut(12));
            p.add(left(Ui.label(L.t("t_add_new"), Ui.MUTED, 13f, Font.PLAIN)));
            p.add(Box.createVerticalStrut(6));

            final JTextField src = Ui.field(), dst = Ui.field();
            src.setPreferredSize(new Dimension(100, 38));
            Ui.RoundButton add = Ui.button(L.t("t_add"), Ui.ACCENT);
            add.setPreferredSize(new Dimension(96, 38));
            JPanel r1 = new JPanel(new BorderLayout(8, 0));
            r1.setOpaque(false);
            JPanel srcBox = new JPanel(new BorderLayout(8, 0));
            srcBox.setOpaque(false);
            srcBox.add(Ui.label(L.t("t_src"), Ui.TEXT, 13f, Font.PLAIN), BorderLayout.WEST);
            srcBox.add(src, BorderLayout.CENTER);
            r1.add(srcBox, BorderLayout.CENTER);
            r1.add(add, BorderLayout.EAST);
            r1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            p.add(r1);
            p.add(Box.createVerticalStrut(6));
            JPanel r2 = new JPanel(new BorderLayout(8, 0));
            r2.setOpaque(false);
            r2.add(Ui.label(L.t("t_dst"), Ui.TEXT, 13f, Font.PLAIN), BorderLayout.WEST);
            r2.add(dst, BorderLayout.CENTER);
            r2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            p.add(r2);
            p.add(Box.createVerticalStrut(8));

            final JRadioButton rl = radio(L.t("t_local"), true), rr = radio(L.t("t_remote"), false), rd = radio(L.t("t_dynamic"), false);
            ButtonGroup g = new ButtonGroup();
            g.add(rl);
            g.add(rr);
            g.add(rd);
            JPanel r3 = new JPanel(new GridLayout(1, 3, 6, 0));
            r3.setOpaque(false);
            r3.add(rl);
            r3.add(rr);
            r3.add(rd);
            r3.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            p.add(r3);
            java.awt.event.ActionListener typeCh = e -> {
                dst.setEnabled(!rd.isSelected());
                if (rd.isSelected()) dst.setText("");
            };
            rl.addActionListener(typeCh);
            rr.addActionListener(typeCh);
            rd.addActionListener(typeCh);

            p.add(Box.createVerticalStrut(10));
            p.add(left(Ui.label(L.t("t_note"), Ui.MUTED, 12f, Font.PLAIN)));
            p.add(Box.createVerticalStrut(10));
            Ui.RoundButton save = Ui.button(L.t("t_save"), Ui.ACCENT);
            save.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            save.setAlignmentX(JComponent.CENTER_ALIGNMENT);
            p.add(save);

            add.addActionListener(e -> {
                int port;
                try {
                    port = Integer.parseInt(src.getText().trim());
                } catch (NumberFormatException ex) {
                    port = -1;
                }
                char type = rl.isSelected() ? 'L' : rr.isSelected() ? 'R' : 'D';
                Tunnel t = Tunnel.make(type, port, dst.getText().trim());
                if (t == null) {
                    JOptionPane.showMessageDialog(this, L.t("t_bad"));
                    return;
                }
                model.addElement(t);
                src.setText("");
                dst.setText("");
            });
            rem.addActionListener(e -> {
                int i = list.getSelectedIndex();
                if (i >= 0) model.remove(i);
            });
            save.addActionListener(e -> {
                java.util.List<Tunnel> l = new java.util.ArrayList<>();
                for (int i = 0; i < model.size(); i++) l.add(model.get(i));
                f.tunnels = Tunnel.encodeList(l);
                f.anyLocal = anyL.isSelected();
                f.anyRemote = anyR.isSelected();
                if (persist) store.saveProfile(f);
                dispose();
            });

            setContentPane(p);
            orient(this);
            ltr(src, dst);
            pack();
            setSize(Math.max(getWidth(), 470), getHeight() + 6);
            setLocationRelativeTo(owner);
        }

        private static JCheckBox check(String t, boolean v) {
            JCheckBox c = new JCheckBox(t, v);
            c.setOpaque(false);
            c.setForeground(Ui.TEXT);
            c.setFont(Ui.pick(t, 13f, Font.PLAIN));
            return c;
        }

        private static JRadioButton radio(String t, boolean v) {
            JRadioButton c = new JRadioButton(t, v);
            c.setOpaque(false);
            c.setForeground(Ui.TEXT);
            c.setFont(Ui.latin(13f, Font.PLAIN));
            return c;
        }

        private static JComponent left(JComponent c) {
            JPanel w = new JPanel(new BorderLayout());
            w.setOpaque(false);
            w.add(c, BorderLayout.CENTER);
            w.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            w.setAlignmentX(JComponent.CENTER_ALIGNMENT);
            return w;
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

    private interface CtlRunner {
        void run(String cmd, java.util.function.Consumer<String> done);
    }

    private static final class SettingsDialog extends JDialog {
        private final DefaultListModel<String> model = new DefaultListModel<>();
        String newLang;

        SettingsDialog(JFrame owner, Store store, CtlRunner ctl) {
            super(owner, L.t("settings"), true);
            JPanel p = vbox();

            // زبان
            p.add(Ui.label(L.t("lang_note"), Ui.MUTED, 12f, Font.PLAIN));
            p.add(Box.createVerticalStrut(4));
            JPanel lg = new JPanel(new GridLayout(1, 2, 8, 0));
            lg.setOpaque(false);
            Ui.RoundButton fa = Ui.button("فارسی", L.isFa() ? Ui.ACCENT : Ui.DARK_BTN);
            Ui.RoundButton en = Ui.button("English", L.isFa() ? Ui.DARK_BTN : Ui.ACCENT);
            lg.add(fa);
            lg.add(en);
            lg.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            p.add(lg);
            p.add(Box.createVerticalStrut(12));
            fa.addActionListener(e -> {
                if (!L.isFa()) { newLang = "fa"; dispose(); }
            });
            en.addActionListener(e -> {
                if (L.isFa()) { newLang = "en"; dispose(); }
            });

            final JTextField d1 = Ui.field(), d2 = Ui.field();
            d1.setText(store.dns1());
            d2.setText(store.dns2());
            addRow(p, L.t("dns1"), d1);
            addRow(p, L.t("dns2"), d2);
            final JCheckBox bypass = new JCheckBox(L.t("bypass"), store.bypass());
            bypass.setOpaque(false);
            bypass.setForeground(Ui.TEXT);
            bypass.setFont(Ui.pick(L.t("bypass"), 13f, Font.PLAIN));
            bypass.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            JPanel bw = new JPanel(new BorderLayout());
            bw.setOpaque(false);
            bw.add(bypass, BorderLayout.CENTER);
            bw.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            p.add(bw);
            p.add(Box.createVerticalStrut(10));

            JPanel ml = new JPanel(new BorderLayout());
            ml.setOpaque(false);
            ml.add(Ui.label(L.t("manual"), Ui.MUTED, 12f, Font.PLAIN), BorderLayout.CENTER);
            ml.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            p.add(ml);
            for (String s : store.manual()) model.addElement(s);
            final JList<String> list = new JList<>(model);
            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            list.setFont(Ui.latin(13f, Font.PLAIN));
            JScrollPane sp = new JScrollPane(list);
            sp.setPreferredSize(new Dimension(300, 100));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
            p.add(sp);
            JPanel add = new JPanel(new BorderLayout(6, 0));
            add.setOpaque(false);
            final JTextField entry = Ui.field();
            Ui.RoundButton addB = Ui.button(L.t("add_short"), Ui.ACCENT);
            Ui.RoundButton delB = Ui.button(L.t("del_short"), Ui.DARK_BTN);
            addB.setPreferredSize(new Dimension(92, 40));
            delB.setPreferredSize(new Dimension(80, 40));
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            btns.setOpaque(false);
            btns.add(addB);
            btns.add(delB);
            add.add(entry, BorderLayout.CENTER);
            add.add(btns, BorderLayout.EAST);
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
            Ui.RoundButton upd = Ui.button(L.t("upd_list"), Ui.DARK_BTN);
            Ui.RoundButton forget = Ui.button(L.t("forget_keys"), Ui.DARK_BTN);
            Ui.RoundButton save = Ui.button(L.t("save"), Ui.ACCENT);
            for (Ui.RoundButton b : new Ui.RoundButton[]{upd, forget, save}) {
                b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
                b.setAlignmentX(JComponent.CENTER_ALIGNMENT);
                p.add(b);
                p.add(Box.createVerticalStrut(8));
            }
            upd.addActionListener(e -> ctl.run("update-list", r ->
                    JOptionPane.showMessageDialog(this, r == null ? L.t("list_updated") : r)));
            forget.addActionListener(e -> ctl.run("forget-keys", r ->
                    JOptionPane.showMessageDialog(this, r == null ? L.t("cleared") : r)));
            save.addActionListener(e -> {
                if (!ok(d1.getText()) || (!d2.getText().trim().isEmpty() && !ok(d2.getText()))) {
                    JOptionPane.showMessageDialog(this, L.t("bad_dns"));
                    return;
                }
                store.setNet(d1.getText().trim(), d2.getText().trim(), bypass.isSelected());
                dispose();
            });

            JScrollPane outer = new JScrollPane(p, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            outer.setBorder(null);
            setContentPane(outer);
            orient(this);
            ltr(d1, d2, entry);
            setSize(460, 640);
            setLocationRelativeTo(owner);
        }

        private static boolean ok(String s) {
            return com.foxssh.vpn.core.CidrSet.parseIp(s.trim()) >= 0;
        }
    }
}
