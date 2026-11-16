package com.foxssh.vpn.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * پشتیبان DNS: اگر badvpn-udpgw در دسترس نباشد، پرس‌وجوی DNS (UDP) را
 * به‌صورت DNS روی TCP از طریق همان تونل SSH می‌فرستد.
 */
final class DnsOverTcp {
    private DnsOverTcp() {
    }

    static byte[] query(ChannelOpener opener, String host, int port, byte[] query, int timeoutMs) throws IOException {
        if (query.length == 0 || query.length > 65535) throw new IOException("bad dns query");
        final Duplex d = opener.open(host, port);
        ScheduledFuture<?> watchdog = Timers.EX.schedule(new Runnable() {
            @Override
            public void run() {
                d.close();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            OutputStream out = d.out();
            byte[] framed = new byte[query.length + 2];
            framed[0] = (byte) (query.length >>> 8);
            framed[1] = (byte) query.length;
            System.arraycopy(query, 0, framed, 2, query.length);
            out.write(framed);
            out.flush();
            DataInputStream in = new DataInputStream(d.in());
            int n = in.readUnsignedShort();
            byte[] resp = new byte[n];
            in.readFully(resp);
            return resp;
        } finally {
            watchdog.cancel(false);
            d.close();
        }
    }
}
