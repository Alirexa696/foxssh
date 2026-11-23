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
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.io.InputStream;

/** اجزای ظاهری مشترک: رنگ‌ها، فونت وزیرمتن، دکمهٔ پاور، کارت. */
final class Ui {
    static final Color BG = new Color(0x0A1020), BAR = new Color(0x111A30), CARD = new Color(0x15203A),
            FIELD = new Color(0x1B2845), ACCENT = new Color(0x2D9CFF), TEXT = new Color(0xE8EEF9),
            MUTED = new Color(0x8895B3), OK = new Color(0x2ECC71), BAD = new Color(0xFF5C5C),
            DARK_BTN = new Color(0x22324F);

    private static Font regular, bold, medium;

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
        Font base = (style & Font.BOLD) != 0 ? bold : regular;
        return base.deriveFont(size);
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
            setFont(font(14f, Font.BOLD));
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
            setPreferredSize(new Dimension(48, 44));
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
            g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2, cy = getHeight() / 2;
            for (int i = -1; i <= 1; i++) g2.draw(new Line2D.Float(cx - 9, cy + i * 6, cx + 9, cy + i * 6));
            g2.dispose();
        }
    }

    /** فلش کوچک بالا/پایین برای کشوی لاگ. */
    static final class Chevron extends JComponent {
        private boolean up = true;

        Chevron() {
            setPreferredSize(new Dimension(24, 20));
        }

        void setUp(boolean u) {
            up = u;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            g2.setColor(MUTED);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2, cy = getHeight() / 2;
            int d = up ? 4 : -4;
            g2.draw(new Line2D.Float(cx - 6, cy + d, cx, cy - d));
            g2.draw(new Line2D.Float(cx, cy - d, cx + 6, cy + d));
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
            setFont(font(12f, Font.BOLD));
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
            Graphics2D g2 = (Graphics2D) g.create();
            antialias(g2);
            int w = getWidth(), h = getHeight();
            int d = Math.min(w, h) - 24;
            int x = (w - d) / 2, y = (h - d) / 2;
            Color ring = state == ON ? OK : state == BUSY ? ACCENT : new Color(0x2B3A5C);
            // هالهٔ نرم
            if (state != OFF) {
                for (int i = 6; i >= 1; i--) {
                    g2.setColor(new Color(ring.getRed(), ring.getGreen(), ring.getBlue(), 10));
                    g2.fill(new Ellipse2D.Float(x - i * 3, y - i * 3, d + i * 6, d + i * 6));
                }
            }
            // بدنهٔ دایره
            if (state == ON) {
                g2.setPaint(new GradientPaint(0, y, new Color(0x3EE08A), 0, y + d, new Color(0x1FA85A)));
            } else {
                g2.setPaint(new GradientPaint(0, y, hover ? new Color(0x1E2C4D) : new Color(0x182440),
                        0, y + d, new Color(0x111A30)));
            }
            g2.fill(new Ellipse2D.Float(x, y, d, d));
            // حلقه
            g2.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(ring);
            if (state == BUSY) {
                g2.setColor(new Color(0x2B3A5C));
                g2.draw(new Ellipse2D.Float(x + 2, y + 2, d - 4, d - 4));
                g2.setColor(ACCENT);
                g2.draw(new Arc2D.Float(x + 2, y + 2, d - 4, d - 4, -angle, 100, Arc2D.OPEN));
            } else {
                g2.draw(new Ellipse2D.Float(x + 2, y + 2, d - 4, d - 4));
            }
            // نماد پاور
            Color ic = state == ON ? Color.WHITE : state == BUSY ? ACCENT : new Color(0xC9D4EC);
            g2.setColor(ic);
            g2.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = w / 2, cy = h / 2, r = d / 5 + 4;
            g2.draw(new Arc2D.Float(cx - r, cy - r + 4, 2 * r, 2 * r, 120, 300, Arc2D.OPEN));
            g2.draw(new Line2D.Float(cx, cy - r - 6, cx, cy + 2));
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
        l.setFont(font(size, style));
        return l;
    }
}
