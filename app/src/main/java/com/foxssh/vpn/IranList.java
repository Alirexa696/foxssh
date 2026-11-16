package com.foxssh.vpn;

import android.content.Context;

import com.foxssh.vpn.core.CidrSet;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** لیست IPv4 ایران: دانلود خودکار، ذخیره در حافظهٔ برنامه و نسخهٔ پیش‌فرض داخل APK. */
public final class IranList {
    private static final String[] SOURCES = {
            "https://www.ipdeny.com/ipblocks/data/aggregated/ir-aggregated.zone",
            "https://raw.githubusercontent.com/ipverse/country-ip-blocks/master/country/ir/ipv4-aggregated.txt",
            "https://cdn.jsdelivr.net/gh/ipverse/country-ip-blocks@master/country/ir/ipv4-aggregated.txt",
    };
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final int MIN_VALID = 200;

    private IranList() {}

    private static File file(Context c) { return new File(c.getFilesDir(), "iran_ipv4.txt"); }

    public static long lastUpdate(Context c) {
        File f = file(c);
        return f.exists() ? f.lastModified() : 0;
    }

    public static boolean needsUpdate(Context c) {
        return System.currentTimeMillis() - lastUpdate(c) > MAX_AGE_MS;
    }

    /** نسخهٔ دانلودشده، وگرنه نسخهٔ داخل APK. */
    public static CidrSet load(Context c) {
        File f = file(c);
        if (f.exists()) {
            try (InputStream in = new java.io.FileInputStream(f)) {
                CidrSet s = parse(in);
                if (s.size() >= MIN_VALID) return s;
            } catch (IOException ignored) {
            }
        }
        try (InputStream in = c.getAssets().open("iran_ipv4.txt")) {
            return parse(in);
        } catch (IOException e) {
            return CidrSet.EMPTY;
        }
    }

    private static CidrSet parse(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        List<String> lines = new ArrayList<>();
        String l;
        while ((l = r.readLine()) != null) lines.add(l);
        return CidrSet.parseLines(lines, new int[1]);
    }

    /** از اولین منبع سالم دانلود می‌کند؛ تعداد رنج‌ها را برمی‌گرداند. */
    public static int update(Context c) throws IOException {
        IOException last = null;
        for (String src : SOURCES) {
            try {
                byte[] data = download(src);
                CidrSet s = parse(new java.io.ByteArrayInputStream(data));
                if (s.size() < MIN_VALID) throw new IOException("لیست نامعتبر");
                File tmp = new File(c.getFilesDir(), "iran_ipv4.tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(data);
                }
                File dst = file(c);
                if (!tmp.renameTo(dst)) {
                    dst.delete();
                    if (!tmp.renameTo(dst)) throw new IOException("ذخیره نشد");
                }
                return s.size();
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("منبعی در دسترس نیست");
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        try {
            if (h.getResponseCode() != 200) throw new IOException("HTTP " + h.getResponseCode());
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try (InputStream in = h.getInputStream()) {
                while ((n = in.read(buf)) != -1) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 4 * 1024 * 1024) throw new IOException("فایل بزرگ");
                }
            }
            return bo.toByteArray();
        } finally {
            h.disconnect();
        }
    }
}
