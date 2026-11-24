package com.foxssh.desktop;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Properties;

/** پیش‌نمایش بدون X11 (headless): رابط اصلی را در حالت‌های مختلف روی PNG می‌کشد. فقط برای توسعه. */
public final class Preview {
    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        final String out = a[0];
        final int w = a.length > 1 ? Integer.parseInt(a[1]) : 720, h = a.length > 2 ? Integer.parseInt(a[2]) : 540;
        if (a.length > 3 && a[3].equals("en")) L.setFa(false);
        Gui.demoIp = "130.94.2.77";
        Gui.demoCc = "IR";
        SwingUtilities.invokeAndWait(() -> {
            try {
                Gui.applyTheme();
                Store st = new Store();
                Store.Profile p = new Store.Profile();
                p.name = "FoxVPN-Tehran"; p.host = "vps.example.com"; p.port = 22; p.user = "u"; p.pass = "x";
                st.saveProfile(p);
                Store.Profile p2 = new Store.Profile();
                p2.name = "amir"; p2.host = "amir.vps-son.ir"; p2.port = 22; p2.user = "u"; p2.pass = "x";
                st.saveProfile(p2);
                p.name = "sena"; p.host = "vps.se-mena.com"; st.saveProfile(p);
                Gui g = new Gui();
                JPanel root = g.buildMain();
                Method rl = Gui.class.getDeclaredMethod("reloadProfiles"); rl.setAccessible(true);
                // reloadProfiles needs no frame
                Method rd = Gui.class.getDeclaredMethod("render", Properties.class); rd.setAccessible(true);
                rl.invoke(g);
                shot(root, w, h, out + "/off.png", g, rd, null);
                Properties s = new Properties();
                s.setProperty("state", "connected");
                s.setProperty("since", String.valueOf(System.currentTimeMillis() - 91000));
                s.setProperty("tx", "463912"); s.setProperty("rx", "1992294"); s.setProperty("banner", "");
                shot(root, w, h, out + "/on.png", g, rd, s);
                menus(g, root, rd, s, w, h, out);
                Properties c = new Properties(); c.setProperty("state", "connecting");
                shot(root, w, h, out + "/busy.png", g, rd, c);
            } catch (Throwable t) { t.printStackTrace(); }
        });
        System.exit(0);
    }

    static void shot(JPanel root, int w, int h, String path, Gui g, Method rd, Properties st) throws Exception {
        java.lang.reflect.Field ipf = Gui.class.getDeclaredField("ip"); ipf.setAccessible(true);
        java.lang.reflect.Field ccf = Gui.class.getDeclaredField("ipCc"); ccf.setAccessible(true);
        ipf.set(g, "130.94.2.77"); ccf.set(g, "IR");
        rd.invoke(g, st);
        root.setSize(new Dimension(w, h));
        layout(root);
        layout(root);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = im.createGraphics();
        root.paint(g2);
        g2.dispose();
        ImageIO.write(im, "png", new File(path));
    }

    /** منوها را روی تصویر پنجره (حالت متصل) می‌گذارد تا جایگاه و ظاهر با طرح مقایسه شود. */
    static void menus(Gui g, JPanel root, Method rd, Properties st, int w, int h, String out) throws Exception {
        rd.invoke(g, st);
        root.setSize(new Dimension(w, h));
        layout(root);
        layout(root);
        BufferedImage im = new BufferedImage(w + 60, h + 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = im.createGraphics();
        g2.setColor(new java.awt.Color(0x6A7076));
        g2.fillRect(0, 0, im.getWidth(), im.getHeight());
        g2.translate(30, 0);
        root.paint(g2);
        javax.swing.JPopupMenu m = g.buildMenu();
        Dimension d = m.getPreferredSize();
        m.setSize(d);
        layout(m);
        Graphics2D a = (Graphics2D) g2.create();
        a.translate(w - d.width + 4, 78);
        m.paint(a);
        a.dispose();
        javax.swing.JPopupMenu pm = g.buildProfileMenu();
        Dimension pd = pm.getPreferredSize();
        pd = new Dimension(Math.max(pd.width, 280), pd.height);
        pm.setSize(pd);
        layout(pm);
        Graphics2D b = (Graphics2D) g2.create();
        b.translate(w / 2 - pd.width / 2, 354);
        pm.paint(b);
        b.dispose();
        g2.dispose();
        ImageIO.write(im, "png", new File(out + "/menus.png"));
    }

    static void layout(Component c) {
        if (c instanceof Container) {
            c.invalidate();
            ((Container) c).doLayout();
            for (Component k : ((Container) c).getComponents()) layout(k);
        }
    }
}
