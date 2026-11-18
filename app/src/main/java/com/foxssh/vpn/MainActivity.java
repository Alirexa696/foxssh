package com.foxssh.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import android.text.InputType;
import android.widget.EditText;
import java.util.Locale;

/** صفحهٔ خانه: انتخاب کانفیگ، اتصال و وضعیت. */
public final class MainActivity extends Activity implements VpnState.Listener {
    private static final int REQ_VPN = 100;

    private Prefs prefs;
    private TextView status, stats, error;
    private Button connect, banner;
    private TextView profName, profSub;
    private EditText user, pass;
    private boolean passVisible;
    private AlertDialog bannerDialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        status = findViewById(R.id.status);
        stats = findViewById(R.id.stats);
        error = findViewById(R.id.error);
        connect = findViewById(R.id.connect);
        banner = findViewById(R.id.banner);
        profName = findViewById(R.id.profName);
        profSub = findViewById(R.id.profSub);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        Bar.setup(this, R.string.app_name, false, true);
        Bar.action1(this, R.drawable.ic_settings, () -> startActivity(new Intent(this, SettingsActivity.class)));
        Bar.action2(this, R.drawable.ic_list, () -> startActivity(new Intent(this, ProfilesActivity.class)));
        findViewById(R.id.fabLog).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.profileSelect).setOnClickListener(v -> chooseProfile());
        findViewById(R.id.eye).setOnClickListener(v -> {
            passVisible = !passVisible;
            int sel = pass.getSelectionEnd();
            pass.setInputType(InputType.TYPE_CLASS_TEXT
                    | (passVisible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setSelection(Math.max(0, sel));
        });

        connect.setOnClickListener(v -> {
            if (VpnState.active()) {
                startService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_DISCONNECT));
            } else {
                tryConnect();
            }
        });
        banner.setOnClickListener(v -> showBanner());
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
        renderProfiles();
        render();
    }

    @Override
    protected void onPause() {
        VpnState.remove(this);
        super.onPause();
    }

    private void renderProfiles() {
        Profile p = prefs.selected();
        if (p == null) {
            profName.setText(R.string.no_profile_selected);
            profSub.setText(R.string.tap_to_add);
            user.setText("");
            pass.setText("");
            return;
        }
        profName.setText(p.title());
        profSub.setText("SSH  •  " + p.host + ":" + p.port);
        user.setText(p.user);
        pass.setText(p.pass);
    }

    private void chooseProfile() {
        if (VpnState.active()) {
            Toast.makeText(this, R.string.active_lock, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Profile> l = prefs.profiles();
        if (l.isEmpty()) {
            startActivity(new Intent(this, ProfileEditActivity.class));
            return;
        }
        String[] names = new String[l.size()];
        int checked = 0;
        Profile sel = prefs.selected();
        for (int i = 0; i < l.size(); i++) {
            names[i] = l.get(i).title();
            if (sel != null && sel.id.equals(l.get(i).id)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.select_profile)
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    prefs.setSelected(l.get(which).id);
                    renderProfiles();
                    d.dismiss();
                })
                .show();
    }

    private void tryConnect() {
        Profile cur = prefs.selected();
        if (cur == null) {
            Toast.makeText(this, R.string.select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        cur.user = user.getText().toString().trim();
        cur.pass = pass.getText().toString();
        if (cur.user.isEmpty() || cur.pass.isEmpty()) {
            Toast.makeText(this, R.string.err_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.saveProfile(cur);
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
        startForegroundService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_CONNECT));
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
        user.setEnabled(!active);
        pass.setEnabled(!active);

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

    private static String fmt(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        return String.format(Locale.US, "%.2f GB", v / 1024);
    }
}
