package com.foxssh.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** اجزای ظاهری مشترک (تم تیره، مثل نسخهٔ اندروید). */
final class Ui {
    static final Color BG = new Color(0x0B1220), BAR = new Color(0x16213A), CARD = new Color(0x141E33),
            FIELD = new Color(0x1B2640), ACCENT = new Color(0xFF7A1A), TEXT = new Color(0xE8EDF7),
            MUTED = new Color(0x8A97B2), OK = new Color(0x2ECC71), BAD = new Color(0xE74C3C),
            DARK_BTN = new Color(0x22304F);
    static final Font FONT = new Font("SansSerif", Font.PLAIN, 14);

    private Ui() {}

    /** دکمهٔ گرد. */
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
            setForeground(Color.WHITE);
            setFont(FONT.deriveFont(Font.BOLD, 15f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setBg(Color c) {
            bg = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
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

    private static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD, 1, true), BorderFactory.createEmptyBorder(8, 10, 8, 10));
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
        f.setFont(FONT);
        f.setBorder(fieldBorder());
        f.setHorizontalAlignment(JTextField.LEFT);
        f.setPreferredSize(new Dimension(200, 40));
    }

    static JLabel label(String t, Color c, float size, int style) {
        JLabel l = new JLabel(t);
        l.setForeground(c);
        l.setFont(FONT.deriveFont(style, size));
        return l;
    }
}
