"""Sample the Pocket S application process group using the same method before/after."""
import argparse
import json
import re
import statistics
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--duration', type=int, default=1800)
parser.add_argument('--interval', type=int, default=30)
parser.add_argument('--core-only', action='store_true', help='Legacy comparison without isolated WebView renderers')
args = parser.parse_args()

def shell(*command):
    return subprocess.check_output([args.adb, '-s', args.serial, 'shell', *command], timeout=30).decode('utf-8', 'replace')

package_uid = re.search(r'^package:io\.deepseekharness\.mobile uid:(\d+)\r?$', shell('pm','list','packages','-U','io.deepseekharness.mobile'),re.M)
if not package_uid:
    raise SystemExit('Whale Assistant package UID unavailable')
uid = int(package_uid[1])
owner = 'u' + str(uid // 100000) + 'a' + str(uid % 100000 - 10000)
samples = []
started = time.monotonic()
previous = {}
while True:
    now = time.monotonic()
    processes = []
    for line in shell('ps', '-A', '-o', 'PID,PPID,UID,NAME').splitlines()[1:]:
        fields = line.split(None, 3)
        if len(fields) == 4:
            processes.append((int(fields[0]), int(fields[1]), int(fields[2]), fields[3]))
    chosen = {pid for pid, _, process_uid, _ in processes if process_uid == uid}
    while True:
        children = {pid for pid, parent, _, _ in processes if parent in chosen}
        if children <= chosen:
            break
        chosen.update(children)
    core = set(chosen)
    if not args.core_only:
        hosted = shell('dumpsys','activity','processes')
        chosen.update(int(pid) for pid in re.findall(r'ProcessRecord\{[^\s]+\s+(\d+):[^\s]*?/' + re.escape(owner) + r'i\d+\}',hosted))
    details = []
    for pid, _, _, name in processes:
        if pid not in chosen:
            continue
        try:
            memory = shell('dumpsys', 'meminfo', str(pid))
            match = re.search(r'TOTAL PSS:\s*([\d,]+)', memory)
            if not match:
                continue
            pss = int(match[1].replace(',', ''))
            cpu = shell('cat', '/proc/' + str(pid) + '/stat')
            fields = cpu[cpu.rindex(')') + 2:].split()
            ticks = int(fields[11]) + int(fields[12])
            old = previous.get(pid)
            percent = 100 * (ticks - old[1]) / (100 * (now - old[0])) if old else None
            previous[pid] = (now, ticks)
            details.append({'pid': pid, 'name': name, 'group': 'core' if pid in core else 'renderer', 'pssKb': pss, 'cpuOneCorePercent': percent})
        except (subprocess.SubprocessError, ValueError, IndexError):
            continue
    samples.append({'elapsedSeconds': round(time.monotonic() - started, 1), 'processes': details,
                    'totalPssKb': sum(row['pssKb'] for row in details),
                    'cpuOneCorePercent': sum(row['cpuOneCorePercent'] or 0 for row in details),
                    'corePssKb': sum(row['pssKb'] for row in details if row['group']=='core'),
                    'coreCpuOneCorePercent': sum(row['cpuOneCorePercent'] or 0 for row in details if row['group']=='core')})
    report = {'method': 'Application package plus node/proot descendants and isolated renderers hosted for the package UID (unless core-only). CPU at Android USER_HZ=100. core* fields match the original pre-change method.',
              'durationSeconds': samples[-1]['elapsedSeconds'], 'samples': samples}
    values = [row['totalPssKb'] for row in samples]
    report['summary'] = {'medianPssKb': statistics.median(values), 'peakPssKb': max(values),
                         'medianCpuOneCorePercent': statistics.median(row['cpuOneCorePercent'] for row in samples[1:]) if len(samples) > 1 else None}
    report['summary']['medianCorePssKb'] = statistics.median(row['corePssKb'] for row in samples)
    report['summary']['medianCoreCpuOneCorePercent'] = statistics.median(row['coreCpuOneCorePercent'] for row in samples[1:]) if len(samples)>1 else None
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    if time.monotonic() - started >= args.duration:
        print(json.dumps(report['summary']))
        break
    time.sleep(min(args.interval, args.duration - (time.monotonic() - started)))
