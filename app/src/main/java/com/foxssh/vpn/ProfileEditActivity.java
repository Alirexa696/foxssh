package com.foxssh.vpn;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public final class ProfileEditActivity extends Activity {
    private Prefs prefs;
    private Profile profile;
    private EditText name, host, port, user, pass, udpgw;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_profile);
        prefs = new Prefs(this);
        name = findViewById(R.id.name);
        host = findViewById(R.id.host);
        port = findViewById(R.id.port);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        udpgw = findViewById(R.id.udpgw);

        String id = getIntent().getStringExtra("id");
        profile = id == null ? null : prefs.profile(id);
        TextView title = findViewById(R.id.title);
        if (profile != null) {
            title.setText(R.string.edit_profile);
            name.setText(profile.name);
            host.setText(profile.host);
            port.setText(String.valueOf(profile.port));
            user.setText(profile.user);
            pass.setText(profile.pass);
            udpgw.setText(String.valueOf(profile.udpgw));
        } else {
            title.setText(R.string.new_profile);
            port.setText("22");
            udpgw.setText("7300");
            findViewById(R.id.delete).setVisibility(View.GONE);
        }

        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.delete).setOnClickListener(v -> {
            if (profile != null) prefs.deleteProfile(profile.id);
            finish();
        });
    }

    private void save() {
        String h = host.getText().toString().trim();
        String u = user.getText().toString().trim();
        String p = pass.getText().toString();
        if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
            Toast.makeText(this, R.string.err_fields, Toast.LENGTH_SHORT).show();
            return;
        }
        Profile n = profile != null ? profile : new Profile();
        n.name = name.getText().toString().trim();
        n.host = h;
        n.port = toInt(port, 22);
        n.user = u;
        n.pass = p;
        n.udpgw = toInt(udpgw, 7300);
        prefs.saveProfile(n);
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private static int toInt(EditText e, int def) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v >= 0 && v < 65536 ? v : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
