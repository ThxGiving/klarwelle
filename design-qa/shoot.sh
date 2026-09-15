#!/usr/bin/env bash
# Boot one AVD headless, install the DEBUG APK (demo stations, no hardware), screenshot the main
# screens at this resolution, shut down.
set -u
AVD=$1; OUT=/home/sebastian/Build/klarwelle/design-qa/$AVD; mkdir -p "$OUT"
APK=/home/sebastian/Build/klarwelle/app/build/outputs/apk/debug/app-debug.apk
E=~/Android/Sdk/emulator/emulator; ADB=~/Android/Sdk/platform-tools/adb
PKG=io.github.thxgiving.klarwelle
rm -f ~/.android/avd/$AVD.avd/*.lock
$E -avd $AVD -no-window -no-boot-anim -no-snapshot -gpu swiftshader_indirect >"$OUT/emu.log" 2>&1 &
EPID=$!
$ADB wait-for-device
for i in $(seq 1 120); do [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
$ADB shell wm size | tr -d '\r' > "$OUT/wm.txt"; $ADB shell wm density | tr -d '\r' >> "$OUT/wm.txt"
$ADB shell settings put secure immersive_mode_confirmations confirmed
for k in window_animation_scale transition_animation_scale animator_duration_scale; do $ADB shell settings put global $k 0; done
$ADB uninstall $PKG >/dev/null 2>&1; $ADB install -r -g "$APK" >"$OUT/install.txt" 2>&1
$ADB shell am start -n $PKG/com.px6.radio.MainActivity >/dev/null
sleep 14
shot(){ $ADB exec-out screencap -p > "$OUT/$1.png"; }
# Tap the centre of the first UI node whose text/desc matches.
tap(){ $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null
  python3 - "$1" "$OUT/ui.xml" <<'PY'
import re,sys
q,f=sys.argv[1],sys.argv[2]; s=open(f,errors='ignore').read()
for m in re.finditer(r'<node [^>]*>', s):
    n=m.group(0)
    if re.search(r'(text|content-desc)="[^"]*%s[^"]*"'%re.escape(q), n):
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x=(int(b[1])+int(b[3]))//2; y=(int(b[2])+int(b[4]))//2; print(x,y); break
PY
}
# BACK on the main screen asks "Quit radio?" — answer Cancel if that dialog appeared.
back(){ $ADB shell input keyevent BACK; sleep 1.5; local c; c=$(tap "Cancel"); [ -n "$c" ] && $ADB shell input tap $c && sleep 1; }
open(){ local xy; xy=$(tap "$1"); [ -n "$xy" ] && $ADB shell input tap $xy && sleep 2.5 && shot "$2"; back; }
shot 01-main
open "Stations" 02-stations
open "Settings" 03-settings
open "Manual" 04-manual
open "View" 05-view
# ASA test alert through the debug receiver (debug build only)
$ADB shell am broadcast -a com.px6.radio.DEBUG_EWS --ez settest true --ei stage 7 --ez test true --es msg "'Probealarm: Dies ist eine Testmeldung des Warnsystems. Es besteht keine Gefahr.'" >/dev/null 2>&1; sleep 3; shot 06-asa
$ADB emu kill >/dev/null 2>&1; sleep 3; kill $EPID 2>/dev/null; wait $EPID 2>/dev/null
rm -f ~/.android/avd/$AVD.avd/*.lock "$OUT/ui.xml"
cat "$OUT/wm.txt"; grep -i "success\|fail" "$OUT/install.txt"; ls "$OUT"/*.png | xargs -n1 basename | tr '\n' ' '; echo
