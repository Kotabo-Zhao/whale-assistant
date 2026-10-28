#!/usr/bin/env python3
"""Rebuild a debug APK from a verified base using current DEX and frontend assets.

The caller must first verify the source snapshot and DEX-entry mapping. This tool
never modifies the base, DEX, assets, or an existing output; zipalign and signing
are separate steps. This is a development fallback, not a release build.
"""
import hashlib
import json
import shutil
import sys
import zipfile
from pathlib import Path

base, mapping_file, build, dist, plugin, output = map(Path, sys.argv[1:7])
BASE_SHA = '3ae33e6c63efe7da890d3ac52bd8e356e8b30528298f24be72f6e02190c08c01'
with base.open('rb') as stream:
    if hashlib.file_digest(stream, 'sha256').hexdigest() != BASE_SHA: raise SystemExit('base APK hash mismatch')
if output.exists(): raise SystemExit('output already exists')
manifest = json.loads(mapping_file.read_text(encoding='utf-8'))
if manifest.get('base') != '5c22000' or not isinstance(manifest.get('dex'), dict): raise SystemExit('invalid DEX mapping')
build = build.resolve()
replacements = {}
for name, relative in manifest['dex'].items():
    if not name.startswith('classes') or not name.endswith('.dex') or '/' in name: raise SystemExit('invalid DEX entry')
    target = (build / relative).resolve()
    if not target.is_relative_to(build / 'intermediates/dex/debug') or not target.is_file(): raise SystemExit('invalid DEX path')
    replacements[name] = target
actual_dex = {p.resolve() for p in (build / 'intermediates/dex/debug').rglob('classes*.dex')}
if actual_dex != set(replacements.values()) or len(actual_dex) != len(replacements): raise SystemExit('DEX set changed')
if not plugin.is_file() or plugin.is_symlink(): raise SystemExit('plugin asset missing')
replacements['assets/support/plugin-manager.cjs'] = plugin
if not (dist / 'index.html').is_file(): raise SystemExit('frontend index missing')
replacements['assets/public/index.html'] = dist / 'index.html'
assets = dist / 'assets'
if not assets.is_dir(): raise SystemExit('frontend assets missing')
for item in assets.iterdir():
    if not item.is_file() or item.is_symlink(): raise SystemExit('invalid frontend asset')
    replacements['assets/public/assets/' + item.name] = item
if len(replacements) <= len(manifest['dex']) + 2: raise SystemExit('empty frontend assets')

def signature(name):
    upper = name.upper()
    return upper.startswith('META-INF/') and (upper.endswith(('.RSA', '.DSA', '.EC', '.SF')) or upper == 'META-INF/MANIFEST.MF')

created = False
try:
    with zipfile.ZipFile(base) as old:
        old_names = old.namelist()
        if len(old_names) != len(set(old_names)): raise ValueError('duplicate base entry')
        old_dex = {n for n in old_names if n.startswith('classes') and n.endswith('.dex')}
        if old_dex != set(manifest['dex']): raise ValueError('base DEX entries changed')
        if not {'assets/public/index.html', 'assets/support/plugin-manager.cjs'} <= set(old_names): raise ValueError('base assets missing')
        with output.open('xb') as destination:
            created = True
            with zipfile.ZipFile(destination, 'w', allowZip64=True) as new:
                for item in old.infolist():
                    name = item.filename
                    if signature(name) or name.startswith('assets/public/assets/'): continue
                    source = replacements.get(name)
                    with (source.open('rb') if source else old.open(item)) as src, new.open(item, 'w') as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)
                for name, source in sorted(replacements.items()):
                    if not name.startswith('assets/public/assets/'): continue
                    info = zipfile.ZipInfo(name)
                    info.compress_type = zipfile.ZIP_DEFLATED
                    info.external_attr = 0o100644 << 16
                    with source.open('rb') as src, new.open(info, 'w') as dst:
                        shutil.copyfileobj(src, dst, 1024 * 1024)
except BaseException:
    if created: output.unlink(missing_ok=True)
    raise
print('Development APK rebuilt from verified base; DEX entries:', len(manifest['dex']))
