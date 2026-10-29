#!/usr/bin/env python3
"""Build the automatic-dialog-memory runtime from the verified 0.3.19 bundle."""
import hashlib
import io
import json
import sys
import tarfile
from pathlib import Path

source, manifest_file, output, output_manifest = map(Path, sys.argv[1:])
expected = '6a02a148be9f9e1387b9d8e37dde2d3a882ae26d431791151d65f2e9090906ff'
manifest = json.loads(manifest_file.read_text(encoding='utf-8'))
with source.open('rb') as stream:
    actual = hashlib.file_digest(stream, 'sha256').hexdigest()
if actual != expected or manifest['rootfs']['sha256'] != expected or manifest['version'] != '0.3.19-whale-memorypage1':
    raise SystemExit('input runtime mismatch')
if output.exists() or output_manifest.exists():
    raise SystemExit('refusing to overwrite outputs')
plugin = 'opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js'
metadata = 'etc/deepseek-harness-runtime.json'
changes = [
    ('mobile_memory_save 创建待确认候选，返回 pending:true 时请用户到助手设置中确认，不能声称已经记住', 'mobile_memory_save 根据用户设置自动保存或创建待确认候选；只有返回 pending:true 时才请用户到助手设置中确认，不得声称已经记住；返回 pending:false 才可说已保存'),
    ('提出用户希望长期保留的事实或偏好，需用户在助手设置中确认后才能检索。不要保存密码、验证码、令牌及通知原文。', '保存用户希望长期保留的事实或偏好；依据用户设置可能立即保存，或需在助手设置中确认，必须按返回的 pending 状态说明。不要保存密码、验证码、令牌及通知原文。'),
]
seen = set()
delta = 0
version = '0.3.20-whale-dialog-memory'
with tarfile.open(source, 'r|gz') as old, tarfile.open(output, 'x:gz', compresslevel=6, format=tarfile.PAX_FORMAT) as new:
    for item in old:
        if item.name in {plugin, metadata}:
            if not item.isfile() or item.name in seen:
                raise ValueError('unexpected runtime target')
            seen.add(item.name)
            original = old.extractfile(item).read()
            if item.name == plugin:
                if hashlib.sha256(original).hexdigest() != 'bd862eb4da8190598b4c647b305e19bc17c23498067062ae43be7dd3266dbba2':
                    raise ValueError('plugin hash mismatch')
                value = original.decode('utf-8')
                for before, after in changes:
                    if value.count(before) != 1:
                        raise ValueError('plugin patch anchor mismatch')
                    value = value.replace(before, after)
                data = value.encode('utf-8')
            else:
                value = json.loads(original)
                if value.get('runtimeVersion') != manifest['version']:
                    raise ValueError('runtime metadata mismatch')
                value['runtimeVersion'] = version
                data = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
            delta += len(data) - item.size
            item.size = len(data)
            new.addfile(item, io.BytesIO(data))
        elif item.isfile():
            new.addfile(item, old.extractfile(item))
        else:
            new.addfile(item)
if seen != {plugin, metadata}:
    raise ValueError('runtime target missing')
with output.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
manifest['version'] = version
manifest['rootfs'].update(url='https://localhost.invalid/embedded-whale-dialog-memory.bundle', sha256=digest,
                          compressedBytes=output.stat().st_size, extractedBytes=manifest['rootfs']['extractedBytes'] + delta)
with output_manifest.open('x', encoding='utf-8') as stream:
    stream.write(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print(version, digest, output.stat().st_size)
