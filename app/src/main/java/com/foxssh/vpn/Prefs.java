package com.foxssh.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import com.foxssh.vpn.core.BypassRules;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** تنظیمات و پروفایل‌های ذخیره‌شده. */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("fox", Context.MODE_PRIVATE);
    }

    // ───── پروفایل‌ها ─────

    public List<Profile> profiles() {
        List<Profile> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Profile.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void saveProfiles(List<Profile> l) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : l) a.put(p.toJson());
        } catch (JSONException ignored) {
        }
        sp.edit().putString("profiles", a.toString()).apply();
    }

    public Profile profile(String id) {
        for (Profile p : profiles()) if (p.id.equals(id)) return p;
        return null;
    }

    public void saveProfile(Profile p) {
        List<Profile> l = profiles();
        if (p.id.isEmpty()) {
            p.id = UUID.randomUUID().toString();
            l.add(p);
        } else {
            boolean found = false;
            for (int i = 0; i < l.size(); i++) {
                if (l.get(i).id.equals(p.id)) {
                    l.set(i, p);
                    found = true;
                }
            }
            if (!found) l.add(p);
        }
        saveProfiles(l);
        if (selectedId().isEmpty() || profile(selectedId()) == null) setSelected(p.id);
    }

    public void deleteProfile(String id) {
        List<Profile> l = profiles();
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(id)) l.remove(i);
        saveProfiles(l);
        if (selectedId().equals(id)) setSelected(l.isEmpty() ? "" : l.get(0).id);
    }

    public String selectedId() { return sp.getString("selected", ""); }
    public void setSelected(String id) { sp.edit().putString("selected", id).apply(); }

    public Profile selected() {
        Profile p = profile(selectedId());
        if (p == null) {
            List<Profile> l = profiles();
            if (!l.isEmpty()) p = l.get(0);
        }
        return p;
    }

    // ───── تنظیمات ─────

    public boolean bypassEnabled() { return sp.getBoolean("bypass", true); }
    public void setBypassEnabled(boolean v) { sp.edit().putBoolean("bypass", v).apply(); }

    public String dns1() { return sp.getString("dns1", "1.1.1.1"); }
    public String dns2() { return sp.getString("dns2", "8.8.8.8"); }
    public void setDns(String a, String b) { sp.edit().putString("dns1", a).putString("dns2", b).apply(); }

    public boolean ipv6() { return sp.getBoolean("ipv6", false); }
    public void setIpv6(boolean v) { sp.edit().putBoolean("ipv6", v).apply(); }

    // ───── ورودی‌های دستی (IP / CIDR / دامنه) ─────

    public List<String> manualEntries() {
        List<String> out = new ArrayList<>();
        for (String line : sp.getString("manual", "").split("\n")) {
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

    public void forgetAllHostKeys() {
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.startsWith("hk:")) e.remove(k);
        e.apply();
    }
}
