package com.foxssh.vpn;

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
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

/** یک نشست SSH ساده (رمز عبور) که کانال‌های direct-tcpip می‌دهد. */
public final class SshTunnel implements ChannelOpener {
    private final Prefs prefs;
    private final AtomicLong tx, rx;
    private final JSch jsch = new JSch();
    private volatile Session session;
    private volatile String banner = "";

    public SshTunnel(Prefs prefs, AtomicLong tx, AtomicLong rx) {
        this.prefs = prefs;
        this.tx = tx;
        this.rx = rx;
    }

    public String banner() { return banner; }

    public boolean isAlive() {
        Session s = session;
        return s != null && s.isConnected();
    }

    public void connect(final String host, final int port, String user, final String pass) throws JSchException {
        final String hostId = host + ":" + port;
        jsch.setHostKeyRepository(new TofuRepo(hostId));
        Session s = jsch.getSession(user, host, port);
        s.setPassword(pass);
        s.setUserInfo(new Info(pass));
        s.setConfig("StrictHostKeyChecking", "yes");
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        // سازگاری با سرورهای قدیمی‌تر (در انتهای فهرست پیش‌فرض)
        s.setConfig("server_host_key", JSch.getConfig("server_host_key") + ",ssh-rsa");
        s.setConfig("kex", JSch.getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1");
        s.setServerAliveInterval(20000);
        s.setServerAliveCountMax(2);
        s.connect(15000);
        session = s;
    }

    public void close() {
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
            InputStream in = new Counting.In(ch.getInputStream(), rx);
            OutputStream out = new Counting.Out(ch.getOutputStream(), tx);
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
                out.close(); // EOF به سرور
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            ch.disconnect();
        }
    }

    /** شمارندهٔ ترافیک «فقط تونل». */
    private static final class Counting {
        static final class In extends FilterInputStream {
            private final AtomicLong c;
            In(InputStream in, AtomicLong c) { super(in); this.c = c; }
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

        static final class Out extends FilterOutputStream {
            private final AtomicLong c;
            Out(OutputStream out, AtomicLong c) { super(out); this.c = c; }
            @Override public void write(int b) throws IOException { out.write(b); c.incrementAndGet(); }
            @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); c.addAndGet(l); }
        }
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

    /** اولین اتصال: اثرانگشت ذخیره می‌شود؛ تغییر بعدی = خطا (محافظ MITM). */
    private final class TofuRepo implements HostKeyRepository {
        private final String id;
        TofuRepo(String id) { this.id = id; }

        @Override
        public int check(String host, byte[] key) {
            String fp = sha256(key);
            String saved = prefs.hostKey(id);
            if (saved == null) {
                prefs.setHostKey(id, fp);
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
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            return android.util.Base64.encodeToString(d, android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
