package com.foxssh.desktop;

import com.foxssh.vpn.core.CidrSet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** لیست IPv4 ایران: نسخهٔ دانلودشده در stateDir، وگرنه نسخهٔ همراه بسته. */
final class IranList {
    private static final String[] SOURCES = {
            "https://www.ipdeny.com/ipblocks/data/aggregated/ir-aggregated.zone",
            "https://raw.githubusercontent.com/ipverse/country-ip-blocks/master/country/ir/ipv4-aggregated.txt",
            "https://cdn.jsdelivr.net/gh/ipverse/country-ip-blocks@master/country/ir/ipv4-aggregated.txt",
    };
    static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final int MIN_VALID = 200;

    private IranList() {}

    static Path file(Path stateDir) { return stateDir.resolve("iran_ipv4.txt"); }

    static long lastUpdate(Path stateDir) {
        try {
            return Files.getLastModifiedTime(file(stateDir)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    static boolean needsUpdate(Path stateDir) {
        return System.currentTimeMillis() - lastUpdate(stateDir) > MAX_AGE_MS;
    }

    static CidrSet load(Path stateDir, Path sharedDir) {
        for (Path p : new Path[]{file(stateDir), sharedDir.resolve("iran_ipv4.txt")}) {
            try {
                CidrSet s = parse(Files.readAllBytes(p));
                if (s.size() >= MIN_VALID) return s;
            } catch (IOException ignored) {
            }
        }
        return CidrSet.EMPTY;
    }

    private static CidrSet parse(byte[] data) {
        List<String> lines = java.util.Arrays.asList(new String(data, StandardCharsets.UTF_8).split("\\r?\\n"));
        return CidrSet.parseLines(lines, new int[1]);
    }

    /** تعداد رنج‌ها را برمی‌گرداند. */
    static int update(Path stateDir) throws IOException {
        IOException last = null;
        for (String src : SOURCES) {
            try {
                byte[] data = download(src);
                CidrSet s = parse(data);
                if (s.size() < MIN_VALID) throw new IOException("لیست نامعتبر");
                Files.createDirectories(stateDir);
                Path tmp = stateDir.resolve("iran_ipv4.tmp");
                Files.write(tmp, data);
                Files.move(tmp, file(stateDir), StandardCopyOption.REPLACE_EXISTING);
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
