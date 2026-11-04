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
archive = zipfile.ZipFile(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1] != '--assets' else None
manifest = json.loads(archive.read('assets/runtime/runtime-manifest.json')) if archive else json.loads((assets / 'runtime-manifest.json').read_text(encoding='utf-8'))
source = assets / 'rootfs.bundle'
def open_source():
    return archive.open('assets/runtime/rootfs.bundle') if archive else source.open('rb')
expected_bases = {
    '0.3.55-whale-pet-modes': 'dc3f0010aa819df93e6165dfba18960f8588d20e95045bd7481084ed31a5cc4c',
    '0.3.54-whale-creator-worker': '0ec905ad83b6593bfd1bd70e35a10df52472c949b190315a4262559a1fda42b9',
    '0.3.53-whale-tools': 'e81a9ff93afb3765c374a3b4c19ddb951d868e7fd8a878f78664564c29b670de',
    '0.3.52-whale-linked': 'd382f807fcfaf856b4a3b7ff42d4c689d8f57b9ccc2281dbb7a6abb6ba39cebf',
    '0.3.23-whale-bubble-stream1': '1d3102190e73ed38894d63dfcf26b730f835986385ef3f2eb1abcd39dda6ab7f',
    '0.3.42-whale-superagent': '1b28473909ea5d3cc606c8a9546010b1bada5e75c326727d9189fd63c847cefa',
    '0.3.43-whale-delivery': '2c353316ebf9d621d65ab108453604a242ac9ebbf58e15a310ef99ac17b285a7',
    '0.3.44-whale-shared-workers': '5edbc2e0f2c20d522e087801f0ce9a3f00d33bb07c4bb8b4cf0f94a611f70539',
    '0.3.51-whale-atomic': '928ea1c4bdcd819b81df7086c1ca27c92fc002175e6b9b3a807d3a2d17c3272a',
    '0.3.50-whale-resources': '168f07e35f6d4292e1d83d9f5df57d3e564bd26ecf9705ad1faef2bbabb3d1bd',
    '0.3.45-whale-report-once': '91cd9f43ce84d6f8e30edee58c586e6d87088dd6f425c400625e3eda715b9e4a',
}
expected = expected_bases.get(manifest['version'])
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
version = sys.argv[3] if len(sys.argv) > 3 else '0.3.46-whale-superagent-manager'
if not all(c.isascii() and (c.isalnum() or c in '.-') for c in version) or not version:
    raise SystemExit('Invalid runtime version')
with open_source() as stream, tarfile.open(fileobj=stream, mode='r|gz') as old, tarfile.open(str(output), 'w|gz', compresslevel=6, format=tarfile.PAX_FORMAT) as new:
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
