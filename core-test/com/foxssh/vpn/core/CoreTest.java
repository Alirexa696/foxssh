package com.foxssh.vpn.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * تست هستهٔ بدون وابستگی به اندروید.
 * اجرا: java -cp out-core:out-test com.foxssh.vpn.core.CoreTest <مسیر badvpn-udpgw>
 * «SSH» در تست با سوکت ساده شبیه‌سازی می‌شود، ولی سرور udpgw همان badvpn-udpgw واقعی است.
 */
public final class CoreTest {
    static int passed = 0, failed = 0;

    static void check(boolean ok, String name) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    // ───────────── ابزارهای تست ─────────────

    /** شبیه‌ساز «SSH»: به‌جای direct-tcpip یک سوکت معمولی می‌زند و تعداد بازشدن‌ها را می‌شمرد. */
    static final class FakeSsh implements ChannelOpener, OpenerSource {
        final AtomicInteger opens = new AtomicInteger();
        final Map<Integer, Integer> portMap = new HashMap<>(); // port→port (مثلاً ۵۳ → سرور DNS تست)

        @Override
        public Duplex open(String host, int port) throws IOException {
            opens.incrementAndGet();
            Integer mapped = portMap.get(port);
            return SocketDuplex.connect(host, mapped != null ? mapped : port, 3000);
        }

        @Override
        public ChannelOpener get() {
            return this;
        }
    }

    static ServerSocket tcpEcho() throws IOException {
        final ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    final Socket s;
                    try {
                        s = ss.accept();
                    } catch (IOException e) {
                        return;
                    }
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                InputStream in = s.getInputStream();
                                OutputStream out = s.getOutputStream();
                                byte[] b = new byte[8192];
                                int n;
                                while ((n = in.read(b)) != -1) {
                                    out.write(b, 0, n);
                                    out.flush();
                                }
                                s.shutdownOutput();
                            } catch (IOException ignored) {
                            }
                        }
                    }).start();
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    /** سرور DNS-over-TCP ساختگی: طول ۲بایتی + پرس‌وجو → همان را با پیشوند "R" برمی‌گرداند. */
    static ServerSocket dnsTcp() throws IOException {
        final ServerSocket ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    final Socket s;
                    try {
                        s = ss.accept();
                    } catch (IOException e) {
                        return;
                    }
                    try {
                        DataInputStream in = new DataInputStream(s.getInputStream());
                        int n = in.readUnsignedShort();
                        byte[] q = new byte[n];
                        in.readFully(q);
                        byte[] r = new byte[n + 1];
                        r[0] = 'R';
                        System.arraycopy(q, 0, r, 1, n);
                        OutputStream out = s.getOutputStream();
                        out.write(new byte[]{(byte) (r.length >>> 8), (byte) r.length});
                        out.write(r);
                        out.flush();
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    static DatagramSocket udpEcho() throws IOException {
        final DatagramSocket ds = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] b = new byte[65535];
                DatagramPacket p = new DatagramPacket(b, b.length);
                while (true) {
                    try {
                        p.setLength(b.length);
                        ds.receive(p);
                        byte[] r = new byte[p.getLength() + 5];
                        System.arraycopy("echo:".getBytes(), 0, r, 0, 5);
                        System.arraycopy(b, 0, r, 5, p.getLength());
                        ds.send(new DatagramPacket(r, r.length, p.getSocketAddress()));
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ds;
    }

    static Socket socksConnect(int socksPort, String ip, int port) throws IOException {
        Socket s = new Socket("127.0.0.1", socksPort);
        s.setSoTimeout(8000);
        OutputStream out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());
        out.write(new byte[]{5, 1, 0});
        byte[] g = new byte[2];
        in.readFully(g);
        if (g[0] != 5 || g[1] != 0) throw new IOException("bad greeting");
        byte[] a = InetAddress.getByName(ip).getAddress();
        byte[] req = new byte[10];
        req[0] = 5;
        req[1] = 1;
        req[3] = 1;
        System.arraycopy(a, 0, req, 4, 4);
        req[8] = (byte) (port >>> 8);
        req[9] = (byte) port;
        out.write(req);
        byte[] rep = new byte[10];
        in.readFully(rep);
        if (rep[1] != 0) throw new ConnectException("socks rep=" + rep[1]);
        return s;
    }

    static final class Assoc {
        final Socket control;
        final int relayPort;
        final DatagramSocket udp;

        Assoc(int socksPort) throws IOException {
            control = new Socket("127.0.0.1", socksPort);
            control.setSoTimeout(8000);
            OutputStream out = control.getOutputStream();
            DataInputStream in = new DataInputStream(control.getInputStream());
            out.write(new byte[]{5, 1, 0});
            byte[] g = new byte[2];
            in.readFully(g);
            out.write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
            byte[] rep = new byte[10];
            in.readFully(rep);
            if (rep[1] != 0) throw new IOException("assoc rep=" + rep[1]);
            relayPort = ((rep[8] & 0xFF) << 8) | (rep[9] & 0xFF);
            udp = new DatagramSocket();
            udp.setSoTimeout(6000);
        }

        void send(String ip, int port, byte[] payload) throws IOException {
            byte[] a = InetAddress.getByName(ip).getAddress();
            byte[] d = new byte[10 + payload.length];
            d[3] = 1;
            System.arraycopy(a, 0, d, 4, 4);
            d[8] = (byte) (port >>> 8);
            d[9] = (byte) port;
            System.arraycopy(payload, 0, d, 10, payload.length);
            udp.send(new DatagramPacket(d, d.length, InetAddress.getByName("127.0.0.1"), relayPort));
        }

        /** [0]=پورت مقصد پاسخ، [1]=payload */
        Object[] recv() throws IOException {
            byte[] b = new byte[65535];
            DatagramPacket p = new DatagramPacket(b, b.length);
            udp.receive(p);
            int port = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
            String ip = (b[4] & 0xFF) + "." + (b[5] & 0xFF) + "." + (b[6] & 0xFF) + "." + (b[7] & 0xFF);
            return new Object[]{ip, port, Arrays.copyOfRange(b, 10, p.getLength())};
        }

        void close() {
            try {
                control.close();
            } catch (IOException ignored) {
            }
            udp.close();
        }
    }

    // ───────────── تست‌ها ─────────────

    static void testCidr() {
        System.out.println("[CidrSet]");
        CidrSet.Builder b = new CidrSet.Builder();
        check(b.addEntry("2.144.0.0/14"), "CIDR معتبر");
        check(b.addEntry(" 5.22.0.0/17 # comment"), "CIDR با کامنت و فاصله");
        check(b.addEntry("10.0.0.1"), "IP تکی");
        check(b.addEntry("20.0.0.1-20.0.0.9"), "بازهٔ A-B");
        check(b.addEntry("30.1.2.77/24"), "CIDR غیرتراز → به شبکه گرد می‌شود");
        check(!b.addEntry("2001:db8::/32"), "IPv6 رد می‌شود");
        check(!b.addEntry("300.1.1.1"), "اکتت نامعتبر");
        check(!b.addEntry("1.2.3.4/33"), "پیشوند نامعتبر");
        check(!b.addEntry("# only comment"), "فقط کامنت");
        check(!b.addEntry(""), "خالی");
        check(b.addEntry("2.144.0.0/16"), "زیرمجموعه (ادغام می‌شود)");
        check(b.addEntry("2.148.0.0/14"), "مجاور (ادغام می‌شود)");
        CidrSet s = b.build();
        check(s.contains(CidrSet.parseIp("2.144.0.0")) && s.contains(CidrSet.parseIp("2.151.255.255")), "ابتدا/انتهای بازهٔ ادغام‌شده");
        check(!s.contains(CidrSet.parseIp("2.152.0.0")) && !s.contains(CidrSet.parseIp("2.143.255.255")), "بیرون بازه");
        check(s.contains(CidrSet.parseIp("30.1.2.200")) && !s.contains(CidrSet.parseIp("30.1.3.0")), "گرد شدن /24");
        check(s.contains(CidrSet.parseIp("20.0.0.5")) && !s.contains(CidrSet.parseIp("20.0.0.10")), "بازهٔ A-B");
        // [2.144-2.151] + 5.22/17 + 10.0.0.1 + 20.0.0.1-9 + 30.1.2.0/24
        check(s.size() == 5, "تعداد بازه‌های ادغام‌شده = 5 (واقعی: " + s.size() + ")");
        CidrSet big = CidrSet.parseLines(Collections.singletonList("0.0.0.0/0"), null);
        check(big.contains(0L) && big.contains(0xFFFFFFFFL), "0.0.0.0/0 همه را می‌گیرد");
        check(!CidrSet.EMPTY.contains(5L), "مجموعهٔ خالی");
        // کارایی و درستی روی لیست بزرگ تصادفی
        Random r = new Random(7);
        CidrSet.Builder bb = new CidrSet.Builder();
        List<long[]> ref = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            long start = (r.nextLong() >>> 33) << 1 & 0xFFFFFF00L;
            bb.addEntry(((start >> 24) & 255) + "." + ((start >> 16) & 255) + "." + ((start >> 8) & 255) + ".0/24");
            ref.add(new long[]{start, start + 255});
        }
        CidrSet bs = bb.build();
        boolean allOk = true;
        for (int i = 0; i < 20000; i++) {
            long ip = r.nextLong() >>> 32;
            boolean exp = false;
            for (long[] x : ref) {
                if (ip >= x[0] && ip <= x[1]) {
                    exp = true;
                    break;
                }
            }
            if (exp != bs.contains(ip)) allOk = false;
        }
        check(allOk, "۲۰هزار جستجو روی ۵هزار رنج با مرجع brute-force یکسان است");
    }

    static void testRules() throws Exception {
        System.out.println("[BypassRules]");
        check(BypassRules.classify("1.2.3.4") == BypassRules.KIND_IP, "classify IP");
        check(BypassRules.classify("1.2.3.0/24") == BypassRules.KIND_IP, "classify CIDR");
        check(BypassRules.classify("digikala.com") == BypassRules.KIND_DOMAIN, "classify دامنه");
        check(BypassRules.classify("sub.example.co.ir") == BypassRules.KIND_DOMAIN, "classify زیردامنه");
        check(BypassRules.classify("1.2.3") == BypassRules.KIND_INVALID, "۱.۲.۳ نه IP است نه دامنه");
        check(BypassRules.classify("localhost") == BypassRules.KIND_INVALID, "بدون نقطه نامعتبر");
        check(BypassRules.classify("a b.com") == BypassRules.KIND_INVALID, "فاصله نامعتبر");
        check(BypassRules.classify("-bad.com") == BypassRules.KIND_INVALID, "خط تیره ابتدای label");
        check(BypassRules.classify("") == BypassRules.KIND_INVALID, "خالی");
        check("digikala.com".equals(BypassRules.normalizeEntry("  https://WWW.digikala.com/path?x=1".replace("WWW.", ""))), "normalize: URL → دامنه");
        check("1.2.3.0/24".equals(BypassRules.normalizeEntry("1.2.3.0/24")), "normalize: CIDR حفظ می‌شود");
        check("example.ir".equals(BypassRules.normalizeEntry("*.Example.IR")), "normalize: wildcard");

        BypassRules r = new BypassRules();
        CidrSet.Builder ir = new CidrSet.Builder();
        ir.addEntry("2.144.0.0/14");
        r.setIranList(ir.build());
        r.setManualEntries(java.util.Arrays.asList("8.8.4.4", "5.6.7.0/24", "digikala.com", "bad value"));
        InetAddress iran = InetAddress.getByName("2.145.1.1");
        InetAddress other = InetAddress.getByName("93.184.216.34");
        check(r.shouldBypass(iran), "IP ایرانی مستقیم");
        check(!r.shouldBypass(other), "IP خارجی از تونل");
        check(r.shouldBypass(InetAddress.getByName("8.8.4.4")), "IP دستی مستقیم");
        check(r.shouldBypass(InetAddress.getByName("5.6.7.99")), "CIDR دستی مستقیم");
        check(r.shouldBypass(InetAddress.getByName("192.168.1.5")), "LAN همیشه مستقیم");
        check(r.shouldBypass(InetAddress.getByName("10.9.8.7")), "LAN ۱۰/۸ مستقیم");
        check(!r.shouldBypass(InetAddress.getByName("172.32.0.1")), "172.32 خارج از 172.16/12");
        check(r.shouldBypassHost("digikala.com") && r.shouldBypassHost("api.digikala.com"), "دامنه و زیردامنه");
        check(!r.shouldBypassHost("notdigikala.com") && !r.shouldBypassHost("digikala.com.evil.org"), "تطبیق پسوند فقط روی مرز نقطه");
        check(r.manualDomains().size() == 1, "ورودی نامعتبر نادیده گرفته شد");
        r.setEnabled(false);
        check(!r.shouldBypass(iran) && !r.shouldBypassHost("digikala.com"), "خاموش‌بودن بایپس");
        check(r.shouldBypass(InetAddress.getByName("192.168.1.5")), "LAN با بایپس خاموش هم مستقیم");
        check(!r.shouldBypass(InetAddress.getByName("2001:db8::1")), "IPv6 فعلاً از تونل");
    }

    static void testTcp(String udpgwBin) throws Exception {
        System.out.println("[SOCKS5 TCP]");
        FakeSsh ssh = new FakeSsh();
        BypassRules rules = new BypassRules();
        Socks5Server srv = new Socks5Server(ssh, rules, null);
        int port = srv.start();
        ServerSocket echo = tcpEcho();
        int ep = echo.getLocalPort();

        // ۱) از تونل
        Socket s = socksConnect(port, "127.0.0.1", ep);
        byte[] data = new byte[1_000_000];
        new Random(1).nextBytes(data);
        final OutputStream out = s.getOutputStream();
        final byte[] fd = data;
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    out.write(fd);
                    out.flush();
                } catch (IOException ignored) {
                }
            }
        });
        // نوشتن و خواندن هم‌زمان (۱ مگابایت) تا بن‌بست بافر پیش نیاید
        w.start();
        DataInputStream in = new DataInputStream(s.getInputStream());
        byte[] got = new byte[data.length];
        in.readFully(got);
        check(Arrays.equals(data, got), "۱ مگابایت رفت‌وبرگشت بدون خطا از مسیر تونل");
        check(ssh.opens.get() == 1, "از کانال SSH رد شد (opens=" + ssh.opens.get() + ")");
        s.close();

        // ۲) بایپس
        rules.setManualEntries(Collections.singletonList("127.0.0.1"));
        int before = ssh.opens.get();
        Socket d = socksConnect(port, "127.0.0.1", ep);
        d.getOutputStream().write("hello".getBytes());
        byte[] h = new byte[5];
        new DataInputStream(d.getInputStream()).readFully(h);
        check("hello".equals(new String(h)), "مسیر مستقیم (بایپس) کار می‌کند");
        check(ssh.opens.get() == before, "بایپس از SSH رد نشد");
        d.close();
        rules.setManualEntries(Collections.<String>emptyList());

        // ۳) مقصد بسته → کد خطا
        ServerSocket tmp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int closedPort = tmp.getLocalPort();
        tmp.close();
        boolean refused = false;
        try {
            socksConnect(port, "127.0.0.1", closedPort).close();
        } catch (ConnectException e) {
            refused = e.getMessage().contains("rep=");
        }
        check(refused, "مقصد بسته → پاسخ خطای SOCKS (نه معطلی)");

        // ۴) ۵۰ اتصال هم‌زمان
        final int sp = port, epf = ep;
        final AtomicInteger okc = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int id = i;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Socket c = socksConnect(sp, "127.0.0.1", epf);
                        byte[] m = ("conn-" + id).getBytes();
                        c.getOutputStream().write(m);
                        byte[] r = new byte[m.length];
                        new DataInputStream(c.getInputStream()).readFully(r);
                        if (Arrays.equals(m, r)) okc.incrementAndGet();
                        c.close();
                    } catch (IOException ignored) {
                    }
                }
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join(10000);
        check(okc.get() == 50, "۵۰ اتصال هم‌زمان (موفق: " + okc.get() + ")");

        // ۵) نیمه‌بستن: کلاینت می‌فرستد و می‌بندد، پاسخ باید برسد
        Socket hc = socksConnect(port, "127.0.0.1", ep);
        hc.getOutputStream().write("half-close".getBytes());
        hc.shutdownOutput();
        byte[] hr = new byte[10];
        new DataInputStream(hc.getInputStream()).readFully(hr);
        check("half-close".equals(new String(hr)), "half-close: پاسخ بعد از shutdownOutput می‌رسد");
        hc.close();

        srv.close();
        echo.close();
    }

    static Process startUdpgw(String bin, int port) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(bin, "--listen-addr", "127.0.0.1:" + port, "--loglevel", "3");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/tmp/udpgw-test.log")));
        Process p = pb.start();
        for (int i = 0; i < 50; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return p;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        throw new IOException("udpgw did not start");
    }

    static void testUdp(String udpgwBin) throws Exception {
        System.out.println("[SOCKS5 UDP ↔ badvpn-udpgw واقعی]");
        int gwPort = 17300 + new Random().nextInt(500);
        Process gw = startUdpgw(udpgwBin, gwPort);
        try {
            FakeSsh ssh = new FakeSsh();
            BypassRules rules = new BypassRules();
            UdpGwMux mux = new UdpGwMux(ssh, gwPort, 8); // سقف کم برای تست حذف LRU
            Socks5Server srv = new Socks5Server(ssh, rules, mux);
            int port = srv.start();
            DatagramSocket e1 = udpEcho(), e2 = udpEcho();

            Assoc a = new Assoc(port);
            a.send("127.0.0.1", e1.getLocalPort(), "ping".getBytes());
            Object[] r = a.recv();
            check("echo:ping".equals(new String((byte[]) r[2])), "UDP از udpgw رفت و برگشت");
            check(((Integer) r[1]) == e1.getLocalPort() && "127.0.0.1".equals(r[0]), "آدرس پاسخ = مقصد اصلی");
            check(ssh.opens.get() == 1, "فقط یک کانال SSH برای udpgw (opens=" + ssh.opens.get() + ")");

            a.send("127.0.0.1", e1.getLocalPort(), "again".getBytes());
            a.send("127.0.0.1", e2.getLocalPort(), "other".getBytes());
            Object[] r1 = a.recv(), r2 = a.recv();
            String s1 = new String((byte[]) r1[2]), s2 = new String((byte[]) r2[2]);
            check((s1.equals("echo:again") && s2.equals("echo:other")) || (s1.equals("echo:other") && s2.equals("echo:again")),
                    "دو مقصد مختلف روی یک کانال، conid جدا");
            check(ssh.opens.get() == 1, "همچنان یک کانال (opens=" + ssh.opens.get() + ")");

            // دیتاگرام بزرگ (۱۲۰۰ بایت)
            byte[] big = new byte[1200];
            new Random(3).nextBytes(big);
            a.send("127.0.0.1", e1.getLocalPort(), big);
            Object[] rb = a.recv();
            byte[] rbp = (byte[]) rb[2];
            check(rbp.length == 1205 && Arrays.equals(Arrays.copyOfRange(rbp, 5, 1205), big), "دیتاگرام ۱۲۰۰ بایتی سالم");

            // حذف LRU: ۱۲ مقصد با سقف ۸
            List<DatagramSocket> echos = new ArrayList<>();
            for (int i = 0; i < 12; i++) echos.add(udpEcho());
            int okRounds = 0;
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 12; i++) {
                    a.send("127.0.0.1", echos.get(i).getLocalPort(), ("r" + round + "-" + i).getBytes());
                    Object[] rr = a.recv();
                    if (("echo:r" + round + "-" + i).equals(new String((byte[]) rr[2])) && ((Integer) rr[1]) == echos.get(i).getLocalPort()) okRounds++;
                }
            }
            check(okRounds == 36, "۳۶ رفت‌وبرگشت با حذف LRU (موفق: " + okRounds + ")");

            // دو association هم‌زمان نباید پاسخ‌ها را قاطی کنند
            Assoc a2 = new Assoc(port);
            a.send("127.0.0.1", e1.getLocalPort(), "from-A".getBytes());
            a2.send("127.0.0.1", e1.getLocalPort(), "from-B".getBytes());
            String ga = new String((byte[]) a.recv()[2]), gb = new String((byte[]) a2.recv()[2]);
            check(ga.equals("echo:from-A") && gb.equals("echo:from-B"), "دو association هم‌زمان بدون قاطی‌شدن پاسخ");
            a2.close();

            // بایپس مستقیم UDP
            rules.setManualEntries(Collections.singletonList("127.0.0.1"));
            int opensBefore = ssh.opens.get();
            a.send("127.0.0.1", e2.getLocalPort(), "direct".getBytes());
            Object[] rd = a.recv();
            check("echo:direct".equals(new String((byte[]) rd[2])) && ((Integer) rd[1]) == e2.getLocalPort(), "UDP بایپس مستقیم کار می‌کند");
            check(ssh.opens.get() == opensBefore, "UDP بایپس از SSH رد نشد");
            rules.setManualEntries(Collections.<String>emptyList());
            a.close();

            // قطع کانال udpgw و بازسازی خودکار
            gw.destroy();
            gw.waitFor();
            Thread.sleep(300);
            gw = startUdpgw(udpgwBin, gwPort);
            Thread.sleep(2500); // تا بازهٔ retry بگذرد
            Assoc a3 = new Assoc(port);
            boolean recovered = false;
            for (int i = 0; i < 6 && !recovered; i++) {
                a3.send("127.0.0.1", e1.getLocalPort(), "recover".getBytes());
                a3.udp.setSoTimeout(1500);
                try {
                    recovered = "echo:recover".equals(new String((byte[]) a3.recv()[2]));
                } catch (IOException ignored) {
                    Thread.sleep(1000);
                }
            }
            check(recovered, "بعد از ری‌استارت udpgw دوباره وصل می‌شود");
            a3.close();

            mux.close();
            srv.close();
        } finally {
            gw.destroy();
        }
    }

    static void testDnsFallback() throws Exception {
        System.out.println("[DNS روی TCP وقتی udpgw نیست]");
        FakeSsh ssh = new FakeSsh();
        ServerSocket dns = dnsTcp();
        ssh.portMap.put(53, dns.getLocalPort());
        BypassRules rules = new BypassRules();
        UdpGwMux mux = new UdpGwMux(ssh, 1, 8); // پورت ۱ = بسته → udpgw وصل نمی‌شود
        Socks5Server srv = new Socks5Server(ssh, rules, mux);
        int port = srv.start();
        Assoc a = new Assoc(port);
        byte[] q = {0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        a.send("127.0.0.1", 53, q);
        Object[] r = a.recv();
        byte[] resp = (byte[]) r[2];
        check(resp.length == q.length + 1 && resp[0] == 'R' && Arrays.equals(Arrays.copyOfRange(resp, 1, resp.length), q), "پاسخ DNS از مسیر TCP رسید");
        check(((Integer) r[1]) == 53, "پورت پاسخ ۵۳");
        // UDP غیر DNS بدون udpgw باید بی‌صدا حذف شود (نه کرش، نه پاسخ)
        a.udp.setSoTimeout(700);
        a.send("127.0.0.1", 9999, "x".getBytes());
        boolean dropped = false;
        try {
            a.recv();
        } catch (java.net.SocketTimeoutException e) {
            dropped = true;
        }
        check(dropped, "UDP غیر DNS بدون udpgw حذف می‌شود");
        a.close();
        mux.close();
        srv.close();
    }

    static void testFrameFormat() {
        System.out.println("[قالب فریم udpgw]");
        byte[] f = UdpGwMux.encodeFrame(4, 0x0102, new byte[]{8, 8, 4, 4}, 53, new byte[]{9, 9});
        // len(LE)=3+4+2+2=11 ، flags=4 ، conid LE = 02 01 ، ip ، port BE ، data
        byte[] exp = {11, 0, 4, 2, 1, 8, 8, 4, 4, 0, 53, 9, 9};
        check(Arrays.equals(f, exp), "IPv4: " + Arrays.toString(f));
        byte[] f6 = UdpGwMux.encodeFrame(8, 1, new byte[16], 443, new byte[0]);
        check(f6.length == 2 + 3 + 16 + 2 && (f6[0] & 0xFF) == 21 && f6[2] == 8, "IPv6 طول و فلگ");
    }

    public static void main(String[] args) throws Exception {
        String bin = args.length > 0 ? args[0] : "badvpn-udpgw";
        testCidr();
        testRules();
        testFrameFormat();
        testTcp(bin);
        testUdp(bin);
        testDnsFallback();
        System.out.println("\nنتیجه: " + passed + " موفق، " + failed + " ناموفق");
        System.exit(failed == 0 ? 0 : 1);
    }
}
