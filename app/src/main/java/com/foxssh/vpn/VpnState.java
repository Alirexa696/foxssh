package com.foxssh.vpn;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** وضعیت مشترک بین سرویس و صفحه‌ها. */
public final class VpnState {
    public static final int DISCONNECTED = 0, CONNECTING = 1, CONNECTED = 2, RECONNECTING = 3;

    public interface Listener {
        void onVpnStateChanged();
    }

    public static volatile int status = DISCONNECTED;
    public static volatile String error = "";
    public static volatile String banner = "";
    public static volatile boolean bannerShown;
    public static volatile long since;
    public static volatile long tx, rx;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private VpnState() {}

    public static boolean active() {
        return status != DISCONNECTED;
    }

    public static void add(Listener l) { LISTENERS.addIfAbsent(l); }
    public static void remove(Listener l) { LISTENERS.remove(l); }

    public static void changed() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onVpnStateChanged();
        });
    }
}
