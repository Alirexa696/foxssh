package com.foxssh.vpn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.foxssh.vpn.core.CidrSet;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class BypassActivity extends Activity {
    private Prefs prefs;
    private TextView listInfo;
    private LinearLayout entries;
    private EditText entry;
    private Button update;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_bypass);
        prefs = new Prefs(this);
        listInfo = findViewById(R.id.listInfo);
        entries = findViewById(R.id.entries);
        entry = findViewById(R.id.entry);
        update = findViewById(R.id.update);

        Switch sw = findViewById(R.id.enable);
        sw.setChecked(prefs.bypassEnabled());
        sw.setOnCheckedChangeListener((v, on) -> {
            prefs.setBypassEnabled(on);
            reloadService();
        });

        findViewById(R.id.add).setOnClickListener(v -> {
            String err = prefs.addManual(entry.getText().toString());
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
                return;
            }
            entry.setText("");
            renderEntries();
            reloadService();
        });

        update.setOnClickListener(v -> {
            update.setEnabled(false);
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String msg;
                try {
                    int n = IranList.update(app);
                    msg = "OK: " + n;
                } catch (Exception e) {
                    msg = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String m = msg;
                runOnUiThread(() -> {
                    update.setEnabled(true);
                    Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                    renderInfo();
                    reloadService();
                });
            }).start();
        });

        renderInfo();
        renderEntries();
    }

    private void renderInfo() {
        long t = IranList.lastUpdate(this);
        String when = t == 0 ? "نسخهٔ داخل برنامه"
                : DateFormat.getDateTimeInstance().format(new Date(t));
        listInfo.setText("لیست ایران: " + when);
        new Thread(() -> {
            final CidrSet s = IranList.load(getApplicationContext());
            runOnUiThread(() -> listInfo.setText(listInfo.getText() + "  (" + s.size() + " رنج)"));
        }).start();
    }

    private void renderEntries() {
        entries.removeAllViews();
        List<String> l = prefs.manualEntries();
        if (l.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(R.string.no_entries);
            t.setTextColor(getColor(R.color.muted));
            t.setPadding(0, 12, 0, 0);
            entries.addView(t);
            return;
        }
        for (final String e : l) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_entry, entries, false);
            ((TextView) row.findViewById(R.id.text)).setText(e);
            row.findViewById(R.id.del).setOnClickListener(v -> {
                prefs.removeManual(e);
                renderEntries();
                reloadService();
            });
            entries.addView(row);
        }
    }

    /** اگر تونل فعال است، قوانین را همان لحظه دوباره بارگذاری می‌کند. */
    private void reloadService() {
        if (VpnState.active()) {
            startService(new Intent(this, VpnTunnelService.class).setAction(VpnTunnelService.ACTION_RELOAD));
        }
    }
}
