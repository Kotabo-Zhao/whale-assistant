#!/usr/bin/env python3
"""Compare published market peer declarations with archive metadata, without running the guest."""
import base64
import hashlib
import json
import posixpath
import subprocess
import sys
import tarfile
from pathlib import Path

runtime, runtime_manifest, market_archive = map(Path, sys.argv[1:4])
metadata = json.loads(runtime_manifest.read_text(encoding='utf-8'))
with runtime.open('rb') as stream:
    if hashlib.file_digest(stream, 'sha256').hexdigest() != metadata['rootfs']['sha256']: raise ValueError('runtime hash mismatch')
with market_archive.open('rb') as stream:
    expected = 'fUkE+QoumMh4OLqT97iq1Vc75PT+EQgXYVLl0IXcw7tTpihRut1qRufEx5EAbGF+4+mqNEDehBnL+xwLQSstVw=='
    if base64.b64encode(hashlib.file_digest(stream, 'sha512').digest()).decode() != expected: raise ValueError('market archive hash mismatch')
with tarfile.open(market_archive, 'r:gz') as tar:
    matches = [x for x in tar if x.name == 'package/package.json']
    if len(matches) != 1 or not matches[0].isfile() or matches[0].size > 512000: raise ValueError('market metadata unavailable')
    pkg = json.load(tar.extractfile(matches[0]))
if pkg.get('name') != 'dshmarket' or pkg.get('version') != '1.73.3': raise ValueError('market identity mismatch')
peers = pkg.get('peerDependencies', {})
clients = pkg.get('dsh', {}).get('client', {}).get('inject', [])
if not isinstance(peers, dict) or not isinstance(clients, list): raise ValueError('unsupported declarations')
names = set(peers) | set(clients)
if len(names) > 30 or any(not isinstance(x, str) or not x.startswith('@deepseek-ai/') or '/' in x[len('@deepseek-ai/'):] for x in names): raise ValueError('unsupported names')
profile = {f'root/.dsh/profiles/node_modules/{name}': name for name in names}
links = {}; package_files = {}; guest = None
with tarfile.open(runtime, 'r|gz') as archive:
    for entry in archive:
        if entry.name in profile:
            if entry.name in links or not entry.issym(): raise ValueError('invalid or duplicate profile link')
            target = posixpath.normpath(posixpath.join(posixpath.dirname(entry.name), entry.linkname))
            if not target.startswith('opt/dsh/'): raise ValueError('profile link escapes runtime')
            links[entry.name] = target + '/package.json'
        elif entry.name == 'etc/deepseek-harness-runtime.json':
            if guest is not None or not entry.isfile() or entry.size > 4096: raise ValueError('invalid runtime metadata')
            guest = json.load(archive.extractfile(entry))
        elif entry.isfile() and entry.size <= 262144 and entry.name.endswith('/package.json') and '/node_modules/@deepseek-ai/' in entry.name:
            if not any(entry.name.endswith('/node_modules/' + name + '/package.json') for name in names): continue
            data = json.load(archive.extractfile(entry))
            if data.get('name') in names:
                if entry.name in package_files: raise ValueError('duplicate package manifest')
                package_files[entry.name] = data
if guest is None or guest.get('runtimeVersion') != metadata.get('version'): raise ValueError('runtime version mismatch')
results=[]
for link, name in sorted(profile.items()):
    item = package_files.get(links.get(link))
    if not item or item.get('name') != name or not isinstance(item.get('version'), str): raise ValueError(f'{name}: package link unavailable')
    results.append({'name': name, 'version': item['version'], 'peerRange': peers.get(name), 'clientInjection': name in clients})
js = "const {satisfiesRange}=require(process.argv[1]);const rows=JSON.parse(process.argv[2]);console.log(JSON.stringify(rows.map(x=>x.peerRange===null?null:satisfiesRange(x.version,x.peerRange))));"
manager = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/support/plugin-manager.cjs'
checks = json.loads(subprocess.check_output(['node', '-e', js, str(manager), json.dumps(results)], text=True))
for item, accepted in zip(results, checks): item['peerRangeMatches'] = accepted
print(json.dumps({'runtime': guest['runtimeVersion'], 'nodeVersion': guest['nodeVersion'], 'market': pkg['version'], 'packages': results}, ensure_ascii=False, indent=2))
if any(item['peerRangeMatches'] is False for item in results): raise SystemExit(1)
