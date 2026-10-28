#!/usr/bin/env python3
"""Verify all non-replaced entries and exact DEX/plugin bytes in a refreshed APK."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

base, mapping_file, build, plugin, apk = map(Path, sys.argv[1:6])
mapping = json.loads(mapping_file.read_text(encoding='utf-8'))['dex']

def signature(name):
    upper = name.upper()
    return upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF')

def asset(name): return name.startswith('assets/public/assets/') or name == 'assets/public/index.html'
def digest(stream): return hashlib.file_digest(stream, 'sha256').digest()

with zipfile.ZipFile(base) as old, zipfile.ZipFile(apk) as new:
    if len(old.namelist()) != len(set(old.namelist())) or len(new.namelist()) != len(set(new.namelist())): raise SystemExit('duplicate ZIP entries')
    a = {item.filename:item for item in old.infolist() if not signature(item.filename) and not asset(item.filename)}
    b = {item.filename:item for item in new.infolist() if not signature(item.filename) and not asset(item.filename)}
    if a.keys() != b.keys(): raise SystemExit('unexpected entry changes')
    for name, item in a.items():
        other = b[name]
        if item.compress_type != other.compress_type: raise SystemExit('compression mismatch: ' + name)
        if name in mapping:
            source = build / mapping[name]
            with new.open(other) as actual, source.open('rb') as expected:
                if digest(actual) != digest(expected): raise SystemExit('DEX mismatch: ' + name)
        elif name == 'assets/support/plugin-manager.cjs':
            with new.open(other) as actual, plugin.open('rb') as expected:
                if digest(actual) != digest(expected): raise SystemExit('plugin manager mismatch')
        else:
            with old.open(item) as expected, new.open(other) as actual:
                if item.file_size != other.file_size or digest(expected) != digest(actual): raise SystemExit('unexpected content change: ' + name)
print('Verified all non-frontend entries and current DEX/plugin:', len(a))
