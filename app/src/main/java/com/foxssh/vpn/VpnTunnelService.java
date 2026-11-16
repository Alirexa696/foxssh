package com.foxssh.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.foxssh.vpn.core.BypassRules;
import com.foxssh.vpn.core.OpenerSource;
import com.foxssh.vpn.core.ChannelOpener;
import com.foxssh.vpn.core.Socks5Server;
import com.foxssh.vpn.core.UdpGwMux;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import hev.htproxy.TProxyService;

public final class VpnTunnelService extends VpnService {
    public static final String ACTION_CONNECT = "com.foxssh.vpn.CONNECT";
    public static final String ACTION_DISCONNECT = "com.foxssh.vpn.DISCONNECT";
    public static final String ACTION_RELOAD = "com.foxssh.vpn.RELOAD";
    private static final String CHANNEL = "fox_vpn";
    private static final int NOTIF_ID = 1;

    private volatile boolean running;
    private Thread worker;
    private volatile SshTunnel tunnel;
    private volatile BypassRules rules;
    private Socks5Server socks;
    private UdpGwMux mux;
    private ParcelFileDescriptor tun;
    private TProxyService hev;
    private final AtomicLong tx = new AtomicLong(), rx = new AtomicLong();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_DISCONNECT.equals(a)) {
            running = false;
            if (worker != null) worker.interrupt();
            if (worker == null) {
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(a)) {
            final BypassRules r = rules;
            if (r != null) new Thread(() -> loadRules(r)).start();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        startFg(getString(R.string.notif_connecting));
        running = true;
        worker = new Thread(this::runTunnel, "fox-vpn");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    // ───────────── اجرای اصلی ─────────────

    private void runTunnel() {
        Prefs p = new Prefs(this);
        VpnState.error = "";
        VpnState.banner = "";
        VpnState.bannerShown = false;
        VpnState.tx = VpnState.rx = 0;
        setStatus(VpnState.CONNECTING);
        try {
            BypassRules r = new BypassRules();
            r.setEnabled(p.bypassEnabled());
            loadRules(r);
            rules = r;

            SshTunnel t = new SshTunnel(p, tx, rx);
            t.connect(p.host(), p.port(), p.user(), p.pass());
            tunnel = t;
            VpnState.banner = t.banner();

            OpenerSource src = () -> {
                SshTunnel c = tunnel;
                return c != null && c.isAlive() ? (ChannelOpener) c : null;
            };
            mux = new UdpGwMux(src, p.udpgwPort(), 200);
            socks = new Socks5Server(src, r, mux);
            int port = socks.start();

            Builder b = new Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(1500)
                    .addAddress("26.26.26.1", 30)
                    .addAddress("da26:2626::1", 126)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setBlocking(false);
            try {
                b.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {
            }
            tun = b.establish();
            if (tun == null) throw new IOException(getString(R.string.err_vpn_permission));

            File cfg = new File(getFilesDir(), "hev.yml");
            try (FileOutputStream o = new FileOutputStream(cfg)) {
                o.write(hevConfig(port).getBytes("UTF-8"));
            }
            hev = new TProxyService();
            if (!hev.TProxyStartService(cfg.getAbsolutePath(), tun.getFd())) {
                throw new IOException("tun2socks");
            }

            VpnState.since = System.currentTimeMillis();
            setStatus(VpnState.CONNECTED);
            updateNotif(getString(R.string.notif_connected));

            monitor(p);
        } catch (InterruptedException e) {
            // قطع توسط کاربر
        } catch (Exception e) {
            VpnState.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            cleanup();
        }
    }

    private void monitor(Prefs p) throws InterruptedException {
        int backoff = 2;
        int tick = 0;
        while (running) {
            Thread.sleep(1000);
            VpnState.tx = tx.get();
            VpnState.rx = rx.get();
            tick++;
            if (tick % 600 == 0) {
                BypassRules r = rules;
                if (r != null) r.resolveDomains();
            }
            SshTunnel cur = tunnel;
            if (cur != null && cur.isAlive()) {
                if (VpnState.status != VpnState.CONNECTED) {
                    backoff = 2;
                    setStatus(VpnState.CONNECTED);
                    updateNotif(getString(R.string.notif_connected));
                }
                VpnState.changed();
                continue;
            }
            setStatus(VpnState.RECONNECTING);
            updateNotif(getString(R.string.notif_reconnecting));
            SshTunnel n = new SshTunnel(p, tx, rx);
            try {
                n.connect(p.host(), p.port(), p.user(), p.pass());
                tunnel = n;
                if (cur != null) cur.close();
                if (!n.banner().isEmpty() && VpnState.banner.isEmpty()) VpnState.banner = n.banner();
            } catch (Exception e) {
                n.close();
                for (int i = 0; i < backoff && running; i++) Thread.sleep(1000);
                backoff = Math.min(backoff * 2, 30);
            }
        }
    }

    private void loadRules(BypassRules r) {
        Prefs p = new Prefs(this);
        r.setEnabled(p.bypassEnabled());
        r.setIranList(IranList.load(this));
        r.setManualEntries(p.manualEntries());
        r.resolveDomains();
    }

    private String hevConfig(int socksPort) {
        return "tunnel:\n  mtu: 1500\n  ipv4: 26.26.26.1\n  ipv6: 'da26:2626::1'\n"
                + "socks5:\n  port: " + socksPort + "\n  address: 127.0.0.1\n  udp: 'udp'\n"
                + "misc:\n  task-stack-size: 81920\n  tcp-buffer-size: 65536\n  connect-timeout: 15000\n"
                + "  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n  log-level: warn\n";
    }

    private void cleanup() {
        running = false;
        try {
            if (hev != null) hev.TProxyStopService();
        } catch (Throwable ignored) {
        }
        hev = null;
        try {
            if (tun != null) tun.close();
        } catch (IOException ignored) {
        }
        tun = null;
        if (socks != null) socks.close();
        if (mux != null) mux.close();
        SshTunnel t = tunnel;
        tunnel = null;
        if (t != null) t.close();
        VpnState.since = 0;
        setStatus(VpnState.DISCONNECTED);
        worker = null;
        stopForeground(true);
        stopSelf();
    }

    private void setStatus(int s) {
        VpnState.status = s;
        VpnState.changed();
    }

    // ───────────── اعلان ─────────────

    private Notification build(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent d = new Intent(this, VpnTunnelService.class).setAction(ACTION_DISCONNECT);
        PendingIntent stop = PendingIntent.getService(this, 1, d, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
                .build();
    }

    private void startFg(String text) {
        Notification n = build(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateNotif(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, build(text));
    }
}
