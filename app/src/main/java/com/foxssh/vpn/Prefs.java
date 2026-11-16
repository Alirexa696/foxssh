package com.foxssh.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import com.foxssh.vpn.core.BypassRules;

import java.util.ArrayList;
import java.util.List;

/** تنظیمات ذخیره‌شدهٔ برنامه. */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("fox", Context.MODE_PRIVATE);
    }

    public String host() { return sp.getString("host", ""); }
    public int port() { return sp.getInt("port", 22); }
    public String user() { return sp.getString("user", ""); }
    public String pass() { return sp.getString("pass", ""); }
    public boolean remember() { return sp.getBoolean("remember", true); }
    public int udpgwPort() { return sp.getInt("udpgw", 7300); }
    public boolean bypassEnabled() { return sp.getBoolean("bypass", true); }

    public void saveLogin(String host, int port, String user, String pass, boolean remember, int udpgw) {
        sp.edit().putString("host", host).putInt("port", port).putString("user", user)
                .putString("pass", remember ? pass : "").putBoolean("remember", remember)
                .putInt("udpgw", udpgw).apply();
    }

    public void setBypassEnabled(boolean v) { sp.edit().putBoolean("bypass", v).apply(); }

    // ───── ورودی‌های دستی (IP / CIDR / دامنه) ─────

    public List<String> manualEntries() {
        List<String> out = new ArrayList<>();
        String s = sp.getString("manual", "");
        for (String line : s.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    /** null یعنی موفق؛ وگرنه متن خطا. */
    public String addManual(String raw) {
        if (BypassRules.classify(raw) == BypassRules.KIND_INVALID) return "ورودی نامعتبر است";
        String n = BypassRules.normalizeEntry(raw);
        List<String> l = manualEntries();
        if (l.contains(n)) return "قبلاً اضافه شده";
        l.add(n);
        saveManual(l);
        return null;
    }

    public void removeManual(String entry) {
        List<String> l = manualEntries();
        l.remove(entry);
        saveManual(l);
    }

    private void saveManual(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String e : l) sb.append(e).append('\n');
        sp.edit().putString("manual", sb.toString()).apply();
    }

    // ───── اثرانگشت کلید هاست (TOFU) ─────

    public String hostKey(String id) { return sp.getString("hk:" + id, null); }
    public void setHostKey(String id, String fp) { sp.edit().putString("hk:" + id, fp).apply(); }
    public void forgetHostKey(String id) { sp.edit().remove("hk:" + id).apply(); }
}
