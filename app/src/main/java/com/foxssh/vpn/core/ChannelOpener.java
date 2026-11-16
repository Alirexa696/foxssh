package com.foxssh.vpn.core;

import java.io.IOException;

/** بازکردن یک اتصال TCP به host:port «از سمت سرور» (در برنامه: کانال direct-tcpip روی SSH). */
public interface ChannelOpener {
    Duplex open(String host, int port) throws IOException;
}
