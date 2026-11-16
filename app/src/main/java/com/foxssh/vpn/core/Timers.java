package com.foxssh.vpn.core;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

/** یک زمان‌بند مشترک برای تایمرهای کوتاه (watchdog، keepalive). */
final class Timers {
    static final ScheduledThreadPoolExecutor EX = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "fox-timers");
            t.setDaemon(true);
            return t;
        }
    });

    static {
        EX.setRemoveOnCancelPolicy(true);
    }

    private Timers() {
    }
}
