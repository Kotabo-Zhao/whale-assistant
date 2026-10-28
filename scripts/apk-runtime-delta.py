#!/usr/bin/env python3
"""Losslessly archive APKs sharing the same stored runtime entry; never remove inputs."""
import argparse
import hashlib
import json
import shutil
import struct
import zipfile
from pathlib import Path

ENTRY = 'assets/runtime/rootfs.bundle'
CHUNK = 1024 * 1024


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def runtime_range(path):
    with zipfile.ZipFile(path) as archive, path.open('rb') as stream:
        entries = [item for item in archive.infolist() if item.filename == ENTRY]
        if len(entries) != 1 or entries[0].compress_type != zipfile.ZIP_STORED:
            raise ValueError('APK must have exactly one stored runtime')
        item = entries[0]
        stream.seek(item.header_offset)
        header = stream.read(30)
        if len(header) != 30 or header[:4] != b'PK\x03\x04':
            raise ValueError('invalid local ZIP header')
        name_len, extra_len = struct.unpack_from('<HH', header, 26)
        start = item.header_offset + 30 + name_len + extra_len
        end = start + item.compress_size
        if end > path.stat().st_size:
            raise ValueError('runtime outside APK')
        return start, end


def hash_range(path, start, end):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        stream.seek(start)
        remaining = end - start
        while remaining:
            block = stream.read(min(CHUNK, remaining))
            if not block:
                raise ValueError('unexpected EOF')
            result.update(block)
            remaining -= len(block)
    return result.hexdigest()


def copy_range(source, out, start, end):
    source.seek(start)
    remaining = end - start
    while remaining:
        block = source.read(min(CHUNK, remaining))
        if not block:
            raise ValueError('unexpected EOF')
        out.write(block)
        remaining -= len(block)


def create(base, target, output):
    if output.exists():
        raise FileExistsError(output)
    bstart, bend = runtime_range(base)
    start, end = runtime_range(target)
    runtime_hash = hash_range(base, bstart, bend)
    if bend - bstart != end - start or runtime_hash != hash_range(target, start, end):
        raise ValueError('runtime entries differ; choose a matching base')
    meta = {'format': 'whale-apk-runtime-delta-v1', 'baseSha256': digest(base),
            'targetSha256': digest(target), 'targetName': target.name,
            'targetSize': target.stat().st_size, 'runtimeSha256': runtime_hash,
            'runtimeSize': end - start, 'prefixSize': start, 'suffixSize': target.stat().st_size - end}
    created = False
    try:
        with output.open('xb') as destination:
            created = True
            with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive, target.open('rb') as stream:
                archive.writestr('manifest.json', json.dumps(meta, ensure_ascii=False, sort_keys=True))
                with archive.open('prefix', 'w') as out:
                    copy_range(stream, out, 0, start)
                with archive.open('suffix', 'w') as out:
                    copy_range(stream, out, end, target.stat().st_size)
    except BaseException:
        if created: output.unlink(missing_ok=True)
        raise
    print(json.dumps(meta, ensure_ascii=False))


def restore(base, delta, output):
    if output.exists():
        raise FileExistsError(output)
    with zipfile.ZipFile(delta) as archive:
        if sorted(archive.namelist()) != ['manifest.json', 'prefix', 'suffix']:
            raise ValueError('unexpected archive contents')
        manifest = archive.getinfo('manifest.json')
        if manifest.file_size > 16384:
            raise ValueError('manifest too large')
        meta = json.loads(archive.read(manifest))
        if meta.get('format') != 'whale-apk-runtime-delta-v1' or digest(base) != meta.get('baseSha256'):
            raise ValueError('base APK or archive format mismatch')
        start, end = runtime_range(base)
        if end - start != meta.get('runtimeSize') or hash_range(base, start, end) != meta.get('runtimeSha256'):
            raise ValueError('base runtime mismatch')
        if archive.getinfo('prefix').file_size != meta.get('prefixSize') or archive.getinfo('suffix').file_size != meta.get('suffixSize'):
            raise ValueError('archive segment size mismatch')
        if meta['prefixSize'] + meta['runtimeSize'] + meta['suffixSize'] != meta.get('targetSize'):
            raise ValueError('archive total size mismatch')
        created = False
        try:
            with output.open('xb') as out:
                created = True
                with base.open('rb') as source:
                    with archive.open('prefix') as prefix:
                        shutil.copyfileobj(prefix, out, CHUNK)
                    copy_range(source, out, start, end)
                    with archive.open('suffix') as suffix:
                        shutil.copyfileobj(suffix, out, CHUNK)
            if output.stat().st_size != meta['targetSize'] or digest(output) != meta['targetSha256']:
                raise ValueError('restored APK hash mismatch')
        except BaseException:
            if created: output.unlink(missing_ok=True)
            raise
    print('Restored and verified SHA-256', meta['targetSha256'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['create', 'restore'])
    parser.add_argument('base', type=Path)
    parser.add_argument('input', type=Path, help='target APK to archive, or delta to restore')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.operation == 'create':
        create(args.base, args.input, args.output)
    else:
        restore(args.base, args.input, args.output)

if __name__ == '__main__':
    main()
