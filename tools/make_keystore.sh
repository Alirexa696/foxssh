#!/usr/bin/env bash
# یک بار اجرا کنید؛ کلید امضا بسازید و در GitHub Secrets بگذارید تا نسخه‌های بعدی روی نسخهٔ قبلی نصب شوند.
set -e
read -rsp "رمز keystore: " PW; echo
keytool -genkeypair -v -keystore foxssh.jks -alias foxssh -keyalg RSA -keysize 2048 -validity 36500 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Fox SSH"
echo "--- مقدارها را در Settings > Secrets > Actions بگذارید:"
echo "KEYSTORE_BASE64:"; base64 -w0 foxssh.jks; echo
echo "KEYSTORE_PASSWORD و KEY_PASSWORD: همان رمز    KEY_ALIAS: foxssh"
echo "فایل foxssh.jks را جای امن نگه دارید و هرگز در گیت نگذارید."
