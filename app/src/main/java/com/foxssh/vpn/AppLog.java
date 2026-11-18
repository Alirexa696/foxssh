package com.foxssh.vpn;

import com.foxssh.vpn.core.CoreLog;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** لاگ درون‌برنامه‌ای برای عیب‌یابی (فقط در حافظه). */
public final class AppLog {
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final int MAX = 400;

    private AppLog() {}

    public static void install() {
        CoreLog.sink = AppLog::add;
    }

    public static synchronized void add(String s) {
        if (LINES.size() >= MAX) LINES.pollFirst();
        LINES.addLast(FMT.format(new Date()) + "  " + s);
    }

    public static synchronized void clear() {
        LINES.clear();
    }

    public static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
