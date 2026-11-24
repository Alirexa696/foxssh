package com.foxssh.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.RenderingHints;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.io.InputStream;

/** اجزای ظاهری مشترک: رنگ‌ها، فونت وزیرمتن، دکمهٔ پاور، کارت. */
final class Ui {
    static final Color BG = new Color(0x0A1020), BAR = new Color(0x111A30), CARD = new Color(0x15203A),
            FIELD = new Color(0x1B2845), ACCENT = new Color(0x2D9CFF), TEXT = new Color(0xE8EEF9),
            MUTED = new Color(0x8895B3), OK = new Color(0x2ECC71), BAD = new Color(0xFF5C5C),
            DARK_BTN = new Color(0x22324F);
    // پالت «سرمه‌ای» رابط اصلی (آبی کم‌اشباع، لبه‌های فولادی)
    static final Color BG0 = new Color(0x070D1C), BG1 = new Color(0x0F1B38), GLOW = new Color(0x4468A8),
            CYAN = new Color(0x5B9BDB), GREEN = new Color(0x2BE07A), PANEL_TOP = new Color(0x182B55),
            PANEL_BOT = new Color(0x0E1A38), SOFT = new Color(0xB4C0DA);

    static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
    }

    /** فونت ایتالیک (کج‌شده) برای لوگوتایپ. */
    static Font italic(Font f) {
        return f.deriveFont(AffineTransform.getShearInstance(-0.17, 0));
    }

    /** پنل شیشه‌ای با لبهٔ درخشان؛ داخل کادر (۳ پیکسل حاشیه برای هاله). */
    static void glowPanel(Graphics2D g2, int w, int h, int arc, Color edge) {
        int x = 3, y = 3, ww = w - 6, hh = h - 6;
        for (int i = 3; i >= 1; i--) {
            g2.setColor(alpha(edge, 16));
            g2.setStroke(new BasicStroke(i * 2f));
            g2.drawRoundRect(x, y, ww, hh, arc, arc);
        }
        g2.setPaint(new GradientPaint(0, y, PANEL_TOP, 0, y + hh, PANEL_BOT));
        g2.fillRoundRect(x, y, ww, hh, arc, arc);
        g2.setPaint(new GradientPaint(0, y, alpha(edge, 235), 0, y + hh, alpha(edge, 150)));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(x, y, ww, hh, arc, arc);
    }

    private static Font regular, bold, medium, pRegular, pMedium, pSemi, pBold;

    private Ui() {}

    private static Font load(String res) {
        try (InputStream in = Ui.class.getResourceAsStream(res)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception ignored) {
        }
        return null;
    }

    static synchronized Font font(float size, int style) {
        if (regular == null) {
            regular = load("/fonts/Vazirmatn-Regular.ttf");
            bold = load("/fonts/Vazirmatn-Bold.ttf");
            medium = load("/fonts/Vazirmatn-Medium.ttf");
            if (regular == null) regular = new Font("SansSerif", Font.PLAIN, 14);
            if (bold == null) bold = regular.deriveFont(Font.BOLD);
            if (medium == null) medium = regular;
        }
        Font base = (style & Font.BOLD) != 0 ? bold : medium;
        return base.deriveFont(size);
    }

    private static Font loadLatin(String n, Font fallback) {
        Font f = load("/fonts/Poppins-" + n + ".ttf");
        return f != null ? f : fallback;
    }

    /** فونت لاتین (انگلیسی، IP، اعداد): Poppins. */
    static synchronized Font latin(float size, int style) {
        font(size, Font.PLAIN);
        if (pRegular == null) {
            pRegular = loadLatin("Regular", regular);
            pMedium = loadLatin("Medium", pRegular);
            pSemi = loadLatin("SemiBold", pRegular);
            pBold = loadLatin("Bold", pSemi);
        }
        return ((style & Font.BOLD) != 0 ? pBold : pMedium).deriveFont(size);
    }

    static Font latinBold(float size) {
        latin(size, Font.PLAIN);
        return pBold.deriveFont(size);
    }

    private static Font wordFont;

    /** فونت لوگوتایپ (Montserrat Italic)؛ در نبودش Poppins Bold با کجی مصنوعی. */
    static synchronized Font display(float size) {
        if (wordFont == null) {
            wordFont = load("/fonts/Montserrat-Italic.ttf");
            if (wordFont == null) wordFont = italic(latinBold(20f));
        }
        return wordFont.deriveFont(size);
    }

    static boolean hasArabic(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF || c >= 0xFB50 && c <= 0xFDFF || c >= 0xFE70 && c <= 0xFEFF || c == 0x200C) return true;
        }
        return false;
    }

    /** تایپوگرافی رابط: Vazirmatn Medium برای فارسی و Poppins Medium برای لاتین. */
    static Font pick(String text, float size, int style) {
        return hasArabic(text) ? font(size, style) : latin(size, style);
    }

    static void setText(JLabel l, String text) {
        Font f = l.getFont();
        l.setText(text);
        l.setFont(pick(text, f.getSize2D(), f.isBold() ? Font.BOLD : Font.PLAIN));
    }

    static Font mediumFont(float size) {
        font(size, Font.PLAIN);
        return medium.deriveFont(size);
    }

    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    // ───────────── دکمه‌ها ─────────────

    static final class RoundButton extends JButton {
        private Color bg;
        private final int radius;

        RoundButton(String text, Color bg, int radius) {
            super(text);
            this.bg = bg;
            this.radius = radius;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setForeground(Color.WHITE);
            setFont(pick(text, 14f, Font.BOLD));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            Color c = !isEnabled() ? bg.darker() : getModel().isRollover() ? bg.brighter() : bg;
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static RoundButton button(String text, Color bg) {
        RoundButton b = new RoundButton(text, bg, 14);
        b.setPreferredSize(new Dimension(120, 42));
        return b;
    }

    /** دکمهٔ همبرگری (سه خط). */
    static final class Hamburger extends JButton {
        Hamburger() {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(48, 30));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            if (getModel().isRollover() || getModel().isPressed()) {
                g2.setColor(new Color(255, 255, 255, 28));
                g2.fillRoundRect(4, 4, getWidth() - 8, getHeight() - 8, 12, 12);
            }
            g2.setColor(TEXT);
            g2.setStroke(new BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2, cy = getHeight() / 2;
            for (int i = -1; i <= 1; i++) g2.draw(new Line2D.Float(cx - 11, cy + i * 6, cx + 11, cy + i * 6));
            g2.dispose();
        }
    }

    /** فلش کوچک: بالا / پایین / راست. */
    static final class Chevron extends JComponent {
        static final int UP = 0, DOWN = 1, RIGHT = 2;
        private int dir = UP;
        private Color color = MUTED;

        Chevron() {
            setPreferredSize(new Dimension(28, 24));
        }

        Chevron(Color c) {
            this();
            color = c;
        }

        void setDir(int d) {
            dir = d;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2, cy = getHeight() / 2;
            if (dir == RIGHT) {
                g2.draw(new Line2D.Float(cx - 3, cy - 7, cx + 3, cy));
                g2.draw(new Line2D.Float(cx + 3, cy, cx - 3, cy + 7));
            } else {
                int d = dir == UP ? 4 : -4;
                g2.draw(new Line2D.Float(cx - 7, cy + d, cx, cy - d));
                g2.draw(new Line2D.Float(cx, cy - d, cx + 7, cy + d));
            }
            g2.dispose();
        }
    }

    /** سپر کوچک؛ با تیک وقتی فعال است. */
    static final class Shield extends JComponent {
        private boolean on;

        Shield() {
            setPreferredSize(new Dimension(30, 34));
        }

        void setOn(boolean v) {
            if (v != on) {
                on = v;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            float x0 = w / 2f - 11, x1 = w / 2f + 11, y0 = 4, y1 = h - 4;
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            p.moveTo(w / 2f, y0);
            p.lineTo(x1, y0 + 4);
            p.lineTo(x1, y0 + 14);
            p.quadTo(x1, y1 - 3, w / 2f, y1);
            p.quadTo(x0, y1 - 3, x0, y0 + 14);
            p.lineTo(x0, y0 + 4);
            p.closePath();
            g2.setColor(on ? OK : MUTED);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(p);
            if (on) {
                g2.draw(new Line2D.Float(w / 2f - 5, h / 2f + 1, w / 2f - 1, h / 2f + 5));
                g2.draw(new Line2D.Float(w / 2f - 1, h / 2f + 5, w / 2f + 6, h / 2f - 3));
            }
            g2.dispose();
        }
    }

    // ───────────── کارت ─────────────

    static final class Card extends JPanel {
        Card() {
            setOpaque(false);
            setBackground(CARD);
            setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** برچسب گرد کوچک (مثل «SSH»). */
    static final class Pill extends JLabel {
        private Color fill = DARK_BTN;

        Pill(String t) {
            super(t, CENTER);
            setFont(pick(t, 12f, Font.BOLD));
            setForeground(Color.WHITE);
            setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 12));
            setOpaque(false);
        }

        void setFill(Color c) {
            fill = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ───────────── دکمهٔ پاور ─────────────

    static final class PowerButton extends JComponent {
        static final int OFF = 0, BUSY = 1, ON = 2;
        private int state = OFF;
        private int angle;
        private final javax.swing.Timer anim;
        private boolean hover;

        PowerButton() {
            setPreferredSize(new Dimension(190, 190));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            anim = new javax.swing.Timer(30, e -> {
                angle = (angle + 8) % 360;
                repaint();
            });
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (isEnabled()) for (java.awt.event.ActionListener l : listeners) l.actionPerformed(null);
                }
            });
        }

        private final java.util.List<java.awt.event.ActionListener> listeners = new java.util.ArrayList<>();

        void addActionListener(java.awt.event.ActionListener l) { listeners.add(l); }

        void setState(int s) {
            if (s == state) return;
            state = s;
            if (s == BUSY) anim.start();
            else anim.stop();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            try {
                paintPower(g);
            } catch (Throwable t) {
                t.printStackTrace();
                g.setColor(ACCENT);
                g.fillOval(10, 10, getWidth() - 20, getHeight() - 20);
            }
        }

        private void paintPower(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f - 1;
            boolean on = state == ON, busy = state == BUSY;
            Color glow = on ? GREEN : GLOW;
            Color bright = on ? new Color(0xA6FFCB) : busy ? CYAN : (hover ? new Color(0xB4CBEE) : new Color(0x8EB0E4));
            // هالهٔ بیرونی
            g2.setPaint(new RadialGradientPaint(cx, cy, R, new float[]{0f, 0.60f, 0.78f, 1f},
                    new Color[]{alpha(glow, 0), alpha(glow, on ? 90 : 60), alpha(glow, on ? 38 : 22), alpha(glow, 0)}));
            g2.fill(new Ellipse2D.Float(cx - R, cy - R, 2 * R, 2 * R));
            // حلقهٔ محو بیرونی
            float r1 = R * 0.84f;
            g2.setStroke(new BasicStroke(1.4f));
            g2.setColor(alpha(glow, on ? 80 : 55));
            g2.draw(new Ellipse2D.Float(cx - r1, cy - r1, 2 * r1, 2 * r1));
            // حلقهٔ روشن
            float r2 = R * 0.68f;
            g2.setColor(alpha(glow, 70));
            g2.setStroke(new BasicStroke(7f));
            g2.draw(new Ellipse2D.Float(cx - r2, cy - r2, 2 * r2, 2 * r2));
            // بدنهٔ تیره
            g2.setColor(on ? new Color(0x072A1E) : new Color(0x0A1730));
            g2.fill(new Ellipse2D.Float(cx - r2, cy - r2, 2 * r2, 2 * r2));
            g2.setColor(bright);
            g2.setStroke(new BasicStroke(3f));
            g2.draw(new Ellipse2D.Float(cx - r2, cy - r2, 2 * r2, 2 * r2));
            // دیسک داخلی
            float r4 = R * 0.52f;
            Color top = on ? new Color(0x45E890) : new Color(0x2F4D86);
            Color bot = on ? new Color(0x118A48) : new Color(0x14264F);
            g2.setPaint(new GradientPaint(0, cy - r4, top, 0, cy + r4, bot));
            g2.fill(new Ellipse2D.Float(cx - r4, cy - r4, 2 * r4, 2 * r4));
            g2.setColor(new Color(255, 255, 255, on ? 70 : 38));
            g2.setStroke(new BasicStroke(1.4f));
            g2.draw(new Ellipse2D.Float(cx - r4, cy - r4, 2 * r4, 2 * r4));
            if (busy) {
                g2.setColor(new Color(0x2A3D66));
                g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Ellipse2D.Float(cx - r2, cy - r2, 2 * r2, 2 * r2));
                g2.setColor(CYAN);
                g2.draw(new Arc2D.Float(cx - r2, cy - r2, 2 * r2, 2 * r2, -angle, 100, Arc2D.OPEN));
            }
            // نماد پاور
            float ir = R * 0.21f;
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(Math.max(4f, R * 0.06f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Arc2D.Float(cx - ir, cy - ir + 3, 2 * ir, 2 * ir, 120, 300, Arc2D.OPEN));
            g2.draw(new Line2D.Float(cx, cy - ir - 4, cx, cy + 2));
            g2.dispose();
        }
    }

    // ───────────── فیلدها ─────────────

    private static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x2B3A5C), 1, true), BorderFactory.createEmptyBorder(6, 10, 6, 10));
    }

    static JTextField field() {
        JTextField f = new JTextField();
        style(f);
        return f;
    }

    static JPasswordField password() {
        JPasswordField f = new JPasswordField();
        style(f);
        f.setEchoChar('•');
        return f;
    }

    private static void style(JTextField f) {
        f.setBackground(FIELD);
        f.setForeground(TEXT);
        f.setCaretColor(TEXT);
        f.setFont(font(14f, Font.PLAIN));
        f.setBorder(fieldBorder());
        f.setHorizontalAlignment(JTextField.LEFT);
        f.setPreferredSize(new Dimension(200, 40));
    }

    static JLabel label(String t, Color c, float size, int style) {
        JLabel l = new JLabel(t);
        l.setForeground(c);
        l.setFont(pick(t, size, style));
        return l;
    }

    // ───────────── اجزای رابط جدید (نئون آبی) ─────────────

    /** کارت شیشه‌ای با لبهٔ درخشان. */
    static class GlowCard extends JPanel {
        private final int arc;
        private Color edge = new Color(0x4C72B4);

        GlowCard(int arc) {
            this.arc = arc;
            setOpaque(false);
        }

        void setEdge(Color c) {
            edge = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            glowPanel(g2, getWidth(), getHeight(), arc, edge);
            g2.dispose();
        }
    }

    /** آیکون‌های خطی: سرور، مانیتور، سند. */
    static final class Icon extends JComponent {
        static final int SERVER = 0, MONITOR = 1, DOC = 2, GEAR = 3, FOLDER = 4, INFO = 5, TUNNELS = 6, EXIT = 7;
        private final int kind;
        private final Color color;

        Icon(int kind, Color color, int w, int h) {
            this.kind = kind;
            this.color = color;
            setPreferredSize(new Dimension(w, h));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            draw(g2, kind, color, getWidth(), getHeight());
            g2.dispose();
        }

        static void draw(Graphics2D g2, int kind, Color c, int w, int h) {
            g2.setColor(c);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float cx = w / 2f, cy = h / 2f;
            if (kind == MONITOR) {
                float sw = Math.min(w - 4, 32), sh = sw * 0.66f;
                g2.draw(new RoundRectangle2D.Float(cx - sw / 2, cy - sh / 2 - 3, sw, sh, 5, 5));
                g2.draw(new Line2D.Float(cx, cy + sh / 2 - 3, cx, cy + sh / 2 + 3));
                g2.draw(new Line2D.Float(cx - 6, cy + sh / 2 + 4, cx + 6, cy + sh / 2 + 4));
            } else if (kind == SERVER) {
                float bw = Math.min(w - 2, 15), bh = 5.2f;
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * 6.4f - bh / 2;
                    g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(new RoundRectangle2D.Float(cx - bw / 2, y, bw, bh, 3, 3));
                    g2.fill(new Ellipse2D.Float(cx + bw / 2 - 5, y + bh / 2 - 0.9f, 1.8f, 1.8f));
                }
            } else if (kind == GEAR) {
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Ellipse2D.Float(cx - 5f, cy - 5f, 10f, 10f));
                g2.draw(new Ellipse2D.Float(cx - 1.8f, cy - 1.8f, 3.6f, 3.6f));
                g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    g2.draw(new Line2D.Double(cx + Math.cos(a) * 7.0, cy + Math.sin(a) * 7.0, cx + Math.cos(a) * 8.6, cy + Math.sin(a) * 8.6));
                }
            } else if (kind == FOLDER) {
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float fw = 20, fh = 15, x = cx - fw / 2, y = cy - fh / 2 + 0.5f;
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x + 2, y);
                p.lineTo(x + 7, y);
                p.lineTo(x + 9.5f, y + 2.5f);
                p.lineTo(x + fw - 2, y + 2.5f);
                p.quadTo(x + fw, y + 2.5f, x + fw, y + 4.5f);
                p.lineTo(x + fw, y + fh - 2);
                p.quadTo(x + fw, y + fh, x + fw - 2, y + fh);
                p.lineTo(x + 2, y + fh);
                p.quadTo(x, y + fh, x, y + fh - 2);
                p.lineTo(x, y + 2);
                p.quadTo(x, y, x + 2, y);
                g2.draw(p);
                g2.draw(new Line2D.Float(x + 0.5f, y + 6.5f, x + fw - 0.5f, y + 6.5f));
            } else if (kind == INFO) {
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Ellipse2D.Float(cx - 8.5f, cy - 8.5f, 17f, 17f));
                g2.fill(new Ellipse2D.Float(cx - 1.1f, cy - 4.8f, 2.2f, 2.2f));
                g2.draw(new Line2D.Float(cx, cy - 1.2f, cx, cy + 4.8f));
            } else if (kind == TUNNELS) {
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Line2D.Float(cx - 8, cy - 4, cx + 8, cy - 4));
                g2.draw(new Line2D.Float(cx + 4, cy - 8, cx + 8, cy - 4));
                g2.draw(new Line2D.Float(cx + 8, cy - 4, cx + 4, cy));
                g2.draw(new Line2D.Float(cx + 8, cy + 4, cx - 8, cy + 4));
                g2.draw(new Line2D.Float(cx - 4, cy, cx - 8, cy + 4));
                g2.draw(new Line2D.Float(cx - 8, cy + 4, cx - 4, cy + 8));
            } else if (kind == EXIT) {
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Float p = new Path2D.Float();
                p.moveTo(cx - 1, cy - 8);
                p.lineTo(cx - 7, cy - 8);
                p.lineTo(cx - 7, cy + 8);
                p.lineTo(cx - 1, cy + 8);
                g2.draw(p);
                g2.draw(new Line2D.Float(cx - 2, cy, cx + 8, cy));
                g2.draw(new Line2D.Float(cx + 4, cy - 4, cx + 8, cy));
                g2.draw(new Line2D.Float(cx + 8, cy, cx + 4, cy + 4));
            } else {
                float dw = Math.min(w - 6, 19), dh = Math.min(h - 2, 24), x = cx - dw / 2, y = cy - dh / 2;
                Path2D.Float p = new Path2D.Float();
                p.moveTo(x + 4, y);
                p.lineTo(x + dw - 4, y);
                p.quadTo(x + dw, y, x + dw, y + 4);
                p.lineTo(x + dw, y + dh - 4);
                p.quadTo(x + dw, y + dh, x + dw - 4, y + dh);
                p.lineTo(x + 4, y + dh);
                p.quadTo(x, y + dh, x, y + dh - 4);
                p.lineTo(x, y + 4);
                p.quadTo(x, y, x + 4, y);
                g2.draw(p);
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 3; i++) {
                    float ly = y + 7.5f + i * 5.2f;
                    g2.draw(new Line2D.Float(x + 5, ly, x + dw - (i == 2 ? 9 : 5), ly));
                }
            }
        }
    }

    /** برچسب «SSH | سرور | پورت» با لبهٔ درخشان. */
    static final class PortPill extends JComponent {
        private String proto = "SSH";
        private int port = 22;

        PortPill() {
            setOpaque(false);
            setFont(latin(15f, Font.BOLD));
        }

        void setPort(int p) {
            if (p != port) {
                port = p;
                revalidate();
                repaint();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(proto) + fm.stringWidth(String.valueOf(port)) + 86, 40);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            glowPanel(g2, w, h, h, new Color(0x4C72B4));
            g2.setFont(getFont());
            java.awt.FontMetrics fm = g2.getFontMetrics();
            int base = (h - fm.getHeight()) / 2 + fm.getAscent();
            int x = 20;
            g2.setColor(TEXT);
            g2.drawString(proto, x, base);
            x += fm.stringWidth(proto) + 12;
            g2.setColor(alpha(SOFT, 170));
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(new Line2D.Float(x, h / 2f - 9, x, h / 2f + 9));
            x += 10;
            g2.translate(x, 0);
            Icon.draw(g2, Icon.SERVER, TEXT, 18, h);
            g2.translate(-x, 0);
            x += 24;
            g2.setColor(TEXT);
            g2.drawString(String.valueOf(port), x, base);
            g2.dispose();
        }
    }

    /** خط درخشان افقی زیر وضعیت. */
    static final class GlowLine extends JComponent {
        private Color color = GLOW;

        GlowLine() {
            setOpaque(false);
            setPreferredSize(new Dimension(360, 8));
        }

        void setColor(Color c) {
            if (!c.equals(color)) {
                color = c;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), cy = getHeight() / 2;
            g2.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.5f, 1f},
                    new Color[]{alpha(color, 0), alpha(color, 70), alpha(color, 0)}));
            g2.setStroke(new BasicStroke(6f));
            g2.draw(new Line2D.Float(0, cy, w, cy));
            g2.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.5f, 1f},
                    new Color[]{alpha(color, 0), alpha(color, 255), alpha(color, 0)}));
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(new Line2D.Float(0, cy, w, cy));
            g2.dispose();
        }
    }

    /** دایرهٔ سبز با تیک کنار «Connected». */
    static final class CheckBadge extends JComponent {
        CheckBadge() {
            setOpaque(false);
            setPreferredSize(new Dimension(26, 26));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            float d = Math.min(w, h) - 4, x = (w - d) / 2f, y = (h - d) / 2f;
            g2.setColor(alpha(GREEN, 50));
            g2.fill(new Ellipse2D.Float(x - 2, y - 2, d + 4, d + 4));
            g2.setPaint(new GradientPaint(0, y, new Color(0x3CEB8A), 0, y + d, new Color(0x16A957)));
            g2.fill(new Ellipse2D.Float(x, y, d, d));
            g2.setColor(new Color(0x06241A));
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float cx = w / 2f, cy = h / 2f;
            g2.draw(new Line2D.Float(cx - 5.5f, cy + 0.5f, cx - 1.5f, cy + 4.5f));
            g2.draw(new Line2D.Float(cx - 1.5f, cy + 4.5f, cx + 6, cy - 4));
            g2.dispose();
        }
    }

    /** دکمهٔ کوچک کنترل پنجره: کمینه (—) یا بستن (×). */
    static final class WinGlyph extends JButton {
        static final int MIN = 0, CLOSE = 1;
        private final int kind;

        WinGlyph(int kind) {
            this.kind = kind;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(34, 28));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            boolean hot = getModel().isRollover();
            g2.setColor(kind == CLOSE && hot ? new Color(0xFF7885) : hot ? Color.WHITE : TEXT);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (kind == MIN) {
                g2.draw(new Line2D.Float(cx - 8, cy, cx + 8, cy));
            } else {
                g2.draw(new Line2D.Float(cx - 6.5f, cy - 6.5f, cx + 6.5f, cy + 6.5f));
                g2.draw(new Line2D.Float(cx - 6.5f, cy + 6.5f, cx + 6.5f, cy - 6.5f));
            }
            g2.dispose();
        }
    }

    /** جداکنندهٔ عمودی نازک. */
    static final class VDivider extends JComponent {
        VDivider(int h) {
            setPreferredSize(new Dimension(2, h));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, alpha(SOFT, 0), 0, getHeight() / 2f, alpha(SOFT, 130), true));
            g2.fillRect(0, 0, 1, getHeight());
            g2.dispose();
        }
    }

    /** هدر: زبانهٔ مرکزی مورب با لبهٔ درخشان؛ لوگو وسط، کنترل پنجره بالا-راست، همبرگر زیر آن. */
    static final class Header extends JPanel {
        private final JComponent brand, controls, burger;

        Header(JComponent brand, JComponent controls, JComponent burger) {
            super(null);
            this.brand = brand;
            this.controls = controls;
            this.burger = burger;
            setOpaque(false);
            add(brand);
            add(controls);
            add(burger);
        }

        @Override
        public void doLayout() {
            int w = getWidth(), h = getHeight();
            Dimension b = brand.getPreferredSize(), c = controls.getPreferredSize(), u = burger.getPreferredSize();
            brand.setBounds((w - b.width) / 2, Math.max(4, (h - 4 - b.height) / 2), b.width, b.height);
            controls.setBounds(w - c.width - 20, 8, c.width, c.height);
            burger.setBounds(w - u.width - 14, h - u.height, u.width, u.height);
        }

        private Path2D.Float tab(int w, int h, float inset) {
            float sh = h * 0.58f;
            Path2D.Float p = new Path2D.Float();
            p.moveTo(0, sh);
            p.lineTo(w * 0.15f, sh);
            p.lineTo(w * 0.23f, h - inset);
            p.lineTo(w * 0.77f, h - inset);
            p.lineTo(w * 0.85f, sh);
            p.lineTo(w, sh);
            return p;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            Path2D.Float line = tab(w, h, 2);
            Path2D.Float fill = new Path2D.Float(line);
            fill.lineTo(w, 0);
            fill.lineTo(0, 0);
            fill.closePath();
            g2.setPaint(new GradientPaint(0, 0, new Color(0x0D1A38), 0, h, new Color(0x14264F)));
            g2.fill(fill);
            g2.setColor(alpha(CYAN, 26));
            g2.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
            g2.setColor(alpha(CYAN, 50));
            g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
            g2.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.2f, 0.5f, 0.8f, 1f},
                    new Color[]{alpha(GLOW, 90), new Color(0x5B84C4), new Color(0x6CA6E6), new Color(0x5B84C4), alpha(GLOW, 90)}));
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(line);
            g2.dispose();
        }
    }

    /** لوگوتایپ «Fox VPN»: حروف ضخیم کج‌شده؛ Fox سفید-نقره‌ای و VPN آبی نئونی با گرادیان و هاله. */
    static final class Wordmark extends JComponent {
        private static final float WEIGHT = 4.2f; // ضخامت‌دهی با کانتور (فونت متغیر در حالت Regular بارگذاری می‌شود)
        private final Font font;
        private final float size;

        Wordmark(float size) {
            this.size = size;
            this.font = display(size);
            setOpaque(false);
        }

        private float gap() {
            return size * 0.34f;
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.FontMetrics fm = getFontMetrics(font);
            return new Dimension(fm.stringWidth("Fox") + fm.stringWidth("VPN") + Math.round(gap()) + Math.round(size * 0.28f),
                    fm.getHeight() + 6);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            java.awt.FontMetrics fm = g2.getFontMetrics(font);
            float base = (getHeight() - fm.getHeight()) / 2f + fm.getAscent();
            float top = base - fm.getAscent() * 0.78f;
            java.awt.font.FontRenderContext frc = g2.getFontRenderContext();
            float x = 2;
            java.awt.Shape fox = font.createGlyphVector(frc, "Fox").getOutline(x, base);
            x += fm.stringWidth("Fox") + gap();
            java.awt.Shape vpn = font.createGlyphVector(frc, "VPN").getOutline(x, base);
            // سایهٔ ملایم Fox
            g2.setColor(new Color(0, 0, 0, 90));
            g2.translate(0, 1.6);
            g2.fill(fox);
            g2.translate(0, -1.6);
            GradientPaint foxPaint = new GradientPaint(0, top, Color.WHITE, 0, base, new Color(0x9FB0D6));
            g2.setPaint(foxPaint);
            g2.fill(fox);
            g2.setStroke(new BasicStroke(WEIGHT, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 3f));
            g2.draw(fox);
            // هالهٔ VPN
            g2.setColor(alpha(CYAN, 26));
            g2.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(vpn);
            g2.setColor(alpha(CYAN, 48));
            g2.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(vpn);
            GradientPaint vpnPaint = new GradientPaint(0, top, new Color(0x74BAF2), 0, base, new Color(0x2F6FD0));
            g2.setPaint(vpnPaint);
            g2.fill(vpn);
            g2.setStroke(new BasicStroke(WEIGHT, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 3f));
            g2.draw(vpn);
            g2.dispose();
        }
    }

    /** منوی بازشوی گرد با پس‌زمینهٔ سرمه‌ای و لبهٔ فولادی. */
    static final class PopupMenu extends javax.swing.JPopupMenu {
        PopupMenu() {
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
            addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    javax.swing.SwingUtilities.invokeLater(() -> roundWindow());
                }
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
            });
        }

        /** اگر منو پنجرهٔ مستقل است (بیرون از پنجرهٔ برنامه)، گوشه‌هایش را گرد می‌کند. */
        private void roundWindow() {
            try {
                java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(this);
                java.awt.Component inv = getInvoker();
                java.awt.Window own = inv == null ? null : javax.swing.SwingUtilities.getWindowAncestor(inv);
                if (w != null && w != own) w.setShape(new RoundRectangle2D.Float(0, 0, w.getWidth(), w.getHeight(), 18, 18));
            } catch (Exception ignored) {
            }
        }

        @Override
        public void addSeparator() {
            add(new Sep());
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(d.width, 250), d.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, new Color(0x16274F), 0, h, new Color(0x0E1A38)));
            g2.fillRoundRect(0, 0, w, h, 18, 18);
            g2.setColor(new Color(0x3A5A92));
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawRoundRect(1, 1, w - 3, h - 3, 17, 17);
            g2.dispose();
        }
    }

    /** خط جداکنندهٔ منو. */
    static final class Sep extends JComponent {
        Sep() {
            setOpaque(false);
            setPreferredSize(new Dimension(10, 11));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 11));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(new Color(0x2E4573));
            g.fillRect(4, getHeight() / 2, getWidth() - 8, 1);
        }
    }

    /** ردیف منو: آیکون یا نقطهٔ سبز + متن؛ با هایلایت گرد هنگام عبور ماوس. */
    static final class MenuRow extends javax.swing.JMenuItem {
        private final int icon;
        private final boolean dot, current;

        /** icon = -1 بدون آیکون؛ dot = نقطهٔ سبز (لیست کانفیگ‌ها)؛ current = کانفیگ انتخاب‌شده. */
        MenuRow(String text, int icon, boolean dot, boolean current) {
            super(text);
            this.icon = icon;
            this.dot = dot;
            this.current = current;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            setBorderPainted(false);
            setFocusPainted(false);
            setFont(pick(text, 15f, Font.PLAIN));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private int textX() {
            return icon >= 0 || dot ? 50 : 26;
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(textX() + fm.stringWidth(getText()) + 30, 40);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 40);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            boolean hot = getModel().isArmed() || getModel().isRollover();
            if (hot || current) {
                g2.setColor(new Color(130, 170, 235, hot ? 38 : 20));
                g2.fillRoundRect(2, 2, w - 4, h - 4, 12, 12);
            }
            if (icon >= 0) {
                g2.translate(14, (h - 24) / 2);
                Icon.draw(g2, icon, SOFT, 24, 24);
                g2.translate(-14, -(h - 24) / 2);
            } else if (dot && current) { // دایرهٔ سبز فقط کنار کانفیگ انتخاب‌شده
                float d = 11f, x = 20, y = (h - d) / 2f;
                g2.setColor(alpha(GREEN, 55));
                g2.fill(new Ellipse2D.Float(x - 3, y - 3, d + 6, d + 6));
                g2.setColor(GREEN);
                g2.fill(new Ellipse2D.Float(x, y, d, d));
            }
            g2.setFont(getFont());
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.setColor(TEXT);
            g2.drawString(getText(), textX(), (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}
