package com.foxssh.desktop;

import com.foxssh.vpn.core.ChannelOpener;
import com.foxssh.vpn.core.Duplex;
import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** نشست SSH ساده (رمز عبور) که کانال‌های direct-tcpip می‌دهد. نسخهٔ دسکتاپ SshTunnel اندروید. */
final class DSshTunnel implements ChannelOpener {
    private final Path knownHosts;
    private final AtomicLong tx, rx;
    private final JSch jsch = new JSch();
    private volatile Session session;
    private volatile String banner = "";

    DSshTunnel(Path knownHosts, AtomicLong tx, AtomicLong rx) {
        this.knownHosts = knownHosts;
        this.tx = tx;
        this.rx = rx;
    }

    String banner() { return banner; }

    boolean isAlive() {
        Session s = session;
        return s != null && s.isConnected();
    }

    /** connectHost می‌تواند IP از پیش‌تبدیل‌شده باشد؛ hostId برای ذخیرهٔ اثرانگشت است. */
    void connect(String connectHost, String hostId, int port, String user, String pass) throws JSchException {
        jsch.setHostKeyRepository(new TofuRepo(hostId));
        Session s = jsch.getSession(user, connectHost, port);
        s.setPassword(pass);
        s.setUserInfo(new Info(pass));
        s.setConfig("StrictHostKeyChecking", "yes");
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        s.setConfig("server_host_key", JSch.getConfig("server_host_key") + ",ssh-rsa");
        s.setConfig("kex", JSch.getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1");
        s.setServerAliveInterval(20000);
        s.setServerAliveCountMax(2);
        s.connect(15000);
        session = s;
    }

    void close() {
        Session s = session;
        session = null;
        if (s != null) s.disconnect();
    }

    @Override
    public Duplex open(String host, int port) throws IOException {
        Session s = session;
        if (s == null || !s.isConnected()) throw new IOException("SSH قطع است");
        try {
            ChannelDirectTCPIP ch = (ChannelDirectTCPIP) s.openChannel("direct-tcpip");
            ch.setHost(host);
            ch.setPort(port);
            ch.setOrgIPAddress("127.0.0.1");
            ch.setOrgPort(0);
            InputStream in = new CountIn(ch.getInputStream(), rx);
            OutputStream out = new CountOut(ch.getOutputStream(), tx);
            ch.connect(15000);
            return new Chan(ch, in, out);
        } catch (JSchException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static final class Chan implements Duplex {
        private final ChannelDirectTCPIP ch;
        private final InputStream in;
        private final OutputStream out;

        Chan(ChannelDirectTCPIP ch, InputStream in, OutputStream out) {
            this.ch = ch;
            this.in = in;
            this.out = out;
        }

        @Override public InputStream in() { return in; }
        @Override public OutputStream out() { return out; }

        @Override
        public void shutdownOutput() {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            ch.disconnect();
        }
    }

    private static final class CountIn extends FilterInputStream {
        private final AtomicLong c;
        CountIn(InputStream in, AtomicLong c) { super(in); this.c = c; }
        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) c.incrementAndGet();
            return b;
        }
        @Override public int read(byte[] b, int o, int l) throws IOException {
            int n = super.read(b, o, l);
            if (n > 0) c.addAndGet(n);
            return n;
        }
    }

    private static final class CountOut extends FilterOutputStream {
        private final AtomicLong c;
        CountOut(OutputStream out, AtomicLong c) { super(out); this.c = c; }
        @Override public void write(int b) throws IOException { out.write(b); c.incrementAndGet(); }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); c.addAndGet(l); }
    }

    private final class Info implements UserInfo, UIKeyboardInteractive {
        private final String pass;
        Info(String pass) { this.pass = pass; }

        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return pass; }
        @Override public boolean promptPassword(String m) { return true; }
        @Override public boolean promptPassphrase(String m) { return false; }
        @Override public boolean promptYesNo(String m) { return false; }

        @Override
        public void showMessage(String m) { // SSH_MSG_USERAUTH_BANNER
            if (m != null && !m.trim().isEmpty()) banner = banner.isEmpty() ? m : banner + "\n" + m;
        }

        @Override
        public String[] promptKeyboardInteractive(String dest, String name, String instr, String[] prompt, boolean[] echo) {
            return prompt != null && prompt.length >= 1 ? new String[]{pass} : new String[0];
        }
    }

    /** اولین اتصال: اثرانگشت ذخیره می‌شود؛ تغییر بعدی = خطا. */
    private final class TofuRepo implements HostKeyRepository {
        private final String id;
        TofuRepo(String id) { this.id = id; }

        @Override
        public synchronized int check(String host, byte[] key) {
            String fp = sha256(key);
            Properties p = new Properties();
            try (java.io.Reader r = Files.newBufferedReader(knownHosts, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {
            }
            String saved = p.getProperty(id);
            if (saved == null) {
                p.setProperty(id, fp);
                try (java.io.Writer w = Files.newBufferedWriter(knownHosts, StandardCharsets.UTF_8)) {
                    p.store(w, null);
                } catch (IOException ignored) {
                }
                return OK;
            }
            return saved.equals(fp) ? OK : CHANGED;
        }

        @Override public void add(HostKey hostkey, UserInfo ui) {}
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "fox-tofu"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    private static String sha256(byte[] b) {
        try {
            return Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
