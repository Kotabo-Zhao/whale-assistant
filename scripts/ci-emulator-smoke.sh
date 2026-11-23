#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:?Usage: ci-emulator-smoke.sh <apk-path> <out-dir>}"
OUT_DIR="${2:-smoke-out}"
PKG="io.deepseekharness.mobile"

mkdir -p "$OUT_DIR"
REPORT="$OUT_DIR/smoke-test-report.txt"
LOGCAT="$OUT_DIR/smoke-logcat.txt"

echo "=== Whale Assistant Cloud Emulator Smoke Test ===" | tee "$REPORT"
echo "Time (UTC): $(date -u '+%Y-%m-%d %H:%M:%S')" | tee -a "$REPORT"
echo "APK: $APK_PATH" | tee -a "$REPORT"
echo "Device: $(adb shell getprop ro.product.model | tr -d '\r') (Android $(adb shell getprop ro.build.version.release | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))" | tee -a "$REPORT"

# Wake screen and dismiss lockscreen
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb logcat -c || true

echo "[1/5] Installing Lite APK..." | tee -a "$REPORT"
adb install -r -g "$APK_PATH" | tee -a "$REPORT"

echo "[2/5] Granting overlay permission (SYSTEM_ALERT_WINDOW)..." | tee -a "$REPORT"
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true

echo "[3/5] Launching $PKG..." | tee -a "$REPORT"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 | tee -a "$REPORT"
sleep 8

PID="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
echo "App PID after 8s: ${PID:-<NOT RUNNING>}" | tee -a "$REPORT"

echo "[4/5] Capturing home screenshot & UI hierarchy..." | tee -a "$REPORT"
adb exec-out screencap -p > "$OUT_DIR/smoke-home.png" || true
adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
adb pull /sdcard/window_dump.xml "$OUT_DIR/smoke-ui-tree.xml" >/dev/null 2>&1 || true

# Exercise basic touch & back navigation to verify UI thread responsiveness
adb shell input tap 540 1900 || true
sleep 2
adb exec-out screencap -p > "$OUT_DIR/smoke-after-tap.png" || true

PID_AFTER="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
echo "App PID after interaction: ${PID_AFTER:-<NOT RUNNING>}" | tee -a "$REPORT"

echo "[5/5] Inspecting logcat for crashes/ANRs..." | tee -a "$REPORT"
adb logcat -d -v time > "$LOGCAT" || true

FATAL_COUNT="$(grep -E "FATAL EXCEPTION|ANR in ${PKG}|SIGSEGV.*${PKG}|UnsatisfiedLinkError" "$LOGCAT" | grep -vc "FATAL EXCEPTION: UiAutomation" || true)"
JS_ERR_COUNT="$(grep -cE "Capacitor/Console:.*(Uncaught|TypeError|ReferenceError)" "$LOGCAT" || true)"

echo "Fatal/ANR matches: $FATAL_COUNT" | tee -a "$REPORT"
echo "WebView JS error matches: $JS_ERR_COUNT" | tee -a "$REPORT"

if [ "$FATAL_COUNT" -gt 0 ]; then
  echo "--- Fatal/ANR excerpt ---" | tee -a "$REPORT"
  grep -n -C 5 -E "FATAL EXCEPTION|ANR in ${PKG}|SIGSEGV.*${PKG}|UnsatisfiedLinkError" "$LOGCAT" | head -n 60 | tee -a "$REPORT"
fi

if [ "$JS_ERR_COUNT" -gt 0 ]; then
  echo "--- WebView JS error excerpt ---" | tee -a "$REPORT"
  grep -n -E "Capacitor/Console:.*(Uncaught|TypeError|ReferenceError)" "$LOGCAT" | head -n 30 | tee -a "$REPORT"
fi

if [ -z "$PID" ] || [ -z "$PID_AFTER" ] || [ "$FATAL_COUNT" -gt 0 ]; then
  echo "RESULT: FAILED" | tee -a "$REPORT"
  exit 1
fi

echo "RESULT: PASSED" | tee -a "$REPORT"
