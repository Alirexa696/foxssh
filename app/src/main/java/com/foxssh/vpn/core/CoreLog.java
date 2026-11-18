package com.foxssh.vpn.core;

/** لاگ سبک هسته؛ برنامه یک sink وصل می‌کند تا در صفحهٔ لاگ دیده شود. */
public final class CoreLog {
    public interface Sink {
        void log(String line);
    }

    public static volatile Sink sink;

    private CoreLog() {}

    public static void log(String s) {
        Sink k = sink;
        if (k != null) k.log(s);
    }
}
