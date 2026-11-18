package com.foxssh.vpn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.foxssh.vpn.core.CidrSet;

public final class SettingsActivity extends Activity {
    private Prefs prefs;
    private EditText dns1, dns2;
    private Switch ipv6, bypass;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        dns1 = findViewById(R.id.dns1);
        dns2 = findViewById(R.id.dns2);
        ipv6 = findViewById(R.id.ipv6);
        bypass = findViewById(R.id.bypass);

        dns1.setText(prefs.dns1());
        dns2.setText(prefs.dns2());
        ipv6.setChecked(prefs.ipv6());
        bypass.setChecked(prefs.bypassEnabled());

        findViewById(R.id.manageBypass).setOnClickListener(v -> startActivity(new Intent(this, BypassActivity.class)));
        findViewById(R.id.viewLog).setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        findViewById(R.id.forgetKeys).setOnClickListener(v -> {
            prefs.forgetAllHostKeys();
            Toast.makeText(this, R.string.keys_cleared, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.saveSettings).setOnClickListener(v -> save());
    }

    private void save() {
        String a = dns1.getText().toString().trim();
        String c = dns2.getText().toString().trim();
        if (!validIp(a) || (!c.isEmpty() && !validIp(c))) {
            Toast.makeText(this, "DNS نامعتبر است (مثلاً 1.1.1.1)", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setDns(a, c);
        prefs.setIpv6(ipv6.isChecked());
        prefs.setBypassEnabled(bypass.isChecked());
        if (VpnState.active()) {
            startService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_RELOAD));
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private static boolean validIp(String s) {
        long v = CidrSet.parseIp(s);
        return v >= 0;
    }
}
