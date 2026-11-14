#!/bin/bash
# Runs inside the virtual phone job. Everything is printed to the log; screenshots go to shots/.
set -x
mkdir -p shots
PKG=io.github.beatreelapp.twa.test
adb install -r BeatReel-test.apk || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb push test-song.mp3 /sdcard/Download/test-song.mp3
# gallery test files: 2 videos and 2 photos (v88)
for k in 1 2; do
  ffmpeg -loglevel error -y -f lavfi -i "testsrc=s=360x640:d=3" -f lavfi -i "sine=f=$((300*k)):d=3" -c:v libx264 -pix_fmt yuv420p -shortest gal_v$k.mp4
  ffmpeg -loglevel error -y -f lavfi -i "color=c=0x3a6b8c:s=480x640" -vf "drawbox=x=40:y=40:w=$((100*k)):h=200:color=white:t=fill" -frames:v 1 gal_p$k.jpg
  adb push gal_v$k.mp4 /sdcard/DCIM/gal_v$k.mp4; adb push gal_p$k.jpg /sdcard/Pictures/gal_p$k.jpg
done
adb shell content call --method scan_volume --uri content://media --arg external_primary || true
sleep 5
ID=$(adb shell content query --uri content://media/external/audio/media --projection _id:_display_name | grep test-song | sed -E 's/.*_id=([0-9]+).*/\1/' | head -1)
echo "song id: $ID"
adb logcat -c
adb shell am start -n $PKG/io.github.beatreelapp.twa.MainActivity
sleep 20
adb exec-out screencap -p > shots/0_open.png
export SONG_ID=$ID
python3 .github/emu/drive.py 2>&1 | tee shots/drive.log
echo "=== gallery ==="; adb shell ls -l /sdcard/Movies/BeatReel/ 2>&1 | tee shots/gallery.txt
echo "=== notifications ==="; adb shell dumpsys notification --noredact | grep -i -A3 "beatreel" | head -40 | tee shots/notif.txt
adb logcat -d | grep -iE "Capacitor/Console|chromium|AndroidRuntime|FATAL" | tail -150 > shots/logcat.txt
exit 0
