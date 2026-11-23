#!/usr/bin/env bash
# ساخت بستهٔ .deb لینوکس. پیش‌نیاز: JDK 11+، dpkg-deb، فایل jsch.jar و باینری hev-socks5-tunnel.
#   JSCH_JAR=/path/jsch-0.2.19.jar HEV_BIN=/path/hev-socks5-tunnel ./linux/build.sh
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-1.0.0}"
ARCH="${ARCH:-$(dpkg --print-architecture)}"
: "${JSCH_JAR:?JSCH_JAR را تنظیم کنید}"
: "${HEV_BIN:?HEV_BIN را تنظیم کنید}"
CORE=../app/src/main/java/com/foxssh/vpn/core
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/pkg"

echo "== کامپایل"
javac --release 11 -encoding UTF-8 -nowarn -cp "$JSCH_JAR" -d "$OUT/classes" "$CORE"/*.java src/com/foxssh/desktop/*.java
cp assets/logo256.png "$OUT/logo256.png"
cp assets/logo48.png "$OUT/logo48.png"
cp assets/logo256.png "$OUT/classes/logo.png"
mkdir -p "$OUT/classes/fonts" && cp assets/fonts/*.ttf "$OUT/classes/fonts/"
mkdir -p "$OUT/classes/flags" && cp assets/flags/*.png "$OUT/classes/flags/"
cp assets/worldmap.png "$OUT/classes/worldmap.png"
jar --create --file "$OUT/foxssh.jar" -C "$OUT/classes" .

echo "== ساخت بسته"
P="$OUT/pkg"
install -d "$P/DEBIAN" "$P/usr/bin" "$P/usr/lib/foxssh/lib" "$P/usr/share/foxssh" \
  "$P/usr/lib/systemd/system" "$P/usr/share/polkit-1/actions" "$P/usr/share/applications" \
  "$P/usr/share/icons/hicolor/256x256/apps" "$P/usr/share/icons/hicolor/48x48/apps"
install -m 755 packaging/foxssh "$P/usr/bin/foxssh"
install -m 644 "$OUT/foxssh.jar" "$P/usr/lib/foxssh/foxssh.jar"
install -m 644 "$JSCH_JAR" "$P/usr/lib/foxssh/lib/jsch.jar"
install -m 755 "$HEV_BIN" "$P/usr/lib/foxssh/hev-socks5-tunnel"
install -m 755 packaging/foxssh-ctl packaging/foxssh-run packaging/foxssh-down "$P/usr/lib/foxssh/"
install -m 644 assets/iran_ipv4.txt "$P/usr/share/foxssh/iran_ipv4.txt"
install -m 644 packaging/foxssh.service "$P/usr/lib/systemd/system/foxssh.service"
install -m 644 packaging/com.foxssh.policy "$P/usr/share/polkit-1/actions/com.foxssh.policy"
install -m 644 packaging/foxssh.desktop "$P/usr/share/applications/foxssh.desktop"
install -m 644 "$OUT/logo256.png" "$P/usr/share/icons/hicolor/256x256/apps/foxssh.png"
install -m 644 "$OUT/logo48.png" "$P/usr/share/icons/hicolor/48x48/apps/foxssh.png"
sed -e "s/@VERSION@/$VERSION/" -e "s/@ARCH@/$ARCH/" packaging/control.in > "$P/DEBIAN/control"
install -m 755 packaging/postinst packaging/prerm packaging/postrm "$P/DEBIAN/"
SIZE=$(du -sk "$P" | cut -f1); echo "Installed-Size: $SIZE" >> "$P/DEBIAN/control"
dpkg-deb --build --root-owner-group "$P" "$OUT/foxssh_${VERSION}_${ARCH}.deb"
echo "== آماده: linux/$OUT/foxssh_${VERSION}_${ARCH}.deb"
