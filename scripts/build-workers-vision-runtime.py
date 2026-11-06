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
    '0.3.84-whale-coordinator': '6feb30c1f34047ea58ee5cf539e5ce32868544dd1749b5d195ea0f7a4f01197b',
    '0.3.83-whale-screen-coordinates': 'f7da437777f2afac1e495641b3f205dcb03effe6967858c1005180313d76a810',
    '0.3.82-whale-screen-text': '75512bf56025bedeed85bb6aba99439d50ace2eda6802f9d16254d436baa8a62',
    '0.3.81-whale-screen-text': '7e4ad14cb3b313f8ffa9e0163cbf4f83be32f54fcf26732da00606968ee5df98',
    '0.3.80-whale-pet-journal': '9c0517341e62b1c7e67b0298dd319871e3bcf72f7c1ed22ad3a9221370c0c2b6',
    '0.3.79-whale-screen-evidence': '6fe31e26038eaaa19262ab3191bfdc665521791a3c5116f929708f5b03436481',
    '0.3.77-whale-screen-evidence': '1c510c748f5a72949dc586a4981c7eaee89376da5eefbcba2bd992197736fdd3',
    '0.3.76-whale-screen-sequence': 'c5239e4bc2d67590530c70b6569065f706fa90aef4b9af2961e8e2644ff7f5f0',
    '0.3.73-whale-screen-fast': '8c1f1edfb9b3b5300b650f406478c5b9f6efd825cd6d490fe624e7e62b057cc9',
    '0.3.72-whale-resilience': 'f4dd5b568e9393c2e284f1a6859025adf8aedafc618d6bb340eb565aa9aced15',
    '0.3.71-whale-resilience': 'a12d45a6e7b3a9e8736787cc7e6eda73a65adae5adfdef636354c2307eaa850c',
    '0.3.70-whale-watchdog': '632980c16dce7db35b6238c2f7fbfd5f1202f65c0f9dd9fe046433695187fb1b',
    '0.3.69-whale-watchdog': 'e5c27b9625f0fbdfe1cda29040a035ee93f4098421c42270cc7b876e9511537e',
    '0.3.68-whale-integrated': '6ad7715d28c4e0ae945f11daad2b843334c3e1e3654c75728f58b52f009d7877',
    '0.3.67-whale-integrated': '7ab05befee573c5ea6254990aa65708abbedeaf67538d2418c22c8fa2acb626e',
    '0.3.66-whale-integrated': '92f96dc2cd37a1e8a129323c488970df2939ec4bed05f3f511f464aaa65a6ff2',
    '0.3.65-whale-integrated': '0f0b72fe942071e055c90a594fea6d0ccdbdedb0458784c92e2c5aa923b67b1c',
    '0.3.64-whale-integrated': '1ac35afbd6aab6be156eda132fb11b0106075a94d4438067fd35770c2c0325e8',
    '0.3.63-whale-integrated': 'fa2edf5814bd51eaad3db5446e5fcda9e2dfa9ca11e5df07e4d97c57ec84a190',
    '0.3.62-whale-integrated': 'f88f8ad3b9627cc765b404b60131946709e774d0e34c609d31fb1f6d3c67fae5',
    '0.3.60-whale-knowledge-write': 'dafb6cd3b8bb132cc1392ba9ec8a5955e36215a4ac05bd011616970fbd9f52cc',
    '0.3.59-whale-task-budget': '87202ebd9181181a31ec9f74c8e57c12fa1f13d91b2b46e08609a21b2816b375',
    '0.3.58-whale-bound-creation': '9ba659261da490ad0f403dc4ab656a28aefcc136bbf0b65cdc54f4737fb232b1',
    '0.3.57-whale-bound-session': '1f6b88bc1eaca93add37e3dcfb0f4b0353af0ba88c524479fb6793c769cf50ff',
    '0.3.56-whale-dependencies': 'aacb80466a27768139a0f6886b5302eab4def68f44ec0b2c05894818bc03021d',
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
files['usr/local/lib/dsh-mobile-auth.cjs'] = (root / 'scripts/mobile-auth-preload.cjs').read_bytes()
files['usr/local/lib/mobile-reviewer-context.cjs'] = (root / 'scripts/mobile-reviewer-context.cjs').read_bytes()
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
