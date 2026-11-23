package com.foxssh.desktop;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Properties;

/** تست ظاهر: GUI را با وضعیت‌های ساختگی اجرا و عکس می‌گیرد (استفاده در توسعه؛ داخل .deb نیست). */
public final class GuiShot {
    static volatile boolean active;
    static volatile String state = "connected";

    public static void main(String[] a) throws Exception {
        final String outDir = a[0];
        Store st = new Store();
        Store.Profile p = new Store.Profile();
        p.name = "sena";
        p.host = "vps.se-mena.com";
        p.port = 2086;
        p.user = "amin";
        p.pass = "x";
        st.saveProfile(p);
        Gui.activeFn = () -> active;
        Gui.statusFn = () -> {
            Properties s = new Properties();
            s.setProperty("state", state);
            s.setProperty("since", String.valueOf(System.currentTimeMillis() - 91000));
            s.setProperty("tx", "463912");
            s.setProperty("rx", "1992294");
            s.setProperty("banner", "");
            return s;
        };
        Gui.demoIp = "130.94.2.77";
        final Gui[] g = new Gui[1];
        SwingUtilities.invokeAndWait(() -> {
            g[0] = new Gui();
            g[0].show();
        });
        Thread.sleep(1500);
        shot(g[0], outDir + "/off.png");
        active = true;
        state = "connecting";
        Thread.sleep(1500);
        shot(g[0], outDir + "/busy.png");
        state = "connected";
        Thread.sleep(1500);
        shot(g[0], outDir + "/on.png");
        SwingUtilities.invokeAndWait(() -> g[0].openLogForTest());
        Thread.sleep(1200);
        shot(g[0], outDir + "/log.png");
        for (String w : new String[]{"profiles", "edit", "settings", "tunnels"}) {
            final javax.swing.JDialog[] d = new javax.swing.JDialog[1];
            SwingUtilities.invokeAndWait(() -> {
                d[0] = g[0].dialogForTest(w);
                d[0].setVisible(true);
            });
            Thread.sleep(800);
            final BufferedImage[] im = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                java.awt.Container c = d[0].getContentPane();
                im[0] = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = im[0].createGraphics();
                c.paint(g2);
                g2.dispose();
                d[0].dispose();
            });
            ImageIO.write(im[0], "png", new File(outDir + "/dlg-" + w + ".png"));
        }
        SwingUtilities.invokeAndWait(() -> g[0].switchLang("en"));
        Thread.sleep(1500);
        shot(g[0], outDir + "/on-en.png");
        for (String w : new String[]{"settings", "tunnels"}) {
            final javax.swing.JDialog[] d = new javax.swing.JDialog[1];
            SwingUtilities.invokeAndWait(() -> {
                d[0] = g[0].dialogForTest(w);
                d[0].setVisible(true);
            });
            Thread.sleep(800);
            final BufferedImage[] im = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                java.awt.Container c = d[0].getContentPane();
                im[0] = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = im[0].createGraphics();
                c.paint(g2);
                g2.dispose();
                d[0].dispose();
            });
            ImageIO.write(im[0], "png", new File(outDir + "/dlg-" + w + "-en.png"));
        }
        System.exit(0);
    }

    static void shot(Gui g, String path) throws Exception {
        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Container c = g.frame().getContentPane();
            img[0] = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = img[0].createGraphics();
            c.paint(g2);
            g2.dispose();
        });
        ImageIO.write(img[0], "png", new File(path));
    }
}
