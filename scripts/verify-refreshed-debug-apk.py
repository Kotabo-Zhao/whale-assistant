#!/usr/bin/env python3
"""Verify non-frontend entries and exact DEX, plugin and draft bytes in a refreshed APK."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

if len(sys.argv) not in (7, 9): raise SystemExit('expected base mapping build plugin draft apk [runtime manifest]')
base, mapping_file, build, plugin, draft_insert, apk = map(Path, sys.argv[1:7])
runtime_pair = tuple(map(Path, sys.argv[7:9]))
mapping = json.loads(mapping_file.read_text(encoding='utf-8'))['dex']
if runtime_pair:
    runtime, runtime_manifest = runtime_pair
    metadata = json.loads(runtime_manifest.read_text(encoding='utf-8'))
    if metadata.get('version') != '0.3.19-whale-memorypage1' or metadata['rootfs']['compressedBytes'] != runtime.stat().st_size: raise SystemExit('runtime manifest mismatch')
    with runtime.open('rb') as f:
        if hashlib.file_digest(f, 'sha256').hexdigest() != metadata['rootfs']['sha256']: raise SystemExit('runtime hash mismatch')

def signature(name):
    upper = name.upper()
    return upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF')

def asset(name): return name.startswith('assets/public/assets/') or name == 'assets/public/index.html'
def digest(stream): return hashlib.file_digest(stream, 'sha256').digest()

with zipfile.ZipFile(base) as old, zipfile.ZipFile(apk) as new:
    if len(old.namelist()) != len(set(old.namelist())) or len(new.namelist()) != len(set(new.namelist())): raise SystemExit('duplicate ZIP entries')
    a = {item.filename:item for item in old.infolist() if not signature(item.filename) and not asset(item.filename)}
    b = {item.filename:item for item in new.infolist() if not signature(item.filename) and not asset(item.filename)}
    removed = {'assets/support/harness-voice-read.js', 'assets/support/harness-voice-insert.js'}
    if (a.keys() - removed) | {'assets/support/harness-draft-insert.js'} != b.keys(): raise SystemExit('unexpected entry changes')
    with new.open('assets/support/harness-draft-insert.js') as actual, draft_insert.open('rb') as expected:
        if digest(actual) != digest(expected): raise SystemExit('draft asset mismatch')
    required = set(mapping) | {'assets/support/plugin-manager.cjs'}
    if runtime_pair: required |= {'assets/runtime/rootfs.bundle', 'assets/runtime/runtime-manifest.json'}
    if not required <= a.keys(): raise SystemExit('required DEX or support asset missing')
    if runtime_pair and b['assets/runtime/rootfs.bundle'].compress_type != zipfile.ZIP_STORED: raise SystemExit('runtime is compressed')
    for name, item in a.items():
        if name in removed: continue
        other = b[name]
        if item.compress_type != other.compress_type: raise SystemExit('compression mismatch: ' + name)
        if name in mapping:
            source = build / mapping[name]
            with new.open(other) as actual, source.open('rb') as expected:
                if digest(actual) != digest(expected): raise SystemExit('DEX mismatch: ' + name)
        elif name == 'assets/support/plugin-manager.cjs' or (runtime_pair and name in ('assets/runtime/rootfs.bundle', 'assets/runtime/runtime-manifest.json')):
            source = (runtime_pair[0] if name.endswith('rootfs.bundle') else runtime_pair[1]) if name.startswith('assets/runtime/') else plugin
            with new.open(other) as actual, source.open('rb') as expected:
                if digest(actual) != digest(expected): raise SystemExit('support asset mismatch: ' + name)
        else:
            with old.open(item) as expected, new.open(other) as actual:
                if item.file_size != other.file_size or digest(expected) != digest(actual): raise SystemExit('unexpected content change: ' + name)
print('Verified all non-frontend entries and current DEX/plugin/draft:', len(a))
