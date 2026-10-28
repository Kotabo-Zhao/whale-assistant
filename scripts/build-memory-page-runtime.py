#!/usr/bin/env python3
"""Build a development runtime from the pinned APK, without extracting its rootfs."""
import hashlib
import io
import json
import sys
import tarfile
import zipfile
from pathlib import Path

apk, plugin_path, output, output_manifest = map(Path, sys.argv[1:])
old_hash = '3a442b514e0ad9e58a920fe24265ca964c6d38d39b1a701b555ce37b68c06965'
old_plugin_hash = 'b2cd9e0591435d567b4756c056360d6db48fd8fe270fa0d793e5800faf0cc687'
version = '0.3.19-whale-memorypage1'
plugin_target = 'opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js'
metadata_target = 'etc/deepseek-harness-runtime.json'
targets = {plugin_target, metadata_target}
plugin = plugin_path.read_bytes()
if not all(marker in plugin for marker in (b'mobile_memory_search', b'nextOffset', b'mobile_knowledge_search', b'mobile_recurring_create')):
    raise SystemExit('expected mobile tools missing')
if output.exists() or output_manifest.exists(): raise SystemExit('refusing to overwrite output')
created = False
try:
    with zipfile.ZipFile(apk) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())): raise ValueError('duplicate APK entry')
        manifest = json.loads(archive.read('assets/runtime/runtime-manifest.json'))
        info = archive.getinfo('assets/runtime/rootfs.bundle')
        if manifest['version'] != '0.3.18-whale-knowledge1' or manifest['rootfs']['sha256'] != old_hash or info.compress_type != zipfile.ZIP_STORED:
            raise ValueError('unexpected base runtime')
        with archive.open(info) as f:
            if hashlib.file_digest(f, 'sha256').hexdigest() != old_hash: raise ValueError('base hash mismatch')
        seen = set(); delta = 0
        with archive.open(info) as stream, tarfile.open(fileobj=stream, mode='r|gz') as old, output.open('xb') as dest:
            created = True
            with tarfile.open(fileobj=dest, mode='w|gz', compresslevel=6, format=tarfile.PAX_FORMAT) as new:
                for item in old:
                    if item.name in targets:
                        if not item.isfile() or item.name in seen: raise ValueError('duplicate or invalid target')
                        seen.add(item.name)
                        before = old.extractfile(item).read()
                        if item.name == plugin_target:
                            if hashlib.sha256(before).hexdigest() != old_plugin_hash: raise ValueError('base plugin mismatch')
                            data = plugin
                        else:
                            metadata = json.loads(before)
                            if metadata.get('runtimeVersion') != manifest['version']: raise ValueError('runtime metadata mismatch')
                            metadata['runtimeVersion'] = version
                            data = (json.dumps(metadata, ensure_ascii=False, indent=2) + '\n').encode()
                        delta += len(data) - item.size; item.size = len(data)
                        new.addfile(item, io.BytesIO(data))
                    elif item.isfile(): new.addfile(item, old.extractfile(item))
                    else: new.addfile(item)
        if seen != targets: raise ValueError('runtime target missing')
    with output.open('rb') as f: digest = hashlib.file_digest(f, 'sha256').hexdigest()
    manifest['version'] = version
    manifest['rootfs'].update(url='https://localhost.invalid/embedded-whale-memorypage1.bundle', sha256=digest,
                              compressedBytes=output.stat().st_size, extractedBytes=manifest['rootfs']['extractedBytes'] + delta)
    with output_manifest.open('x', encoding='utf-8') as f: f.write(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
except BaseException:
    if created: output.unlink(missing_ok=True)
    raise
print('built', version, digest, output.stat().st_size)
