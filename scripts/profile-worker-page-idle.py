"""Measure task-page bridge reads on a connected device with an installed test APK."""
import argparse
import collections
import re
import subprocess
import sys
import threading

PACKAGE = "io.deepseekharness.mobile"
CASE = PACKAGE + ".companion.WhaleWorkerListPerfTest#installedTaskPageFiltersPagesAndReadsOneActualTimeline"
OPS = ("workerPage", "workerDetail", "screenWatchStatus")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--worker", required=True, help="ID of an existing creator test task")
    parser.add_argument("--seconds", type=int, choices=range(30, 181), default=90, metavar="30..180")
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    marker = re.compile(r"(WhaleIdle-[0-9a-f]+)-(calibrate|start|end)")
    bridge = re.compile(r'"operation"\s*:\s*"(workerPage|workerDetail|screenWatchStatus)"')
    brief = re.compile(r"^[VDIWEF]/(\S+)\s*\(\s*(\d+)\):\s*(.*)")
    events = []
    ended = threading.Event()

    def consume(stream):
        # Retain only operation names and markers. The bridge can also log private task text.
        for line in stream:
            record = brief.match(line)
            if record:
                tag, pid, message = record.groups()
                if tag == "WhaleDeskIdle" and (hit := marker.fullmatch(message.strip())):
                    events.append((pid, hit.group(1), hit.group(2)))
                    if hit.group(2) == "end":
                        ended.set()
                elif tag == "Capacitor" and (hit := bridge.search(message)):
                    events.append((pid, None, hit.group(1)))

    # Stream throughout the interval. Android can overwrite the calibration marker before it ends.
    with subprocess.Popen(adb + ["logcat", "-T", "1", "-v", "brief", "-s", "WhaleDeskIdle:I", "Capacitor:V"],
                          stdout=subprocess.PIPE, encoding="utf-8", errors="replace") as monitor:
        reader = threading.Thread(target=consume, args=(monitor.stdout,), daemon=True)
        reader.start()
        try:
            command = adb + ["shell", "am", "instrument", "-w", "-e", "class", CASE, "-e",
                             "authorWorker", args.worker, "-e", "idleSeconds", str(args.seconds),
                             PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
            report = subprocess.check_output(command, encoding="utf-8", errors="replace", timeout=args.seconds+120)
            sys.stdout.write(report)
            if "OK (1 test)" not in report:
                return 1
            ended.wait(5)
        finally:
            monitor.terminate()
            monitor.wait(timeout=10)
            reader.join(timeout=5)

    pid = re.search(r"profilePid=(\d+)", report).group(1)
    token = re.search(r"idleToken=(WhaleIdle-[0-9a-f]+)", report).group(1)
    phase = None
    markers = []
    counts = collections.Counter()
    for owner, sample, event in events:
        if owner == pid:
            if sample == token:
                markers.append(event)
                phase = event
            elif sample is None:
                counts[phase, event] += 1
    if markers != ["calibrate", "start", "end"] or not counts["calibrate", "workerPage"]:
        raise RuntimeError(f"Missing measurement or manual refresh. markers={markers}, calibrated={counts['calibrate','workerPage']}")
    print("manualRefreshWorkerPageCalls=" + str(counts["calibrate", "workerPage"]))
    for op in OPS:
        print(f"idle_{op}_calls={counts['start', op]}")
    return int(any(counts["start", op] for op in OPS))


if __name__ == "__main__":
    sys.exit(main())
