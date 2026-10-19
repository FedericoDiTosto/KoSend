#!/bin/sh
set -e
cd "$(dirname "$0")"
ADB="${ADB:-/mnt/c/Users/feder/platform-tools/adb.exe}"

./build.sh debug
APK="dist/KoSend-$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)-debug.apk"

"$ADB" get-state >/dev/null 2>&1 || { echo "No phone found: check the cable, USB debugging, and accept the prompt on the phone"; exit 1; }
"$ADB" install -r "$(wslpath -w "$APK")" || {
    echo "Install refused. On some phones you need to turn on Developer options > Install via USB, and tap Install on the phone's prompt."
    exit 1
}
"$ADB" shell am start -n dev.kosend.debug/dev.kosend.MainActivity
