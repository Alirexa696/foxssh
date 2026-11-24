#!/usr/bin/python3
# آیکون نوار بالای اوبونتو (AppIndicator) برای Fox VPN.
# پروتکل ساده روی stdin/stdout با برنامهٔ جاوا:
#   جاوا -> این اسکریپت:  "on" (وصل) | "off" (قطع)
#   این اسکریپت -> جاوا:  "show" | "exit"
# استفاده: foxssh-tray.py <پوشهٔ آیکون‌ها> <متن «نمایش»> <متن «خروج»>
import os
import sys
import threading
import time

import gi

AI = None
for ns in ("AyatanaAppIndicator3", "AppIndicator3"):
    try:
        gi.require_version(ns, "0.1")
        AI = getattr(__import__("gi.repository", fromlist=[ns]), ns)
        break
    except (ValueError, ImportError, AttributeError):
        continue
if AI is None:
    sys.exit(3)

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

icon_dir = sys.argv[1]
show_label = sys.argv[2] if len(sys.argv) > 2 else "Open"
exit_label = sys.argv[3] if len(sys.argv) > 3 else "Exit"


ICON_OFF = "foxssh_tray_off"  # بدون خط‌تیره: GTK نام‌های دارای «-» را در نبودنِ آیکون به نام کوتاه‌تر (foxssh) برمی‌گرداند
ICON_ON = "foxssh_tray_on"


def log(msg):
    sys.stderr.write("%s pid=%d %s\n" % (time.strftime("%H:%M:%S"), os.getpid(), msg))
    sys.stderr.flush()


def send(msg):
    try:
        sys.stdout.write(msg + "\n")
        sys.stdout.flush()
    except Exception:
        Gtk.main_quit()


log("start icon_dir=%s lib=%s" % (icon_dir, AI.__name__))
try:  # عیب‌یابی: آیکون‌ها در تم پیدا می‌شوند؟ (مسیر فایل یا None)
    theme = Gtk.IconTheme.get_default()
    theme.append_search_path(icon_dir)
    for _n in (ICON_OFF, ICON_ON):
        _i = theme.lookup_icon(_n, 24, 0)
        log("lookup %s -> %s" % (_n, _i.get_filename() if _i else None))
except Exception as e:
    log("lookup error: %r" % (e,))

ind = AI.Indicator.new("foxssh", ICON_OFF, AI.IndicatorCategory.APPLICATION_STATUS)
ind.set_icon_theme_path(icon_dir)
ind.set_icon_full(ICON_OFF, "Disconnected")
# آیکون «وصل» هم به‌عنوان آیکون حالت ATTENTION ثبت می‌شود تا شل‌هایی که تعویض نام آیکون را دیر می‌گیرند
# هم با عوض‌شدن status آیکون را عوض کنند.
try:
    ind.set_attention_icon_full(ICON_ON, "Connected")
except Exception:
    pass
ind.set_title("Fox VPN")
ind.set_status(AI.IndicatorStatus.ACTIVE)

menu = Gtk.Menu()
item_show = Gtk.MenuItem(label=show_label)
item_show.connect("activate", lambda *_: send("show"))
item_exit = Gtk.MenuItem(label=exit_label)
item_exit.connect("activate", lambda *_: send("exit"))
menu.append(item_show)
menu.append(Gtk.SeparatorMenuItem())
menu.append(item_exit)
menu.show_all()
ind.set_menu(menu)
try:
    ind.set_secondary_activate_target(item_show)  # کلیک وسط ماوس
except Exception:
    pass


def set_state(on):
    try:
        ind.set_icon_full(ICON_ON if on else ICON_OFF, "Connected" if on else "Disconnected")
        ind.set_status(AI.IndicatorStatus.ATTENTION if on else AI.IndicatorStatus.ACTIVE)
        log("state=%s" % ("on" if on else "off"))
    except Exception as e:  # برای عیب‌یابی در /tmp/foxssh-tray.log
        log("set_state error: %r" % (e,))
    return False


def reader():
    for line in sys.stdin:
        cmd = line.strip()
        if cmd in ("on", "off"):
            GLib.idle_add(set_state, cmd == "on")
    GLib.idle_add(Gtk.main_quit)  # جاوا بسته شد


threading.Thread(target=reader, daemon=True).start()
send("ready")
Gtk.main()
