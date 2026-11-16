package com.foxssh.vpn.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * تصمیم می‌گیرد مقصد باید مستقیم (بدون تونل) وصل شود یا از تونل SSH.
 * مستقیم: لیست ایران، ورودی‌های دستی (IP/CIDR/دامنه) و شبکهٔ محلی (LAN).
 */
public final class BypassRules {
    public static final int KIND_INVALID = 0;
    public static final int KIND_IP = 1;
    public static final int KIND_DOMAIN = 2;

    private static final CidrSet LAN = buildLan();

    private volatile boolean enabled = true;
    private volatile CidrSet iran = CidrSet.EMPTY;
    private volatile CidrSet manualIps = CidrSet.EMPTY;
    private volatile CidrSet resolved = CidrSet.EMPTY;
    private volatile List<String> domains = Collections.emptyList();

    private static CidrSet buildLan() {
        CidrSet.Builder b = new CidrSet.Builder();
        b.addEntry("10.0.0.0/8");
        b.addEntry("172.16.0.0/12");
        b.addEntry("192.168.0.0/16");
        b.addEntry("169.254.0.0/16");
        return b.build();
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setIranList(CidrSet s) {
        iran = s == null ? CidrSet.EMPTY : s;
    }

    public int iranCount() {
        return iran.size();
    }

    /** نوع یک ورودی دستی را تشخیص می‌دهد. */
    public static int classify(String raw) {
        if (raw == null) return KIND_INVALID;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return KIND_INVALID;
        CidrSet.Builder b = new CidrSet.Builder();
        if (b.addEntry(s)) return KIND_IP;
        if (isDomain(s)) return KIND_DOMAIN;
        return KIND_INVALID;
    }

    static boolean isDomain(String s) {
        if (s.length() > 253 || s.indexOf('.') < 0) return false;
        if (s.startsWith(".") || s.endsWith(".") || s.indexOf("..") >= 0) return false;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean letter = c >= 'a' && c <= 'z';
            boolean digit = c >= '0' && c <= '9';
            if (letter) hasLetter = true;
            if (!(letter || digit || c == '-' || c == '.')) return false;
        }
        if (!hasLetter) return false; // مثل 1.2.3 که نه IP کامل است نه دامنه
        for (String label : s.split("\\.")) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) return false;
        }
        return true;
    }

    /** نرمال‌سازی ورودی دستی برای ذخیره (حروف کوچک، بدون فاصله، بدون http:// و مسیر). */
    public static String normalizeEntry(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        int slash = s.indexOf('/');
        // CIDR مثل 1.2.3.0/24 را حفظ می‌کنیم؛ مسیر URL را حذف می‌کنیم
        if (slash >= 0 && !(isCidrSuffix(s.substring(slash + 1)) && CidrSet.parseIp(s.substring(0, slash)) >= 0)) {
            s = s.substring(0, slash);
        }
        if (s.startsWith("*.")) s = s.substring(2);
        return s.trim();
    }

    private static boolean isCidrSuffix(String x) {
        if (x.isEmpty() || x.length() > 2) return false;
        for (int i = 0; i < x.length(); i++) {
            if (x.charAt(i) < '0' || x.charAt(i) > '9') return false;
        }
        return true;
    }

    /** ورودی‌های دستی را به مجموعهٔ IP و فهرست دامنه‌ها تقسیم می‌کند. */
    public void setManualEntries(Collection<String> entries) {
        CidrSet.Builder ips = new CidrSet.Builder();
        List<String> doms = new ArrayList<>();
        if (entries != null) {
            for (String e : entries) {
                String n = normalizeEntry(e);
                int k = classify(n);
                if (k == KIND_IP) {
                    ips.addEntry(n);
                } else if (k == KIND_DOMAIN && !doms.contains(n)) {
                    doms.add(n);
                }
            }
        }
        manualIps = ips.build();
        domains = Collections.unmodifiableList(doms);
    }

    public List<String> manualDomains() {
        return domains;
    }

    public void setResolvedAddresses(CidrSet s) {
        resolved = s == null ? CidrSet.EMPTY : s;
    }

    /** IPهای فعلی دامنه‌های دستی (و www. آن‌ها) را می‌گیرد. بدون اینترنت خالی می‌ماند. */
    public int resolveDomains() {
        CidrSet.Builder b = new CidrSet.Builder();
        int count = 0;
        for (String d : domains) {
            for (String host : new String[]{d, "www." + d}) {
                try {
                    for (InetAddress a : InetAddress.getAllByName(host)) {
                        if (a instanceof Inet4Address) {
                            b.addEntry(a.getHostAddress());
                            count++;
                        }
                    }
                } catch (Exception ignored) {
                    // دامنهٔ بدون رکورد یا بدون اینترنت
                }
            }
        }
        if (count > 0) resolved = b.build();
        return count;
    }

    /** شبکهٔ محلی همیشه مستقیم است، حتی اگر بایپس ایران خاموش باشد. */
    public boolean shouldBypass(InetAddress addr) {
        if (!(addr instanceof Inet4Address)) return false;
        byte[] raw = addr.getAddress();
        if (LAN.contains(raw)) return true;
        if (!enabled) return false;
        return iran.contains(raw) || manualIps.contains(raw) || resolved.contains(raw);
    }

    public boolean shouldBypassHost(String host) {
        if (!enabled || host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : domains) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }
}
