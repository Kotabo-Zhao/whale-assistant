"""Update the verified bundled runtime's mobile plugin without rebuilding Ubuntu."""
import hashlib
import io
import json
import tarfile
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
assets = root / 'android/app/src/main/assets/runtime'
# An optional base APK keeps subsequent rebuilds pinned even after assets were updated.
archive = zipfile.ZipFile(sys.argv[1]) if len(sys.argv) > 1 else None
manifest = json.loads(archive.read('assets/runtime/runtime-manifest.json')) if archive else json.loads((assets / 'runtime-manifest.json').read_text(encoding='utf-8'))
source = assets / 'rootfs.bundle'
def open_source():
    return archive.open('assets/runtime/rootfs.bundle') if archive else source.open('rb')
expected = '1d3102190e73ed38894d63dfcf26b730f835986385ef3f2eb1abcd39dda6ab7f'
with open_source() as stream:
    if manifest['rootfs']['sha256'] != expected or hashlib.file_digest(stream, 'sha256').hexdigest() != expected:
        raise SystemExit('Unexpected base runtime; do not patch an unverified version')
suffix = sys.argv[2] if len(sys.argv) > 2 else 'worker-vision'
if not suffix.replace('-', '').isalnum(): raise SystemExit('Invalid output suffix')
output = root / f'build/{suffix}-rootfs.bundle'
out_manifest = root / f'build/{suffix}-runtime-manifest.json'
if output.exists() or out_manifest.exists():
    raise SystemExit('Output already exists; inspect it before replacing')
plugin = root / 'scripts/runtime-profile/plugins/dsh-mobile-shizuku'
prefix = 'opt/dsh/plugins/dsh-mobile-shizuku/'
files = {prefix + p.relative_to(plugin).as_posix(): p.read_bytes()
         for p in sorted(plugin.rglob('*')) if p.is_file() and 'node_modules' not in p.parts and
         (p.relative_to(plugin).parts[0] == 'lib' or p.name in ('package.json', 'cordis.patch.yml'))}
seen = set()
delta = 0
version = '0.3.42-whale-superagent'
with open_source() as stream, tarfile.open(fileobj=stream, mode='r|gz') as old, tarfile.open(output, 'w|gz', compresslevel=6, format=tarfile.PAX_FORMAT) as new:
    for item in old:
        if item.name in files:
            if not item.isfile() or item.name in seen:
                raise ValueError('Invalid or duplicate plugin member')
            seen.add(item.name)
            data = files[item.name]
            delta += len(data) - item.size
            item.size = len(data)
            new.addfile(item, io.BytesIO(data))
        elif item.name == 'etc/deepseek-harness-runtime.json':
            metadata = json.load(old.extractfile(item))
            if metadata['runtimeVersion'] != manifest['version']:
                raise ValueError('Metadata version mismatch')
            metadata['runtimeVersion'] = version
            data = (json.dumps(metadata, indent=2) + '\n').encode()
            delta += len(data) - item.size
            item.size = len(data)
            new.addfile(item, io.BytesIO(data))
        elif item.isfile():
            new.addfile(item, old.extractfile(item))
        else:
            new.addfile(item)
    if prefix + 'lib/index.js' not in seen:
        raise ValueError('Base plugin missing')
    for name in sorted(files.keys() - seen):
        info = tarfile.TarInfo(name)
        info.mode = 0o644
        info.size = len(files[name])
        delta += info.size
        new.addfile(info, io.BytesIO(files[name]))
manifest['version'] = version
manifest['rootfs'].update(url=f'https://localhost.invalid/embedded-{version}.bundle',
                         sha256=hashlib.file_digest(output.open('rb'), 'sha256').hexdigest(),
                         compressedBytes=output.stat().st_size,
                         extractedBytes=manifest['rootfs']['extractedBytes'] + delta)
out_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'version': version, 'sha256': manifest['rootfs']['sha256'], 'plugin_files': len(files)}, indent=2))
