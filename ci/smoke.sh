#!/bin/sh
# Install the release APK, walk the main flow (sample playlist → play a channel → channel list → back),
# and print any crash with a de-obfuscated stack trace.
APK=app/build/outputs/apk/release/app-release.apk
adb install -r "$APK"
adb logcat -c

# tap <text>: find a UI element by visible text (or content-desc) and tap its centre.
tap() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  B=$(python3 - "$1" <<'PY'
import re, sys
t = sys.argv[1]; x = open("ui.xml", encoding="utf-8").read()
for n in re.finditer(r'<node [^>]*>', x):
    s = n.group(0)
    sub = t[1:] if t.startswith("~") else None
    if ('text="%s"' % t) in s or ('content-desc="%s"' % t) in s or (sub and ('content-desc="' in s) and sub.lower() in s.lower() and 'clickable="true"' in s):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if b:
            a, c, d, e = map(int, b.groups()); print((a + d) // 2, (c + e) // 2); break
PY
)
  if [ -n "$B" ]; then echo "tap '$1' at $B"; adb shell input tap $B; else echo "NOT FOUND: $1"; fi
}

# screen <label>: print what is visible right now
screen() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  echo "--- SCREEN [$1]: $(python3 -c "
import re;x=open('ui.xml',encoding='utf-8').read()
print(' | '.join(t for t in re.findall(r'(?:text|content-desc)=\"([^\"]+)\"',x) if t)[:400])")"
}
adb shell wm size
adb shell am start -W -n com.mashwar.tv/.MainActivity
sleep 6
screen home
tap "جرّب بقائمة تجريبية"
sleep 6
screen channels
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
FIRST=$(python3 - <<'PY'
import re
x = open("ui.xml", encoding="utf-8").read()
# first channel row: a clickable node below the "N قناة" counter
nodes = re.findall(r'<node [^>]*>', x)
seen = False
for s in nodes:
    if re.search(r'text="\d+ قناة"', s): seen = True; continue
    if seen and 'clickable="true"' in s:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        a, c, d, e = map(int, b.groups()); print((a + d) // 2, (c + e) // 2); break
PY
)
echo "first channel at $FIRST"; [ -n "$FIRST" ] && adb shell input tap $FIRST
sleep 15
screen player
adb shell input keyevent KEYCODE_MEDIA_PAUSE   # paused video lets uiautomator read the screen
sleep 2
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
adb shell input tap 20 $(( ${W%x*} / 3 ))      # toggle controls (either orientation)
sleep 1
screen player-controls
tap "القنوات"
sleep 3
screen channel-panel
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input tap 20 $(( ${W%x*} / 3 ))
sleep 1
screen before-cast
tap "~cast"
sleep 6
screen after-cast
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 5

adb logcat -d > logcat.txt
grep -E " E |W/System.err| E/" logcat.txt | grep -iE "mashwar|media3|exoplayer|cast|AndroidRuntime" | head -30
if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "=========== CRASH ==========="
  grep -A60 "FATAL EXCEPTION" logcat.txt > crash.txt
  RETRACE=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/retrace 2>/dev/null | head -1)
  if [ -n "$RETRACE" ]; then "$RETRACE" app/build/outputs/mapping/release/mapping.txt crash.txt; else cat crash.txt; fi
else
  echo "=========== NO CRASH ==========="
fi
