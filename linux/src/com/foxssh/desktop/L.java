package com.foxssh.desktop;

import java.util.HashMap;
import java.util.Map;

/** متن‌های برنامه به دو زبان (fa / en). */
final class L {
    private static final Map<String, String[]> T = new HashMap<>();
    private static volatile boolean fa = true;

    private static void a(String k, String fa, String en) {
        T.put(k, new String[]{fa, en});
    }

    static {
        a("connected", "متصل", "Connected");
        a("disconnected", "قطع", "Disconnected");
        a("connecting", "در حال اتصال…", "Connecting…");
        a("reconnecting", "اتصال مجدد…", "Reconnecting…");
        a("your_ip", "آی‌پی شما", "Your IP");
        a("vpn_ip", "آی‌پی شما (VPN)", "Your IP (VPN)");
        a("logs", "مشاهده لاگ‌ها", "View Logs");
        a("add_config", "افزودن کانفیگ", "Add a config");
        a("menu_configs", "کانفیگ‌ها", "Configs");
        a("menu_tunnels", "تانل‌ها (Port Forwarding)", "Tunnels (Port forwarding)");
        a("menu_settings", "تنظیمات", "Settings");
        a("menu_logs", "لاگ‌ها", "Logs");
        a("menu_logs_close", "بستن لاگ‌ها", "Close logs");
        a("manage_configs", "مدیریت کانفیگ‌ها…", "Manage configs…");
        a("add_first", "اول یک کانفیگ اضافه کنید.", "Add a config first.");
        a("incomplete", "آدرس سرور، نام کاربری و رمز این کانفیگ کامل نیست.", "Server, username or password of this config is incomplete.");
        a("banner_title", "پیام سرور", "Server message");
        a("log_empty", "(هنوز لاگی وجود ندارد؛ بعد از اتصال پر می‌شود)", "(no log yet; it fills after connecting)");
        a("auth_cancel", "احراز هویت لغو شد یا مجوز داده نشد", "Authentication was cancelled or denied");
        a("pkexec_fail", "اجرای pkexec ممکن نشد: ", "Could not run pkexec: ");
        a("unknown", "نامشخص", "Unknown");
        a("err_code", "خطا (کد ", "Error (code ");
        // دیالوگ‌ها
        a("configs", "کانفیگ‌ها", "Configs");
        a("add", "+ افزودن", "+ Add");
        a("edit", "ویرایش", "Edit");
        a("select", "انتخاب", "Select");
        a("new_config", "کانفیگ جدید", "New config");
        a("edit_config", "ویرایش کانفیگ", "Edit config");
        a("f_name", "نام کانفیگ", "Config name");
        a("f_host", "آدرس سرور", "Server address");
        a("f_port", "پورت SSH", "SSH port");
        a("f_user", "نام کاربری", "Username");
        a("f_pass", "رمز عبور", "Password");
        a("f_udpgw", "پورت badvpn روی سرور (۰ = غیرفعال)", "badvpn port on server (0 = off)");
        a("save", "ذخیره", "Save");
        a("delete", "حذف", "Delete");
        a("tunnels_btn", "تانل‌ها…", "Tunnels…");
        a("fill_fields", "آدرس سرور، نام کاربری و رمز را وارد کنید.", "Enter server, username and password.");
        a("settings", "تنظیمات", "Settings");
        a("language", "زبان", "Language");
        a("dns1", "DNS اول", "Primary DNS");
        a("dns2", "DNS دوم (اختیاری)", "Secondary DNS (optional)");
        a("bypass", "ترافیک ایران مستقیم برود", "Send Iran traffic directly");
        a("manual", "موارد دستی مستقیم (IP / CIDR / دامنه):", "Manual direct entries (IP / CIDR / domain):");
        a("add_short", "افزودن", "Add");
        a("del_short", "حذف", "Remove");
        a("upd_list", "به‌روزرسانی لیست ایران", "Update Iran list");
        a("forget_keys", "پاک‌کردن کلیدهای ذخیره‌شدهٔ سرورها", "Forget saved server keys");
        a("list_updated", "لیست به‌روز شد (از اتصال بعدی اعمال می‌شود)", "List updated (applies on next connect)");
        a("cleared", "پاک شد", "Cleared");
        a("bad_dns", "DNS نامعتبر است (مثلاً 1.1.1.1)", "Invalid DNS (e.g. 1.1.1.1)");
        // تانل‌ها
        a("t_title", "تانل‌ها (Port forwarding)", "Tunnels (Port forwarding)");
        a("t_any_local", "پورت‌های محلی اتصال از هاست‌های دیگر را بپذیرند", "Local ports accept connections from other hosts");
        a("t_any_remote", "پورت‌های راه دور هم همین‌طور (فقط SSH-2)", "Remote ports do the same (SSH-2 only)");
        a("t_forwarded", "پورت‌های فوروارد‌شده:", "Forwarded ports:");
        a("t_remove", "حذف", "Remove");
        a("t_add_new", "افزودن فوروارد جدید:", "Add new forwarded port:");
        a("t_src", "پورت مبدأ", "Source port");
        a("t_dst", "مقصد (host:port)", "Destination (host:port)");
        a("t_local", "Local", "Local");
        a("t_remote", "Remote", "Remote");
        a("t_dynamic", "Dynamic", "Dynamic");
        a("t_add", "افزودن", "Add");
        a("t_bad", "مقدار نامعتبر: پورت مبدأ ۱ تا ۶۵۵۵۵ و مقصد به شکل host:port (برای Dynamic لازم نیست)", "Invalid: source port 1-65535, destination host:port (not needed for Dynamic)");
        a("t_note", "تانل‌ها با اتصال بعدی اعمال می‌شوند.", "Tunnels apply on the next connect.");
        a("t_save", "ذخیره", "Save");
        a("t_none", "ابتدا یک کانفیگ انتخاب کنید.", "Select a config first.");
    }

    static {
        a("menu_about", "درباره", "About");
        a("menu_exit", "خروج", "Exit");
        a("about_text", "Fox VPN نسخهٔ ۱٫۰٫۰\nتونل SSH برای کل سیستم (Ubuntu)\nخروج از برنامه اتصال را قطع نمی‌کند؛ با دکمهٔ پاور قطع کنید.",
                "Fox VPN v1.0.0\nSystem-wide SSH tunnel for Ubuntu\nClosing the window does not disconnect; use the power button.");
        a("lang_note", "زبان برنامه", "Application language");
    }

    static void setFa(boolean v) { fa = v; }
    static boolean isFa() { return fa; }
    static String t(String k) {
        String[] v = T.get(k);
        return v == null ? k : v[fa ? 0 : 1];
    }
}
