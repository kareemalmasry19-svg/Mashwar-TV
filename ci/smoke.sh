#!/bin/sh
# Install the release APK on the emulator, open it, poke the playback service, and print any crash.
APK=app/build/outputs/apk/release/app-release.apk
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n com.mashwar.tv/.MainActivity
sleep 12
adb shell input keyevent KEYCODE_MEDIA_PLAY   # wakes media sessions
sleep 5
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "=========== CRASH ==========="
  grep -A60 "FATAL EXCEPTION" logcat.txt > crash.txt
  RETRACE=$(ls "$ANDROID_HOME"/cmdline-tools/*/bin/retrace 2>/dev/null | head -1)
  if [ -n "$RETRACE" ]; then "$RETRACE" app/build/outputs/mapping/release/mapping.txt crash.txt; else cat crash.txt; fi
else
  echo "=========== NO CRASH ==========="
  adb shell dumpsys activity activities | grep -i "mashwar" | head -5
fi
