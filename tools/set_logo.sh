#!/usr/bin/env bash
# استفاده: tools/set_logo.sh logo.png   (PNG مربع، ترجیحاً 512x512 یا بزرگتر، با پس‌زمینهٔ شفاف)
set -e
[ -f "$1" ] || { echo "فایل PNG را بدهید"; exit 1; }
R=app/src/main/res
python3 - "$1" <<'PY'
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGBA")
R = "app/src/main/res"
import os
os.makedirs(R + "/drawable-nodpi", exist_ok=True)
im.resize((432, 432), Image.LANCZOS).save(R + "/drawable-nodpi/logo_img.png")
PY
cat > $R/drawable/ic_logo.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/logo_img" />
XML
echo "لوگو جایگزین شد."
