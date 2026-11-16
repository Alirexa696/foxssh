package com.foxssh.vpn.core;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/** یک کانال دوطرفهٔ بایتی (سوکت مستقیم یا کانال direct-tcpip روی SSH). */
public interface Duplex extends Closeable {
    InputStream in();

    OutputStream out();

    /** نیمه‌بستن سمت ارسال (EOF) بدون بستن سمت دریافت. */
    void shutdownOutput();

    @Override
    void close();
}
