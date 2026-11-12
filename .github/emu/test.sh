#!/bin/bash
# Runs inside the virtual phone job. Everything is printed to the log; screenshots go to shots/.
set -x
mkdir -p shots
PKG=io.github.beatreelapp.twa.test
adb install -r BeatReel-test.apk || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb push test-song.mp3 /sdcard/Download/test-song.mp3
adb shell content call --method scan_volume --uri content://media --arg external_primary || true
sleep 5
ID=$(adb shell content query --uri content://media/external/audio/media --projection _id:_display_name | grep test-song | sed -E 's/.*_id=([0-9]+).*/\1/' | head -1)
echo "song id: $ID"
adb logcat -c
adb shell am start -n $PKG/io.github.beatreelapp.twa.MainActivity
sleep 20
adb exec-out screencap -p > shots/0_open.png
adb shell am start -a android.intent.action.SEND -t audio/mpeg --eu android.intent.extra.STREAM content://media/external/audio/media/$ID --grant-read-uri-permission -n $PKG/io.github.beatreelapp.twa.MainActivity
sleep 8
adb exec-out screencap -p > shots/1_shared.png
python3 .github/emu/drive.py 2>&1 | tee shots/drive.log
echo "=== gallery ==="; adb shell ls -l /sdcard/Movies/BeatReel/ 2>&1 | tee shots/gallery.txt
echo "=== notifications ==="; adb shell dumpsys notification --noredact | grep -i -A3 "beatreel" | head -40 | tee shots/notif.txt
adb logcat -d | grep -iE "Capacitor/Console|chromium|AndroidRuntime|FATAL" | tail -150 > shots/logcat.txt
exit 0
