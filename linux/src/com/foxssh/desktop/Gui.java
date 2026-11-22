package com.foxssh.desktop;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
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

/** برنامهٔ گرافیکی Fox SSH برای لینوکس. تونل سیستم را از طریق pkexec و سرویس foxssh راه می‌اندازد. */
public final class Gui {
    private static final String CTL = "/usr/lib/foxssh/foxssh-ctl";
    private static final Path RUN = Paths.get("/run/foxssh");

    private final Store store = new Store();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fox-poll");
        t.setDaemon(true);
        return t;
    });

    private JFrame frame;
    private Ui.RoundButton connect, bannerBtn;
    private JComboBox<String> combo;
    private JTextField user;
    private JPasswordField pass;
    private JLabel status, stats, error;
    private volatile boolean busy;        // در حال اجرای pkexec
    private volatile String localError = "";
    private boolean bannerShown;
    private boolean wasActive;
    private String lastBanner = "";

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> new Gui().show());
    }

    // ───────────── پنجرهٔ اصلی ─────────────

    private void show() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        UIManager.put("OptionPane.background", Ui.CARD);
        UIManager.put("Panel.background", Ui.CARD);
        UIManager.put("OptionPane.messageForeground", Ui.TEXT);
        UIManager.put("Label.foreground", Ui.TEXT);

        frame = new JFrame("Fox SSH");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        URL logo = Gui.class.getResource("/logo.png");
        if (logo != null) frame.setIconImage(new ImageIcon(logo).getImage());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);

        // نوار بالا
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Ui.BAR);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        JPanel title = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        title.setOpaque(false);
        if (logo != null) {
            title.add(new JLabel(new ImageIcon(new ImageIcon(logo).getImage().getScaledInstance(34, 34, Image.SCALE_SMOOTH))));
        }
        title.add(Ui.label("Fox SSH", Ui.TEXT, 20f, Font.BOLD));
        bar.add(title, BorderLayout.EAST);
        JPanel acts = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        acts.setOpaque(false);
        Ui.RoundButton bProfiles = Ui.button("کانفیگ‌ها", Ui.DARK_BTN);
        Ui.RoundButton bSettings = Ui.button("تنظیمات", Ui.DARK_BTN);
        Ui.RoundButton bLog = Ui.button("لاگ", Ui.DARK_BTN);
        for (Ui.RoundButton b : new Ui.RoundButton[]{bProfiles, bSettings, bLog}) {
            b.setPreferredSize(new Dimension(92, 34));
            acts.add(b);
        }
        bar.add(acts, BorderLayout.WEST);
        root.add(bar, BorderLayout.NORTH);

        // بدنه
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Ui.BG);
        body.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        connect = new Ui.RoundButton("اتصال", Ui.ACCENT, 64);
        connect.setFont(Ui.FONT.deriveFont(Font.BOLD, 20f));
        fixHeight(connect, 64);
        body.add(connect);
        body.add(Box.createVerticalStrut(16));

        combo = new JComboBox<>();
        combo.setFont(Ui.FONT.deriveFont(15f));
        combo.setBackground(Ui.FIELD);
        combo.setForeground(Ui.TEXT);
        fixHeight(combo, 42);
        body.add(labeled("کانفیگ", combo));
        user = Ui.field();
        pass = Ui.password();
        body.add(Box.createVerticalStrut(10));
        body.add(labeled("نام کاربری", user));
        body.add(Box.createVerticalStrut(10));
        body.add(labeled("رمز عبور", pass));
        JCheckBox show = new JCheckBox("نمایش رمز");
        show.setOpaque(false);
        show.setForeground(Ui.MUTED);
        show.addActionListener(e -> pass.setEchoChar(show.isSelected() ? (char) 0 : '•'));
        JPanel showRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        showRow.setOpaque(false);
        showRow.add(show);
        fixHeight(showRow, 30);
        body.add(showRow);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Ui.CARD);
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        status = Ui.label(" ", Ui.MUTED, 20f, Font.BOLD);
        stats = Ui.label(" ", Ui.MUTED, 13f, Font.PLAIN);
        error = Ui.label(" ", Ui.BAD, 13f, Font.PLAIN);
        for (JLabel l : new JLabel[]{status, stats, error}) {
            l.setAlignmentX(JComponent.CENTER_ALIGNMENT);
            l.setHorizontalAlignment(JLabel.CENTER);
            card.add(l);
            card.add(Box.createVerticalStrut(4));
        }
        body.add(Box.createVerticalStrut(8));
        body.add(card);

        bannerBtn = Ui.button("نمایش بنر سرور", Ui.DARK_BTN);
        fixHeight(bannerBtn, 42);
        bannerBtn.setVisible(false);
        body.add(Box.createVerticalStrut(12));
        body.add(bannerBtn);
        root.add(body, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        user.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        pass.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        frame.setMinimumSize(new Dimension(420, 640));
        frame.setSize(440, 700);
        frame.setLocationRelativeTo(null);

        combo.addActionListener(e -> {
            int i = combo.getSelectedIndex();
            List<Store.Profile> l = store.profiles();
            if (i >= 0 && i < l.size() && !updating) {
                store.setSelected(l.get(i).id);
                fillLogin();
            }
        });
        connect.addActionListener(e -> onConnect());
        bannerBtn.addActionListener(e -> showBanner(lastBanner));
        bProfiles.addActionListener(e -> new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true));
        bSettings.addActionListener(e -> new SettingsDialog(frame, store, Gui::ctlAsync).setVisible(true));
        bLog.addActionListener(e -> showLog());

        reloadProfiles();
        frame.setVisible(true);
        poller.scheduleWithFixedDelay(this::poll, 0, 1, TimeUnit.SECONDS);
    }

    private static void fixHeight(JComponent c, int h) {
        c.setPreferredSize(new Dimension(200, h));
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        c.setMinimumSize(new Dimension(50, h));
        c.setAlignmentX(JComponent.CENTER_ALIGNMENT);
    }

    private static JPanel labeled(String text, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.add(Ui.label(text, Ui.MUTED, 12f, Font.PLAIN), BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        p.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 74));
        return p;
    }

    private boolean updating;

    private void reloadProfiles() {
        updating = true;
        combo.removeAllItems();
        List<Store.Profile> l = store.profiles();
        Store.Profile sel = store.selected();
        int idx = -1;
        for (int i = 0; i < l.size(); i++) {
            combo.addItem(l.get(i).title() + "   —   " + l.get(i).host + ":" + l.get(i).port);
            if (sel != null && sel.id.equals(l.get(i).id)) idx = i;
        }
        if (idx >= 0) combo.setSelectedIndex(idx);
        updating = false;
        fillLogin();
    }

    private void fillLogin() {
        Store.Profile p = store.selected();
        user.setText(p == null ? "" : p.user);
        pass.setText(p == null ? "" : p.pass);
    }

    // ───────────── اتصال / قطع ─────────────

    private void onConnect() {
        if (busy) return;
        if (isActive()) {
            runCtl("stop", null);
            return;
        }
        Store.Profile p = store.selected();
        if (p == null) {
            JOptionPane.showMessageDialog(frame, "اول یک کانفیگ اضافه کنید.");
            new ProfilesDialog(frame, store, this::reloadProfiles).setVisible(true);
            return;
        }
        p.user = user.getText().trim();
        p.pass = new String(pass.getPassword());
        if (p.user.isEmpty() || p.pass.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "نام کاربری و رمز را وارد کنید.");
            return;
        }
        store.saveProfile(p);
        bannerShown = false;
        localError = "";
        runCtl("start", store.engineConfig(p, p.user, p.pass));
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

    private boolean isActive() {
        try {
            Process p = new ProcessBuilder("systemctl", "is-active", "--quiet", "foxssh.service").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Properties readStatus() {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(RUN.resolve("status.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
        }
        return p;
    }

    private void poll() {
        try {
            boolean active = isActive();
            final Properties all = readStatus();
            wasActive = active;
            final boolean a = active;
            SwingUtilities.invokeLater(() -> render(a ? all : errorOnly(all)));
        } catch (Exception ignored) {
        }
    }

    private Properties errorOnly(Properties s) {
        lastEngineError = s == null ? "" : s.getProperty("error", "");
        return null;
    }

    private String lastEngineError = "";

    private void render(Properties st) {
        boolean active = st != null;
        String state = active ? st.getProperty("state", "connecting") : "stopped";
        if (busy && !active) state = "connecting";
        if (active && "connected".equals(state)) lastEngineError = "";
        boolean connected = active && "connected".equals(state);
        boolean working = busy || (active && !connected);

        status.setText(connected ? "متصل" : "reconnecting".equals(state) ? "اتصال مجدد…"
                : working ? "در حال اتصال…" : "قطع");
        status.setForeground(connected ? Ui.OK : working ? Ui.ACCENT : Ui.MUTED);
        connect.setText(connected || (active && !busy) ? "قطع اتصال" : working ? "در حال اتصال…" : "اتصال");
        connect.setEnabled(!busy);
        user.setEnabled(!active && !busy);
        pass.setEnabled(!active && !busy);
        combo.setEnabled(!active && !busy);

        if (connected) {
            long since = parse(st.getProperty("since"));
            long sec = Math.max(0, (System.currentTimeMillis() - since) / 1000);
            stats.setText(String.format(Locale.US, "مدت اتصال: %02d:%02d:%02d     ترافیک تونل: ↑ %s   ↓ %s",
                    sec / 3600, (sec / 60) % 60, sec % 60, fmt(parse(st.getProperty("tx"))), fmt(parse(st.getProperty("rx")))));
        } else {
            stats.setText(" ");
        }
        String err = !localError.isEmpty() ? localError : lastEngineError;
        error.setText(!active && !busy && !err.isEmpty() ? "<html><div style='text-align:center'>" + esc(err) + "</div></html>" : " ");

        String banner = active ? clean(st.getProperty("banner", "")) : "";
        lastBanner = banner;
        bannerBtn.setVisible(connected && !banner.isEmpty());
        if (connected && !banner.isEmpty() && !bannerShown) {
            bannerShown = true;
            showBanner(banner);
        }
    }

    private void showBanner(String text) {
        if (text.isEmpty()) return;
        JTextArea a = new JTextArea(text, 10, 36);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBackground(Ui.FIELD);
        a.setForeground(Ui.TEXT);
        a.setFont(Ui.FONT);
        a.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JOptionPane.showMessageDialog(frame, new JScrollPane(a), "پیام سرور", JOptionPane.PLAIN_MESSAGE);
    }

    private void showLog() {
        String t;
        try {
            t = new String(Files.readAllBytes(RUN.resolve("engine.log")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            t = "(لاگ در دسترس نیست؛ لاگ فقط در زمان/بعد از اتصال وجود دارد)";
        }
        if (!localError.isEmpty()) t += "\nGUI: " + localError;
        JTextArea a = new JTextArea(t, 20, 56);
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        a.setBackground(Ui.FIELD);
        a.setForeground(Ui.TEXT);
        a.setCaretPosition(a.getDocument().getLength());
        JOptionPane.showMessageDialog(frame, new JScrollPane(a), "لاگ اتصال", JOptionPane.PLAIN_MESSAGE);
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
            list.setFont(Ui.FONT.deriveFont(15f));
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
            ProfileEditDialog.addRow(p, "DNS اول", d1);
            ProfileEditDialog.addRow(p, "DNS دوم (اختیاری)", d2);
            final JCheckBox bypass = new JCheckBox("ترافیک ایران مستقیم برود (بدون تونل، بدون محاسبه)", store.bypass());
            bypass.setOpaque(false);
            bypass.setForeground(Ui.TEXT);
            p.add(bypass);
            p.add(Box.createVerticalStrut(10));

            p.add(Ui.label("موارد دستی (IP، CIDR یا دامنه) که مستقیم می‌روند:", Ui.MUTED, 12f, Font.PLAIN));
            for (String s : store.manual()) model.addElement(s);
            final JList<String> list = new JList<>(model);
            list.setBackground(Ui.CARD);
            list.setForeground(Ui.TEXT);
            JScrollPane sp = new JScrollPane(list);
            sp.setPreferredSize(new Dimension(300, 110));
            p.add(sp);
            JPanel add = new JPanel(new BorderLayout(6, 0));
            add.setOpaque(false);
            final JTextField entry = Ui.field();
            entry.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            Ui.RoundButton addB = Ui.button("افزودن", Ui.ACCENT);
            Ui.RoundButton delB = Ui.button("حذف", Ui.DARK_BTN);
            addB.setPreferredSize(new Dimension(80, 40));
            delB.setPreferredSize(new Dimension(70, 40));
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

            JScrollPane outer = new JScrollPane(p);
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
