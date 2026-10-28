#!/usr/bin/env python3
"""Compare every runtime entry against the pinned APK, except two verified replacements."""
import hashlib
import json
import sys
import tarfile
import zipfile
from itertools import zip_longest
from pathlib import Path

apk, bundle, plugin, manifest_path = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
version = '0.3.19-whale-memorypage1'
if manifest['version'] != version or bundle.stat().st_size != manifest['rootfs']['compressedBytes']:
    raise SystemExit('runtime manifest mismatch')
with bundle.open('rb') as f:
    if hashlib.file_digest(f, 'sha256').hexdigest() != manifest['rootfs']['sha256']: raise SystemExit('runtime hash mismatch')
plugin_target = 'opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js'
metadata_target = 'etc/deepseek-harness-runtime.json'
seen = set(); extracted = 0; count = 0
with zipfile.ZipFile(apk) as archive, archive.open('assets/runtime/rootfs.bundle') as source, \
        tarfile.open(fileobj=source, mode='r|gz') as old, tarfile.open(bundle, mode='r|gz') as new:
    previous = json.loads(archive.read('assets/runtime/runtime-manifest.json'))
    if previous['version'] != '0.3.18-whale-knowledge1' or previous['rootfs']['sha256'] != '3a442b514e0ad9e58a920fe24265ca964c6d38d39b1a701b555ce37b68c06965':
        raise SystemExit('unexpected base manifest')
    previous['version'] = version
    for key in ('url', 'sha256', 'compressedBytes', 'extractedBytes'):
        previous['rootfs'][key] = manifest['rootfs'][key]
    if previous != manifest: raise SystemExit('runtime manifest changed unexpectedly')
    for left, right in zip_longest(old, new):
        if left is None or right is None: raise SystemExit('entry count mismatch')
        meta = lambda x: (x.name, x.type, x.linkname, x.mode, x.uid, x.gid, x.mtime)
        if meta(left) != meta(right): raise SystemExit('entry metadata mismatch: ' + left.name)
        count += 1
        if not left.isfile(): continue
        extracted += right.size
        if left.name in (plugin_target, metadata_target):
            if left.name in seen: raise SystemExit('duplicate target')
            seen.add(left.name)
            if left.name == plugin_target:
                if new.extractfile(right).read() != plugin.read_bytes(): raise SystemExit('plugin mismatch')
            else:
                before = json.load(old.extractfile(left)); after = json.load(new.extractfile(right))
                before['runtimeVersion'] = version
                if before != after: raise SystemExit('runtime metadata changed unexpectedly')
            continue
        if left.size != right.size: raise SystemExit('entry size mismatch: ' + left.name)
        one = hashlib.sha256(); two = hashlib.sha256()
        a = old.extractfile(left); b = new.extractfile(right)
        for chunk in iter(lambda: a.read(1048576), b''): one.update(chunk)
        for chunk in iter(lambda: b.read(1048576), b''): two.update(chunk)
        if one.digest() != two.digest(): raise SystemExit('entry mismatch: ' + left.name)
if seen != {plugin_target, metadata_target} or extracted != manifest['rootfs']['extractedBytes']:
    raise SystemExit('target or extracted size mismatch')
print('verified runtime entries:', count, 'extracted bytes:', extracted)
