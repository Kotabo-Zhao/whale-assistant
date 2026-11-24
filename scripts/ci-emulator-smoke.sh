#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:?Usage: ci-emulator-smoke.sh <apk-path> <out-dir>}"
OUT_DIR="${2:-smoke-out}"
PKG="io.deepseekharness.mobile"

mkdir -p "$OUT_DIR"
REPORT="$OUT_DIR/smoke-test-report.txt"
LOGCAT="$OUT_DIR/smoke-logcat.txt"

tap_by_label() {
  local patterns="$1"
  adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window_dump.xml "$OUT_DIR/tmp_tree.xml" >/dev/null 2>&1 || return 1
  local coords
  coords="$(python3 - "$OUT_DIR/tmp_tree.xml" "$patterns" << 'PY'
import re, sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
pats = [p.strip().lower() for p in sys.argv[2].split("|") if p.strip()]
for node in tree.iter("node"):
    txt = (node.attrib.get("text") or "").lower()
    desc = (node.attrib.get("content-desc") or "").lower()
    hay = f"{txt} {desc}".strip()
    if any(p in hay for p in pats):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 > x1 and y2 > y1:
                print(f"{(x1+x2)//2} {(y1+y2)//2}")
                sys.exit(0)
sys.exit(1)
PY
  )" || return 1
  echo "  [tap] matched ($patterns) -> $coords" | tee -a "$REPORT"
  adb shell input tap $coords || true
  return 0
}

echo "=== Whale Assistant Cloud Emulator Smoke & Visual Audit ===" | tee "$REPORT"
echo "Time (UTC): $(date -u '+%Y-%m-%d %H:%M:%S')" | tee -a "$REPORT"
echo "APK: $APK_PATH" | tee -a "$REPORT"
echo "Device: $(adb shell getprop ro.product.model | tr -d '\r') (Android $(adb shell getprop ro.build.version.release | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))" | tee -a "$REPORT"

# Wake screen, dismiss lockscreen & any system launcher ANR popups
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
adb logcat -c || true

echo "[1/6] Installing Lite APK..." | tee -a "$REPORT"
adb install -r -g "$APK_PATH" | tee -a "$REPORT"

echo "[2/6] Granting overlay & notification permissions..." | tee -a "$REPORT"
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

echo "[3/6] Launching $PKG and waiting for WebView ready..." | tee -a "$REPORT"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 | tee -a "$REPORT"
sleep 14

# Dismiss Pixel Launcher ANR popup if Android cold boot showed one
tap_by_label "Wait|Close app" && sleep 2 || true

PID="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
echo "App PID after 14s: ${PID:-<NOT RUNNING>}" | tee -a "$REPORT"

echo "[4/6] Dismissing onboarding sheet & capturing real visual screenshots..." | tee -a "$REPORT"
adb exec-out screencap -p > "$OUT_DIR/smoke-home.png" || true

# Close Welcome onboarding sheet (close button or Back)
if ! tap_by_label "关闭|close|×|跳过|skip"; then
  adb shell input tap 956 1525 || true
fi
sleep 3
adb exec-out screencap -p > "$OUT_DIR/visual-cute-chat.png" || true
cp "$OUT_DIR/visual-cute-chat.png" "$OUT_DIR/smoke-after-tap.png" || true

# Switch to "Me / 我的" tab
if ! tap_by_label "我的|me|settings"; then
  adb shell input tap 760 2230 || true
fi
sleep 3
adb exec-out screencap -p > "$OUT_DIR/visual-cute-me.png" || true

# Open Appearance / 外观 to capture 3-world theme cards & test switching themes
if tap_by_label "外观|appearance|主题|theme"; then
  sleep 2
  adb exec-out screencap -p > "$OUT_DIR/visual-appearance.png" || true

  # Switch to Usagi theme and capture Chat Home
  if tap_by_label "乌萨奇|usagi"; then
    sleep 2
    adb shell input keyevent KEYCODE_BACK || true
    sleep 2
    if ! tap_by_label "对话|chat"; then
      adb shell input tap 320 2230 || true
    fi
    sleep 3
    adb exec-out screencap -p > "$OUT_DIR/visual-usagi-chat.png" || true
  fi

  # Switch back to Me -> Appearance -> Simple theme and capture Chat Home
  if ! tap_by_label "我的|me"; then
    adb shell input tap 760 2230 || true
  fi
  sleep 2
  if tap_by_label "外观|appearance|主题|theme"; then
    sleep 2
    if tap_by_label "极简|simple|grok"; then
      sleep 2
      adb shell input keyevent KEYCODE_BACK || true
      sleep 2
      if ! tap_by_label "对话|chat"; then
        adb shell input tap 320 2230 || true
      fi
      sleep 3
      adb exec-out screencap -p > "$OUT_DIR/visual-simple-chat.png" || true
    fi
  fi
fi

adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
adb pull /sdcard/window_dump.xml "$OUT_DIR/smoke-ui-tree.xml" >/dev/null 2>&1 || true

echo "[5/6] Checking process health..." | tee -a "$REPORT"
PID_AFTER="$(adb shell pidof "$PKG" | tr -d '\r' || true)"
echo "App PID after visual audit: ${PID_AFTER:-<NOT RUNNING>}" | tee -a "$REPORT"

echo "[6/6] Inspecting logcat for crashes/ANRs..." | tee -a "$REPORT"
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
