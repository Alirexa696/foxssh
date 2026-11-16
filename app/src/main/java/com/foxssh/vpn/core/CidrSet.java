package com.foxssh.vpn.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * مجموعهٔ ثابت از بازه‌های IPv4 (مرتب و ادغام‌شده) با جستجوی دودویی.
 * ورودی‌های قابل قبول: "1.2.3.4"، "1.2.3.0/24"، "1.2.3.4-1.2.3.9".
 */
public final class CidrSet {
    public static final CidrSet EMPTY = new CidrSet(new long[0], new long[0]);

    private final long[] starts;
    private final long[] ends;

    private CidrSet(long[] starts, long[] ends) {
        this.starts = starts;
        this.ends = ends;
    }

    /** تعداد بازه‌های ادغام‌شده */
    public int size() {
        return starts.length;
    }

    public boolean contains(long ip) {
        int lo = 0, hi = starts.length - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= ip) {
                idx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return idx >= 0 && ip <= ends[idx];
    }

    public boolean contains(byte[] ip4) {
        if (ip4 == null || ip4.length != 4) return false;
        return contains(toLong(ip4));
    }

    public static long toLong(byte[] b) {
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }

    /** نشانی IPv4 نقطه‌دار → عدد؛ در صورت نامعتبر بودن -1 */
    public static long parseIp(String s) {
        if (s == null) return -1;
        String[] p = s.trim().split("\\.", -1);
        if (p.length != 4) return -1;
        long v = 0;
        for (String part : p) {
            if (part.isEmpty() || part.length() > 3) return -1;
            int n = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return -1;
                n = n * 10 + (c - '0');
            }
            if (n > 255) return -1;
            v = (v << 8) | n;
        }
        return v;
    }

    public static final class Builder {
        private final List<long[]> ranges = new ArrayList<>();

        /** یک خط متنی را اضافه می‌کند؛ برای خط نامعتبر/IPv6/کامنت false برمی‌گرداند. */
        public boolean addEntry(String text) {
            if (text == null) return false;
            String t = text;
            int c = t.indexOf('#');
            if (c >= 0) t = t.substring(0, c);
            c = t.indexOf(';');
            if (c >= 0) t = t.substring(0, c);
            t = t.trim();
            if (t.isEmpty() || t.indexOf(':') >= 0) return false;

            int slash = t.indexOf('/');
            if (slash >= 0) {
                long ip = parseIp(t.substring(0, slash));
                if (ip < 0) return false;
                String ps = t.substring(slash + 1).trim();
                if (ps.isEmpty() || ps.length() > 2) return false;
                int prefix;
                try {
                    prefix = Integer.parseInt(ps);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (prefix < 0 || prefix > 32) return false;
                long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long start = ip & mask;
                long end = start | (~mask & 0xFFFFFFFFL);
                ranges.add(new long[]{start, end});
                return true;
            }
            int dash = t.indexOf('-');
            if (dash >= 0) {
                long a = parseIp(t.substring(0, dash));
                long b = parseIp(t.substring(dash + 1));
                if (a < 0 || b < 0 || a > b) return false;
                ranges.add(new long[]{a, b});
                return true;
            }
            long ip = parseIp(t);
            if (ip < 0) return false;
            ranges.add(new long[]{ip, ip});
            return true;
        }

        public CidrSet build() {
            if (ranges.isEmpty()) return EMPTY;
            Collections.sort(ranges, new Comparator<long[]>() {
                @Override
                public int compare(long[] x, long[] y) {
                    return Long.compare(x[0], y[0]);
                }
            });
            List<long[]> merged = new ArrayList<>();
            long[] cur = ranges.get(0).clone();
            for (int i = 1; i < ranges.size(); i++) {
                long[] r = ranges.get(i);
                if (r[0] <= cur[1] + 1) {
                    if (r[1] > cur[1]) cur[1] = r[1];
                } else {
                    merged.add(cur);
                    cur = r.clone();
                }
            }
            merged.add(cur);
            long[] s = new long[merged.size()];
            long[] e = new long[merged.size()];
            for (int i = 0; i < s.length; i++) {
                s[i] = merged.get(i)[0];
                e[i] = merged.get(i)[1];
            }
            return new CidrSet(s, e);
        }
    }

    /** ساخت از چند خط متن؛ تعداد خط‌های معتبر در validOut[0] نوشته می‌شود (اگر null نباشد). */
    public static CidrSet parseLines(Iterable<String> lines, int[] validOut) {
        Builder b = new Builder();
        int ok = 0;
        for (String l : lines) {
            if (b.addEntry(l)) ok++;
        }
        if (validOut != null && validOut.length > 0) validOut[0] = ok;
        return b.build();
    }
}
