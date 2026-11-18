package com.foxssh.vpn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** لیست کانفیگ‌ها؛ لمس = انتخاب، ✎ = ویرایش. */
public final class ProfilesActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_profiles);
        prefs = new Prefs(this);
        list = findViewById(R.id.list);
        Bar.setup(this, R.string.profiles, true, false);
        Bar.action1(this, R.drawable.ic_plus, () -> startActivity(new Intent(this, ProfileEditActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        List<Profile> l = prefs.profiles();
        if (l.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(R.string.no_profiles);
            t.setTextColor(getColor(R.color.muted));
            t.setPadding(0, 24, 0, 0);
            list.addView(t);
            return;
        }
        Profile sel = prefs.selected();
        for (final Profile p : l) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_profile, list, false);
            boolean on = sel != null && sel.id.equals(p.id);
            ((TextView) row.findViewById(R.id.dot)).setText(on ? "◉" : "○");
            ((TextView) row.findViewById(R.id.title)).setText(p.title());
            ((TextView) row.findViewById(R.id.sub)).setText("SSH  •  " + p.host + ":" + p.port);
            row.setOnClickListener(v -> {
                prefs.setSelected(p.id);
                finish();
            });
            row.findViewById(R.id.edit).setOnClickListener(v ->
                    startActivity(new Intent(this, ProfileEditActivity.class).putExtra("id", p.id)));
            list.addView(row);
        }
    }
}
