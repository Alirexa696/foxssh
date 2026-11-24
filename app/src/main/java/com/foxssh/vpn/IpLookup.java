package com.foxssh.vpn;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** پیدا کردن IP عمومی و کشور آن. با socksPort>0 از داخل تونل SSH می‌پرسد (IP سرور VPN). */
final class IpLookup {
    private static final Pattern IP = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private IpLookup() {}

    /** @return {ip, کد دو حرفی کشور یا ""} ؛ null اگر نشد. */
    static String[] fetch(int socksPort) {
        Proxy proxy = socksPort > 0
                ? new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort))
                : Proxy.NO_PROXY;
        String ip = null;
        for (String u : new String[]{"https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"}) {
            String s = get(u, proxy);
            if (s != null && IP.matcher(s.trim()).matches()) {
                ip = s.trim();
                break;
            }
        }
        if (ip == null) return null;
        String cc = "";
        for (String u : new String[]{"https://ipinfo.io/" + ip + "/country", "https://api.country.is/" + ip}) {
            String s = get(u, proxy);
            if (s == null) continue;
            Matcher m = Pattern.compile("^([A-Za-z]{2})$|\"country\"\\s*:\\s*\"([A-Za-z]{2})\"").matcher(s.trim());
            if (m.find()) {
                cc = (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase(Locale.ROOT);
                break;
            }
        }
        return new String[]{ip, cc};
    }

    private static String get(String url, Proxy proxy) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection(proxy);
            h.setConnectTimeout(6000);
            h.setReadTimeout(6000);
            h.setRequestProperty("User-Agent", "curl/8");
            if (h.getResponseCode() != 200) return null;
            InputStream in = h.getInputStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0 && o.size() < 512) o.write(b, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
