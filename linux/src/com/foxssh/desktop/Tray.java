package com.foxssh.desktop;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * آیکون نوار بالا (system tray): لوگوی برنامه، و هنگام اتصال یک دایرهٔ سبز کنارش.
 * روی اوبونتو/گنوم از AppIndicator (اسکریپت پایتون foxssh-tray) استفاده می‌شود؛ اگر نبود، SystemTray خود جاوا.
 */
final class Tray {
    private Process proc;
    private OutputStream toHelper;
    private TrayIcon awtIcon;
    private Image awtOff, awtOn;
    private boolean last;

    private Tray() {
    }

    /** onShow: نمایش/آوردن پنجره جلو؛ onExit: خروج. هر دو روی نخ دلخواه صدا زده می‌شوند. */
    static Tray start(String showLabel, String exitLabel, Runnable onShow, Runnable onExit) {
        Tray t = new Tray();
        try {
            if (t.startIndicator(showLabel, exitLabel, onShow, onExit)) return t;
        } catch (Exception e) {
            System.err.println("[fox] tray helper: " + e);
        }
        try {
            if (t.startAwt(showLabel, exitLabel, onShow, onExit)) return t;
        } catch (Throwable e) {
            System.err.println("[fox] awt tray: " + e);
        }
        return null;
    }

    /** وضعیت اتصال: true = دایرهٔ سبز کنار لوگو، false = لوگوی ساده. */
    synchronized void setConnected(boolean on) {
        if (on == last) return;
        last = on;
        try {
            if (toHelper != null) {
                toHelper.write((on ? "on\n" : "off\n").getBytes(StandardCharsets.UTF_8));
                toHelper.flush();
            } else if (awtIcon != null) {
                awtIcon.setImage(on ? awtOn : awtOff);
            }
        } catch (IOException ignored) {
        }
    }

    void close() {
        if (proc != null) proc.destroy();
        if (awtIcon != null) SystemTray.getSystemTray().remove(awtIcon);
    }

    // ── AppIndicator (پایتون)
    private boolean startIndicator(String showLabel, String exitLabel, Runnable onShow, Runnable onExit) throws Exception {
        File py = findHelper();
        if (py == null) return false;
        Path dir = Files.createTempDirectory("foxssh-tray");
        dir.toFile().deleteOnExit();
        for (String n : new String[]{"off", "on"}) {
            Path f = dir.resolve("foxssh-" + n + ".png");
            try (InputStream in = Tray.class.getResourceAsStream("/tray-" + n + ".png")) {
                if (in == null) return false;
                Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
            }
            f.toFile().deleteOnExit();
        }
        ProcessBuilder pb = new ProcessBuilder("python3", py.getPath(), dir.toString(), showLabel, exitLabel);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        // اولین خط «ready» یعنی AppIndicator بالا آمد؛ اگر اسکریپت بلافاصله خارج شد (کتابخانه نصب نیست) -> fallback
        String first = r.readLine();
        if (first == null || !first.equals("ready")) {
            p.destroy();
            return false;
        }
        proc = p;
        toHelper = p.getOutputStream();
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.equals("show")) onShow.run();
                    else if (line.equals("exit")) onExit.run();
                }
            } catch (IOException ignored) {
            }
        }, "tray-reader");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
        return true;
    }

    private static File findHelper() {
        for (String c : Arrays.asList("/usr/lib/foxssh/foxssh-tray.py", System.getProperty("foxssh.tray", ""))) {
            if (!c.isEmpty() && new File(c).canRead()) return new File(c);
        }
        return null;
    }

    // ── SystemTray خود جاوا (جایگزین)
    private boolean startAwt(String showLabel, String exitLabel, Runnable onShow, Runnable onExit) throws Exception {
        if (!SystemTray.isSupported()) return false;
        awtOff = ImageIO.read(Tray.class.getResourceAsStream("/tray-off.png"));
        awtOn = ImageIO.read(Tray.class.getResourceAsStream("/tray-on.png"));
        PopupMenu m = new PopupMenu();
        MenuItem show = new MenuItem(showLabel);
        show.addActionListener(e -> onShow.run());
        MenuItem exit = new MenuItem(exitLabel);
        exit.addActionListener(e -> onExit.run());
        m.add(show);
        m.addSeparator();
        m.add(exit);
        awtIcon = new TrayIcon(awtOff, "Fox VPN", m);
        awtIcon.setImageAutoSize(true);
        awtIcon.addActionListener(e -> onShow.run());
        SystemTray.getSystemTray().add(awtIcon);
        return true;
    }
}
