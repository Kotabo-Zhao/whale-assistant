#!/system/bin/sh
# Run with the AYANEO shell UID. No connection to the workstation is needed after launch.
set -eu
mode=${1:-baseline}
seconds=${2:-30}
cycle=${3:-false}
case "$mode/$cycle" in baseline/false|baseline/true|discharge/false) ;; *) exit 2;; esac
case "$seconds" in ''|*[!0-9]*) exit 2;; esac
case "$mode" in
  baseline) [ "$seconds" -ge 30 ] && [ "$seconds" -le 600 ] || exit 2 ;;
  discharge) [ "$seconds" -ge 1800 ] && [ "$seconds" -le 3600 ] || exit 2 ;;
  *) exit 2 ;;
esac
pkg=io.deepseekharness.mobile
dir=$(mktemp -d /data/local/tmp/whale-power.XXXXXX)
echo "$dir"
cd "$dir"
prior=$(cmd appops get "$pkg" SYSTEM_ALERT_WINDOW | sed -n 's/^SYSTEM_ALERT_WINDOW: \([a-z]*\).*/\1/p')
case "$prior" in allow|ignore|deny|default|foreground) ;; *) echo "Cannot identify original app-op"; exit 1;; esac
timeout=$(settings get system screen_off_timeout)
case "$timeout" in null) ;; ''|*[!0-9]*) exit 1;; esac
printf '%s\n' "$prior" > original-overlay-mode
printf '%s\n' "$timeout" > original-screen-timeout
restore() {
  cmd appops set "$pkg" SYSTEM_ALERT_WINDOW "$prior"
  if [ "$timeout" = null ]; then settings delete system screen_off_timeout >/dev/null
  else settings put system screen_off_timeout "$timeout"; fi
}
trap restore EXIT
trap 'exit 1' INT TERM
cmd appops set "$pkg" SYSTEM_ALERT_WINDOW allow
settings put system screen_off_timeout "$(((seconds*2+400)*1000))"
hardware() {
  now=$(cut -d' ' -f1 /proc/uptime)
  usb=$(cat /sys/class/power_supply/usb/online)
  wireless=$(cat /sys/class/power_supply/wireless/online)
  charger=$(cat /sys/class/power_supply/ts_charger/online)
  charge=$(cat /sys/class/power_supply/battery/charge_counter)
  temp=$(cat /sys/class/power_supply/battery/temp)
  printf '%s,%s,%s,%s,%s,%s\n' "$now" "$usb" "$wireless" "$charger" "$charge" "$temp" >> hardware.csv
}
printf '%s\n' 'elapsed_s,usb,wireless,charger,charge_uAh,temp_deciC' > hardware.csv
hardware
if [ "$mode" = discharge ]; then
  echo WAITING_FOR_PHYSICAL_DISCONNECT > status
  remaining=60
  while [ "$usb$wireless$charger" != 000 ]; do
    if [ "$remaining" -eq 0 ]; then echo NO_DISCHARGE > status; exit 1; fi
    sleep 5; remaining=$((remaining-1)); hardware
  done
fi
echo SAMPLING > status
am instrument -w -e powerMode "$mode" -e sampleSeconds "$seconds" -e screenCycle "$cycle" -e class \
  "$pkg.companion.WhalePetRenderDeviceTest#profileVisibleAndHiddenOverlay" \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > instrumentation.log 2>&1 &
child=$!
mixed=0
while kill -0 "$child" 2>/dev/null; do
  sleep 15
  hardware
  if [ "$mode" = discharge ] && [ "$usb$wireless$charger" != 000 ]; then mixed=1; fi
done
wait "$child" || true
if [ "$mixed" -eq 0 ] && grep -q '^OK (1 test)' instrumentation.log; then echo COMPLETE > status
else echo INVALID > status; exit 1; fi
