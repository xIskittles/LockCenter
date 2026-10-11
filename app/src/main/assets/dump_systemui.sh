#!/system/bin/sh
# LockCenter: дамп SystemUI для анализа раздельного шейда уведомлений/быстрых настроек.
# Запуск: su -c "sh /data/data/com.anton.lockcenter/files/dump_systemui.sh"
# Или вручную: su -c "sh /storage/emulated/0/Download/dump_systemui.sh"

DL=/storage/emulated/0/Download
[ -d "$DL" ] || DL=/sdcard/Download
[ -d "$DL" ] || DL=/data/local/tmp
OUT="$DL/lockcenter_systemui_dump.txt"
APKDIR="$DL/lockcenter_systemui_apk"
mkdir -p "$APKDIR"
rm -f "$OUT"

dump_strings() {
  if command -v strings >/dev/null 2>&1; then
    strings -n 5 "$1" 2>/dev/null
  else
    tr -c '[:print:]' '\n' < "$1" 2>/dev/null
  fi
}

{
  echo "=== LockCenter: дамп SystemUI ==="
  echo "дата: $(date)"
  echo
  echo "--- прошивка ---"
  getprop ro.build.version.release
  getprop ro.build.version.sdk
  getprop ro.build.id
  getprop ro.build.fingerprint
  getprop ro.product.model
  echo
  echo "--- пакет SystemUI ---"
  pm path com.android.systemui
  dumpsys package com.android.systemui 2>/dev/null | grep -E "versionName|versionCode|codePath|primaryCpuAbi|lastUpdateTime"
  echo
  echo "--- файлы dex/vdex/apex ---"
  find /data/dalvik-cache -iname "*systemui*" 2>/dev/null
  find /apex -ipath "*systemui*" -name "*.apk" 2>/dev/null
  find /data/app -ipath "*systemui*" \( -name "*.vdex" -o -name "*.odex" \) 2>/dev/null
  echo
  echo "--- dumpsys SystemUI: строки про флаги/шейд ---"
  dumpsys activity service com.android.systemui/.SystemUIService 2>/dev/null | grep -iE "flag|shade|split|quick" | head -150
} >> "$OUT" 2>&1

FILES=""
for p in $(pm path com.android.systemui 2>/dev/null | sed 's/^package://'); do
  FILES="$FILES $p"
done
for f in $(find /data/dalvik-cache -iname "*systemui*" 2>/dev/null); do
  FILES="$FILES $f"
done
for f in $(find /data/app -ipath "*systemui*" \( -name "*.vdex" -o -name "*.odex" -o -name "*.oat" \) 2>/dev/null); do
  FILES="$FILES $f"
done

{
  echo
  echo "--- просканированные файлы ---"
  for f in $FILES; do
    echo "$f  $(stat -c %s "$f" 2>/dev/null) байт"
  done
} >> "$OUT" 2>&1

{
  echo
  echo "--- строки, где встречается shade (до 1500) ---"
  for f in $FILES; do dump_strings "$f"; done | grep -i shade | sort -u | head -1500
} >> "$OUT" 2>&1

{
  echo
  echo "--- строки, где встречается split / scene / aconfig (до 800) ---"
  for f in $FILES; do dump_strings "$f"; done | grep -iE "split|scene_container|scenecontainer|aconfig" | sort -u | head -800
} >> "$OUT" 2>&1

{
  echo
  echo "--- копии APK (на случай глубокого анализа) ---"
  i=0
  for p in $(pm path com.android.systemui 2>/dev/null | sed 's/^package://'); do
    i=$((i + 1))
    cp -f "$p" "$APKDIR/systemui_$i.apk" 2>/dev/null && echo "$APKDIR/systemui_$i.apk <- $p"
  done
} >> "$OUT" 2>&1

{
  echo
  echo "--- aflags: scene_container / dual_shade ---"
  aflags list 2>/dev/null | grep -iE "scene_container|dual_shade" | head -40
  aflags list 2>/dev/null | grep -i shade | head -60
  echo "--- settings secure dual_shade ---"
  settings get secure dual_shade 2>/dev/null
} >> "$OUT" 2>&1

chmod 644 "$OUT" 2>/dev/null
chmod -R 644 "$APKDIR" 2>/dev/null
echo "ГОТОВО: $OUT"
