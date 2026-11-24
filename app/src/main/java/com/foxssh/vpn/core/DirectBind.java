package com.foxssh.vpn.core;

import java.net.InetAddress;

/**
 * آدرس محلی اختیاری برای اتصال‌های مستقیم (بایپس). فقط نسخهٔ ویندوز مقدار می‌دهد تا ترافیک مستقیم
 * به‌جای تونل از کارت شبکهٔ اصلی خارج شود؛ در اندروید و لینوکس همیشه null است.
 */
public final class DirectBind {
    public static volatile InetAddress local;

    private DirectBind() {}
}
