package com.foxssh.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity implements VpnState.Listener {
    private static final int REQ_VPN = 100;

    private Prefs prefs;
    private EditText host, port, user, pass, udpgw;
    private CheckBox remember;
    private TextView status, stats, error;
    private Button connect, banner;
    private AlertDialog bannerDialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        host = findViewById(R.id.host);
        port = findViewById(R.id.port);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        udpgw = findViewById(R.id.udpgw);
        remember = findViewById(R.id.remember);
        status = findViewById(R.id.status);
        stats = findViewById(R.id.stats);
        error = findViewById(R.id.error);
        connect = findViewById(R.id.connect);
        banner = findViewById(R.id.banner);

        host.setText(prefs.host());
        port.setText(String.valueOf(prefs.port()));
        user.setText(prefs.user());
        pass.setText(prefs.pass());
        udpgw.setText(String.valueOf(prefs.udpgwPort()));
        remember.setChecked(prefs.remember());

        connect.setOnClickListener(v -> {
            if (VpnState.active()) {
                startService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_DISCONNECT));
            } else {
                tryConnect();
            }
        });
        banner.setOnClickListener(v -> showBanner());
        findViewById(R.id.rules).setOnClickListener(v -> startActivity(new Intent(this, BypassActivity.class)));

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        if (IranList.needsUpdate(this)) {
            new Thread(() -> {
                try {
                    IranList.update(getApplicationContext());
                } catch (Exception ignored) {
                }
            }).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        VpnState.add(this);
        render();
    }

    @Override
    protected void onPause() {
        VpnState.remove(this);
        super.onPause();
    }

    private void tryConnect() {
        String h = host.getText().toString().trim();
        String u = user.getText().toString().trim();
        String p = pass.getText().toString();
        if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, R.string.err_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.saveLogin(h, toInt(port, 22), u, p, remember.isChecked(), toInt(udpgw, 7300));
        Intent i = VpnService.prepare(this);
        if (i != null) {
            startActivityForResult(i, REQ_VPN);
        } else {
            startVpn();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) startVpn();
            else Toast.makeText(this, R.string.err_vpn_permission, Toast.LENGTH_SHORT).show();
        }
    }

    private void startVpn() {
        Intent i = new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_CONNECT);
        startForegroundService(i);
    }

    @Override
    public void onVpnStateChanged() {
        render();
    }

    private void render() {
        int s = VpnState.status;
        boolean active = s != VpnState.DISCONNECTED;
        int res = s == VpnState.CONNECTED ? R.string.st_connected
                : s == VpnState.CONNECTING ? R.string.st_connecting
                : s == VpnState.RECONNECTING ? R.string.st_reconnecting : R.string.st_disconnected;
        status.setText(res);
        status.setTextColor(s == VpnState.CONNECTED ? getColor(R.color.ok)
                : active ? getColor(R.color.accent) : getColor(R.color.muted));
        connect.setText(active ? R.string.disconnect : R.string.connect);
        for (EditText e : new EditText[]{host, port, user, pass, udpgw}) e.setEnabled(!active);

        String err = VpnState.error;
        error.setVisibility(!active && !err.isEmpty() ? TextView.VISIBLE : TextView.GONE);
        error.setText(err);

        if (s == VpnState.CONNECTED && VpnState.since > 0) {
            long sec = (System.currentTimeMillis() - VpnState.since) / 1000;
            stats.setText(String.format(Locale.US, "%s: %02d:%02d:%02d\n%s: ↑ %s   ↓ %s",
                    getString(R.string.uptime), sec / 3600, (sec / 60) % 60, sec % 60,
                    getString(R.string.traffic_tunnel), fmt(VpnState.tx), fmt(VpnState.rx)));
        } else {
            stats.setText("");
        }

        boolean hasBanner = !clean(VpnState.banner).isEmpty();
        banner.setVisibility(s == VpnState.CONNECTED && hasBanner ? Button.VISIBLE : Button.GONE);
        if (s == VpnState.CONNECTED && hasBanner && !VpnState.bannerShown) {
            VpnState.bannerShown = true;
            showBanner();
        }
    }

    private void showBanner() {
        String t = clean(VpnState.banner);
        if (t.isEmpty() || isFinishing()) return;
        if (bannerDialog != null && bannerDialog.isShowing()) bannerDialog.dismiss();
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setPadding(48, 32, 48, 8);
        tv.setTextIsSelectable(true);
        bannerDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.banner_title)
                .setView(tv)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** حذف کدهای کنترلی/رنگ ترمینال از بنر. */
    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").replace("\r", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").trim();
    }

    private static int toInt(EditText e, int def) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v >= 0 && v < 65536 ? v : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        return String.format(Locale.US, "%.2f GB", v / 1024);
    }
}
