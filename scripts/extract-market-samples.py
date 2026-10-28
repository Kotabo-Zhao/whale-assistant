#!/usr/bin/env python3
"""Extract pinned integrity-verified npm samples into a new directory without running code."""
import base64
import hashlib
import os
import shutil
import sys
import tarfile
from pathlib import Path, PurePosixPath

INTEGRITY = {
    'dshmarket-1.73.3.tgz': 'fUkE+QoumMh4OLqT97iq1Vc75PT+EQgXYVLl0IXcw7tTpihRut1qRufEx5EAbGF+4+mqNEDehBnL+xwLQSstVw==',
    'undici-7.29.1.tgz': 'RYONW2MeafgYlkVOKYKkA/Ag7BmXqgIWCa8t1m0JcxrQg9pI9lEqRhAOruOBCbAohOa/gkCF+iPi9hrgvTzu6Q==',
    'js-yaml-4.3.2.tgz': 'SFNOvSJ+Dgf/9An904Yx+CgSlIPCkIpao4qo51lpee25TIRejdH3rhR4EZMGoNx3/TP3O+wzWuiTFl4sqbltzA==',
    'argparse-2.0.1.tgz': '8+9WqebbFzpX9OR+Wa6O29asIogeRMzcGtAINdpMHHyAg10f05aSFVBbcEqGf/PXw1EjAZ+q2/bEBg3DvurK3Q==',
}
source, destination = map(Path, sys.argv[1:3])
if destination.exists(): raise SystemExit('Destination must not exist')
destination.mkdir()
for filename, expected in INTEGRITY.items():
    archive = source / filename
    with archive.open('rb') as stream:
        if base64.b64encode(hashlib.file_digest(stream, 'sha512').digest()).decode() != expected: raise SystemExit('Sample integrity mismatch')
    output = destination / filename.rsplit('-', 1)[0]
    output.mkdir()
    seen = set(); total = 0
    with tarfile.open(archive, 'r:gz') as bundle:
        for count, item in enumerate(bundle, 1):
            if count > 2000 or not (item.isfile() or item.isdir()): raise ValueError('Unsupported archive entry')
            name = PurePosixPath(item.name)
            if not name.parts or name.parts[0] != 'package' or any(part in ('.', '..') or '\\' in part or ':' in part for part in name.parts): raise ValueError('Unsafe archive path')
            relative = name.parts[1:]
            if not relative: continue
            key = '/'.join(relative).casefold()
            if key in seen: raise ValueError('Duplicate archive path')
            seen.add(key)
            target = output.joinpath(*relative)
            if os.path.commonpath((str(output.resolve()), str(target.resolve()))) != str(output.resolve()): raise ValueError('Path escapes destination')
            if item.isdir(): target.mkdir(parents=True, exist_ok=True); continue
            total += item.size
            if total > 20 * 1024 * 1024: raise ValueError('Archive exceeds extraction limit')
            target.parent.mkdir(parents=True, exist_ok=True)
            with bundle.extractfile(item) as src, target.open('xb') as dst: shutil.copyfileobj(src, dst, 1024 * 1024)
    print(filename, count, total)
