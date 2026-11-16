package com.foxssh.vpn.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** اتصال مستقیم TCP (بدون تونل) برای مقصدهای بایپس. */
public final class SocketDuplex implements Duplex {
    private final Socket socket;

    private SocketDuplex(Socket s) {
        this.socket = s;
    }

    public static SocketDuplex connect(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        return new SocketDuplex(s);
    }

    @Override
    public InputStream in() {
        try {
            return socket.getInputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public OutputStream out() {
        try {
            return socket.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void shutdownOutput() {
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
