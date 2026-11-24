package com.foxssh.vpn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.text.InputType;
import android.widget.EditText;
import java.util.Locale;

/** صفحهٔ خانه: انتخاب کانفیگ، اتصال و وضعیت. */
public final class MainActivity extends Activity implements VpnState.Listener {
    private static final int REQ_VPN = 100;

    private Prefs prefs;
    private TextView status, stats, error, proto, ipValue, ipLabel, country;
    private PowerView power;
    private ImageView flag, statusCheck;
    private View glow;
    private TextView profName, profSub;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> flags = new HashMap<>();
    private int lastStatus = -1;
    private int ipGen;
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
        power = findViewById(R.id.power);
        proto = findViewById(R.id.proto);
        ipValue = findViewById(R.id.ipValue);
        ipLabel = findViewById(R.id.ipLabel);
        country = findViewById(R.id.country);
        flag = findViewById(R.id.flag);
        statusCheck = findViewById(R.id.statusCheck);
        glow = findViewById(R.id.glow);
        profName = findViewById(R.id.profName);
        profSub = findViewById(R.id.profSub);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        findViewById(R.id.menuBtn).setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, v);
            m.getMenu().add(0, 1, 0, R.string.menu_configs);
            m.getMenu().add(0, 2, 1, R.string.menu_settings);
            m.getMenu().add(0, 3, 2, R.string.menu_logs);
            m.setOnMenuItemClickListener(it -> {
                switch (it.getItemId()) {
                    case 1: startActivity(new Intent(this, ProfilesActivity.class)); break;
                    case 2: startActivity(new Intent(this, SettingsActivity.class)); break;
                    default: startActivity(new Intent(this, LogActivity.class));
                }
                return true;
            });
            m.show();
        });
        findViewById(R.id.logsBar).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.profileSelect).setOnClickListener(v -> chooseProfile());
        findViewById(R.id.eye).setOnClickListener(v -> {
            passVisible = !passVisible;
            int sel = pass.getSelectionEnd();
            pass.setInputType(InputType.TYPE_CLASS_TEXT
                    | (passVisible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setSelection(Math.max(0, sel));
        });

        power.setOnClickListener(v -> {
            if (VpnState.active()) {
                startService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_DISCONNECT));
            } else {
                tryConnect();
            }
        });
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
            proto.setText("SSH  |  22");
            user.setText("");
            pass.setText("");
            return;
        }
        profName.setText(p.title());
        profSub.setText(p.host + ":" + p.port);
        proto.setText("SSH  |  " + p.port);
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
        power.setState(s == VpnState.CONNECTED ? PowerView.ON : active ? PowerView.BUSY : PowerView.OFF);
        statusCheck.setVisibility(s == VpnState.CONNECTED ? View.VISIBLE : View.GONE);
        glow.setBackgroundResource(s == VpnState.CONNECTED ? R.drawable.glow_ok
                : active ? R.drawable.glow_busy : R.drawable.glow_off);
        ipLabel.setText(s == VpnState.CONNECTED ? R.string.your_ip_vpn : R.string.your_ip);
        user.setEnabled(!active);
        pass.setEnabled(!active);
        if (s != lastStatus) {
            lastStatus = s;
            refreshIp(s);
        }

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
        if (s == VpnState.CONNECTED && hasBanner && !VpnState.bannerShown) {
            VpnState.bannerShown = true;
            showBanner();
        }
    }

    /** IP و پرچم: وصل = از داخل تونل (IP سرور)، قطع = IP واقعی. */
    private void refreshIp(int s) {
        final int gen = ++ipGen;
        ipValue.setText("…");
        flag.setVisibility(View.GONE);
        country.setText("");
        if (s == VpnState.CONNECTING || s == VpnState.RECONNECTING) return;
        final int port = s == VpnState.CONNECTED ? VpnState.socksPort : 0;
        final boolean viaTunnel = port > 0;
        ui.postDelayed(() -> new Thread(() -> {
            final String[] r = IpLookup.fetch(port);
            runOnUiThread(() -> {
                if (gen != ipGen || isFinishing()) return;
                if (s == VpnState.CONNECTED && !viaTunnel) return;
                if (r == null) {
                    ipValue.setText(R.string.unknown_ip);
                    return;
                }
                ipValue.setText(r[0]);
                showCountry(r[1]);
            });
        }).start(), s == VpnState.CONNECTED ? 600 : 1200);
    }

    private void showCountry(String cc) {
        if (cc == null || cc.length() != 2) return;
        Bitmap bmp = flags.get(cc);
        if (bmp == null) {
            try (InputStream in = getAssets().open("flags/" + cc.toLowerCase(Locale.ROOT) + ".png")) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Exception ignored) {
            }
            if (bmp != null) flags.put(cc, bmp);
        }
        if (bmp != null) {
            flag.setImageBitmap(bmp);
            flag.setVisibility(View.VISIBLE);
        }
        String n = new Locale("", cc).getDisplayCountry(new Locale("fa"));
        country.setText(n.isEmpty() ? cc : n);
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
