package com.foxssh.vpn;

import android.app.Activity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

/** نوار بالای صفحه‌ها (layout/bar.xml). */
final class Bar {
    private Bar() {}

    static void setup(final Activity a, int titleRes, boolean back, boolean logo) {
        ((TextView) a.findViewById(R.id.barTitle)).setText(titleRes);
        if (back) {
            View b = a.findViewById(R.id.barBack);
            b.setVisibility(View.VISIBLE);
            b.setOnClickListener(v -> a.finish());
        }
        if (logo) a.findViewById(R.id.barLogo).setVisibility(View.VISIBLE);
    }

    static void action1(Activity a, int icon, Runnable r) {
        set(a.findViewById(R.id.barAction1), icon, r);
    }

    static void action2(Activity a, int icon, Runnable r) {
        set(a.findViewById(R.id.barAction2), icon, r);
    }

    private static void set(ImageButton b, int icon, final Runnable r) {
        b.setImageResource(icon);
        b.setVisibility(View.VISIBLE);
        b.setOnClickListener(v -> r.run());
    }
}
