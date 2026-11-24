package com.foxssh.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** دکمهٔ بزرگ پاور (مثل نسخهٔ دسکتاپ): خاموش = آبی، در حال اتصال = حلقهٔ چرخان، وصل = سبز. */
public final class PowerView extends View {
    public static final int OFF = 0, BUSY = 1, ON = 2;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int state = OFF;

    public PowerView(Context c) {
        super(c);
    }

    public PowerView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setState(int s) {
        if (s != state) {
            state = s;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f * 0.72f;
        int ring = state == ON ? 0xFF2ECC71 : state == BUSY ? 0xFF2D9CFF : 0xFF5B8DEF;
        int top = state == ON ? 0xFF1E8E4E : 0xFF22335A;
        int bottom = state == ON ? 0xFF0F4F2A : 0xFF16213D;

        // درخشش اطراف
        p.setStyle(Paint.Style.FILL);
        for (int i = 4; i >= 1; i--) {
            p.setShader(null);
            p.setColor((ring & 0x00FFFFFF) | (14 << 24));
            c.drawCircle(cx, cy, r + i * r * 0.10f, p);
        }
        // بدنهٔ دکمه
        p.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.2f, top, bottom, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);

        // حلقه
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        float rw = r * 0.09f;
        p.setStrokeWidth(rw);
        p.setColor(ring);
        c.drawCircle(cx, cy, r, p);
        if (state == BUSY) {
            float a = (System.currentTimeMillis() / 4) % 360;
            p.setStrokeWidth(rw * 1.6f);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(rect, a, 90, false, p);
            postInvalidateOnAnimation();
        }

        // آیکن پاور
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(r * 0.10f);
        float ir = r * 0.38f;
        rect.set(cx - ir, cy - ir, cx + ir, cy + ir);
        c.drawArc(rect, -50, 280, false, p);
        c.drawLine(cx, cy - ir * 1.15f, cx, cy - ir * 0.15f, p);
    }
}
