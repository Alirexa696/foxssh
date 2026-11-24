# Fox VPN (Fox SSH) برای لینوکس (Ubuntu 22.04 به بالا)

برنامهٔ گرافیکی با همان امکانات نسخهٔ اندروید: کانفیگ‌های SSH، اتصال یک‌کلیکی، بنر سرور به‌صورت پاپ‌آپ،
بایپس ایران (لیست خودکار + IP/CIDR/دامنهٔ دستی)، DNS قابل‌تنظیم و لاگ.
**کل ترافیک سیستم** از تونل می‌رود؛ مقصدهای ایران مستقیم می‌روند و در «ترافیک تونل» حساب نمی‌شوند.

## ساخت .deb (بدون نصب ابزار روی سیستم)
1. این پوشه‌ها را در همان مخزن GitHub بگذارید (`linux/` و `.github/workflows/linux.yml`) و push کنید.
2. Actions ← **Build Linux .deb** ← Run workflow.
3. از Artifacts فایل `foxssh-deb` را دانلود و باز کنید.

## نصب
```
sudo apt install ./foxssh_1.1.0_amd64.deb
```
بعد از نصب، «Fox SSH» در منوی برنامه‌ها هست (یا در ترمینال `foxssh`).
هنگام اتصال، رمز کاربر (sudo) خواسته می‌شود تا تونل سیستم ساخته شود.

## خط فرمان (CLI)
`foxssh` بدون آرگومان رابط گرافیکی را باز می‌کند؛ با دستور، همان کارهای برنامه را بدون پنجره انجام می‌دهد (`foxssh --help`، `man foxssh`):

```
foxssh add --name work --host example.com -u me --ask-pass -L 8080:intranet:80 -D 1080
foxssh list                      # لیست کانفیگ‌ها (* = پیش‌فرض)
foxssh connect work              # بدون نام = کانفیگ پیش‌فرض؛ بدون ذخیره: foxssh connect --host H -u U --pass-file F
foxssh status [--json]           # کد خروج 0 = وصل، 3 = قطع
foxssh logs -f
foxssh disconnect
foxssh edit work --port 2022 -D 1081 [--clear-tunnels]
foxssh remove work | select work | show work [--show-pass] | import FILE
foxssh dns 1.1.1.1 8.8.8.8
foxssh bypass on|off|list|add example.ir|remove 10.0.0.0/8
foxssh update-list | forget-keys | lang fa|en
```
- رمز را بهتر است با `--pass-file`، `--pass-stdin`، `--ask-pass` یا متغیر `FOXSSH_PASS` بدهید (`--pass` در `ps` دیده می‌شود).
- پرچم‌های `-L SRC:HOST:PORT`، `-R SRC:HOST:PORT`، `-D PORT` (چندبار قابل تکرار)، `--any-local`، `--any-remote` معادل بخش «تانل‌ها» در برنامه‌اند.
- دامنهٔ کاربر (پیش‌فرض برای کاربر عادی): `~/.config/foxssh` — همان ذخیره‌گاه برنامهٔ گرافیکی؛ کانفیگی که با CLI اضافه کنید در GUI هم هست.
- دامنهٔ سیستم (پیش‌فرض برای root/`sudo` یا با `--system`): `/etc/foxssh`. دستورهای نیازمند root خودکار با pkexec بالا می‌روند. برای برگشت به دامنهٔ کاربر: `--no-system`.

## کانفیگ در /etc و سرویس اتصال خودکار هنگام بوت
- `/etc/foxssh/foxssh.conf`: تنظیمات (`selected` = کانفیگ پیش‌فرض، `dns1`، `dns2`، `bypass=true|false`، `manual=a;b`، `lang`).
- `/etc/foxssh/profiles.d/NAME.conf`: یک کانفیگ در هر فایل (دسترسی 600، فقط root). می‌توانید با `sudo foxssh add ...` بسازید یا دستی بنویسید:
  ```
  name=office
  host=vpn.example.com
  port=22
  user=me
  pass=secret
  udpgw=7300
  local=8080:intranet:80,9090:db:5432
  remote=2200:localhost:22
  dynamic=1080
  anyLocal=false
  ```
- سرویس سیستمی `foxssh-auto.service` با همین کانفیگ‌ها هنگام بوت وصل می‌شود، با قطع‌شدن هر ۳۰ ثانیه دوباره تلاش می‌کند و با `foxssh.service` (اتصال دستی/GUI) هم‌زمان اجرا نمی‌شود:
  ```
  sudo foxssh add --name office --host vpn.example.com -u me --pass-file /root/pw
  sudo foxssh service enable office     # فعال + شروع (بدون نام = کانفیگ پیش‌فرض)
  sudo foxssh service status | restart | stop | disable
  journalctl -u foxssh-auto -f          # یا: foxssh logs -f
  ```
  اگر کانفیگ ناقص باشد (کد خروج 78) سرویس تلاش مجدد نمی‌کند؛ `systemctl status foxssh-auto` علت را نشان می‌دهد.

## پیش‌نیازها (خودکار نصب می‌شوند)
Java (default-jre)، iproute2، polkit، systemd. برای DNS از systemd-resolved (پیش‌فرض Ubuntu) استفاده می‌شود.

## معماری
- `foxssh` (Swing): رابط کاربری. بدون دسترسی root اجرا می‌شود.
- `foxssh.service` (root): موتور SSH+SOCKS (با کاربر محدود `foxssh`) + `hev-socks5-tunnel` (دستگاه `fox0`) + مسیرها (`ip rule`) + DNS.
  ترافیک کاربر `foxssh` (اتصال SSH و بایپس ایران) از مسیر اصلی می‌رود، بقیه از تونل.
- با قطع اتصال یا خطا، مسیرها و DNS خودکار پاک می‌شوند.

## حذف
`sudo apt remove foxssh` (یا `purge` برای حذف کاربر و داده‌ها).

## محدودیت‌ها
- فقط IPv4 در تونل؛ IPv6 سیستم از تونل رد نمی‌شود (در صورت نیاز آن را غیرفعال کنید).
- بایپس دامنه با تبدیل به IP انجام می‌شود.
- روی Java 11 بدون پشتیبانی ed25519 در JSch (فقط اگر سرور فقط ed25519 داشته باشد)؛ با Java 17+ مشکلی نیست.
- نیاز به systemd و محیط دسکتاپ با عامل polkit (در Ubuntu Desktop پیش‌فرض هست).

## ظاهر
رابط «نئون آبی» مطابق طرح Fox VPN: پنجرهٔ گوشه‌گرد بدون نوار عنوان سیستم، هدر با زبانهٔ مورب و لوگوی وسط (کمینه/بستن بالا-راست، همبرگر زیر آن)،
برچسب `SSH | پورت` بالا-چپ، دکمهٔ پاور درخشان (سبز هنگام اتصال، آبی در حالت قطع) با «Connected ✓»، کارت آی‌پی با پرچم و نام کشور،
لیست کانفیگ‌ها و نوار «View Logs» پایین. نقشهٔ نقطه‌ای آبی پس‌زمینه است.
فونت وزیرمتن (فارسی) و Poppins (لاتین) داخل برنامه همراه است (مجوز OFL). لوگو: `linux/assets/logo*.png`.

### پیش‌نمایش بدون X11 (فقط توسعه)
`test/com/foxssh/desktop/Preview.java` رابط را بدون سرور گرافیکی روی PNG می‌کشد:
`java -cp <classes> com.foxssh.desktop.Preview <outDir> 500 428 [en]`

### آیکون نوار بالا (Ubuntu)
هنگام باز بودن برنامه، لوگوی Fox VPN در نوار بالای سیستم دیده می‌شود؛ هنگام اتصال یک دایرهٔ سبز کنار لوگو می‌آید و هنگام قطع، لوگوی ساده است.
از AppIndicator استفاده می‌شود (`packaging/foxssh-tray.py`؛ بسته‌های `python3-gi` و `gir1.2-ayatanaappindicator3-0.1` در Recommends هستند).
اگر نصب نبودند، `SystemTray` خود جاوا امتحان می‌شود. کلیک روی آیکون: «باز کردن Fox VPN» / «خروج».

بستن پنجره (×) فقط آن را پنهان می‌کند و برنامه در سینی می‌ماند؛ برای بستن کامل از منوی آیکون سینی «Exit» (یا منوی همبرگری) را بزنید.
آیکون‌ها از لوگوی اصلی با برش دقیق (بدون حاشیهٔ خالی) و در اندازه‌های ۲۲ تا ۱۲۸ ساخته شده‌اند تا در نوار بالا بزرگ و واضح دیده شوند. اگر آیکون سینی تغییر نکرد، لاگ هلپر در `/tmp/foxssh-tray.log` است.

### اجرای تک‌نمونه و سرویس‌ها
- فقط یک نمونه از برنامه به‌ازای هر کاربر اجرا می‌شود؛ اجرای دوباره (از منوی برنامه‌ها) همان نمونه را جلو می‌آورد و آیکون سینی تکراری نمی‌سازد.
- `foxssh --hidden`: بدون نمایش پنجره، فقط آیکون سینی (اگر سینی در دسترس نباشد پنجره نشان داده می‌شود).
- سرویس **کاربر** `foxssh-tray.service` هنگام نصب برای همهٔ کاربران فعال می‌شود و هنگام ورود به دسکتاپ برنامه را بی‌صدا در سینی بالا می‌آورد:
  `systemctl --user status foxssh-tray` · `systemctl --user stop|restart foxssh-tray` · `systemctl --user disable foxssh-tray`
- سرویس **سیستمی** `foxssh.service` موتور تونل است و با دکمهٔ پاور (pkexec) روشن/خاموش می‌شود: `systemctl status foxssh`
  (به‌طور پیش‌فرض enable نیست؛ بدون کانفیگ اتصال، اجرای خودکار معنی ندارد).
- آیکون‌های سینی در `hicolor` نصب می‌شوند (`foxssh_tray_off` / `foxssh_tray_on`). عیب‌یابی آیکون: `cat /tmp/foxssh-tray.log`
  (خطوط `lookup ... -> مسیر` نشان می‌دهند آیکون‌ها پیدا شده‌اند).
