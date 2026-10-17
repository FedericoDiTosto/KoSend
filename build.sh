#!/bin/sh
set -e
cd "$(dirname "$0")"

if [ -z "$JAVA_HOME" ]; then
    for j in "$HOME"/.jdks/*17* /usr/lib/jvm/*17*; do
        [ -x "$j/bin/java" ] && JAVA_HOME="$j" && break
    done
    export JAVA_HOME
fi
[ -n "$JAVA_HOME" ] || { echo "JDK 17 not found: set JAVA_HOME"; exit 1; }

TYPE="${1:-release}"
case "$TYPE" in
    release) TASK=assembleRelease; OUT=app/build/outputs/apk/release/app-release.apk ;;
    debug)   TASK=assembleDebug;   OUT=app/build/outputs/apk/debug/app-debug.apk ;;
    *) echo "Unknown build type: $TYPE (use release or debug)"; exit 1 ;;
esac

./gradlew "$TASK" --console=plain

VERSION=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)
mkdir -p dist
APK="dist/KoSend-$VERSION-$TYPE.apk"
cp "$OUT" "$APK"
echo "APK ready: $APK"

if command -v powershell.exe >/dev/null 2>&1; then
    WIN_HOME=$(powershell.exe -NoProfile -Command '[Environment]::GetFolderPath("UserProfile")' | tr -d '\r')
    DL="$(wslpath "$WIN_HOME")/Downloads"
    [ -d "$DL" ] && cp "$APK" "$DL/" && printf "Also copied to: %s\\Downloads\n" "$WIN_HOME"
fi
