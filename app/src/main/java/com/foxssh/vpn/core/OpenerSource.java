package com.foxssh.vpn.core;

/** منبع ChannelOpener فعلی؛ وقتی تونل SSH قطع است null برمی‌گرداند (تا با اتصال مجدد عوض شود). */
public interface OpenerSource {
    ChannelOpener get();
}
