package com.foxssh.vpn;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

public final class LogActivity extends Activity {
    private TextView log;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_log);
        log = findViewById(R.id.log);
        findViewById(R.id.refresh).setOnClickListener(v -> load());
        findViewById(R.id.copy).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", AppLog.text()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        load();
    }

    private void load() {
        String t = AppLog.text();
        log.setText(t.isEmpty() ? "—" : t);
    }
}
