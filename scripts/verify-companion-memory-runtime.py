#!/usr/bin/env python3
"""Compare the rebuilt runtime to upstream, permitting only the plugin replacement."""
import hashlib
import sys
import tarfile
from itertools import zip_longest
from pathlib import Path

old, new, plugin = sys.argv[1:]
expected = Path(plugin).read_bytes().replace(b"\r\n", b"\n")
target = "opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js"
seen = 0
with tarfile.open(old, "r|gz") as a, tarfile.open(new, "r|gz") as b:
    for left, right in zip_longest(a, b):
        if left is None or right is None:
            raise SystemExit("Different entry counts")
        meta = lambda x: (x.name, x.type, x.linkname, x.mode, x.uid, x.gid, x.mtime)
        if meta(left) != meta(right):
            raise SystemExit(f"Metadata mismatch: {left.name} / {right.name}")
        if left.isfile():
            if left.name == target:
                if b.extractfile(right).read() != expected:
                    raise SystemExit("Plugin content mismatch")
                seen += 1
            else:
                if left.size != right.size:
                    raise SystemExit(f"Size mismatch: {left.name}")
                ah = hashlib.sha256(); bh = hashlib.sha256()
                af = a.extractfile(left); bf = b.extractfile(right)
                for chunk in iter(lambda: af.read(1024 * 1024), b""): ah.update(chunk)
                for chunk in iter(lambda: bf.read(1024 * 1024), b""): bh.update(chunk)
                if ah.digest() != bh.digest():
                    raise SystemExit(f"Content mismatch: {left.name}")
if seen != 1:
    raise SystemExit("Plugin entry missing or duplicated")
print("Verified all unchanged runtime entries and memory plugin payload")
