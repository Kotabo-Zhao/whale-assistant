#!/usr/bin/env python3
"""Verify every archive entry outside the reminder plugin and version metadata."""
import hashlib
import json
import sys
import tarfile
from itertools import zip_longest
from pathlib import Path

old, new, plugin, manifest_path = sys.argv[1:]
expected_plugin = Path(plugin).read_bytes()
manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
if manifest["version"] != "0.3.13-whale-calendar1" or Path(new).stat().st_size != manifest["rootfs"]["compressedBytes"]:
    raise SystemExit("Runtime version or compressed size mismatch")
with open(new, "rb") as source:
    if hashlib.file_digest(source, "sha256").hexdigest() != manifest["rootfs"]["sha256"]:
        raise SystemExit("Runtime hash does not match manifest")
targets = {"opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js", "etc/deepseek-harness-runtime.json"}
seen = set()
extracted = 0
with tarfile.open(old, "r|gz") as a, tarfile.open(new, "r|gz") as b:
    for left, right in zip_longest(a, b):
        if left is None or right is None:
            raise SystemExit("Different entry counts")
        meta = lambda x: (x.name, x.type, x.linkname, x.mode, x.uid, x.gid, x.mtime)
        if meta(left) != meta(right):
            raise SystemExit(f"Metadata mismatch: {left.name}")
        if not left.isfile():
            continue
        extracted += right.size
        if left.name in targets:
            if left.name in seen:
                raise SystemExit("Duplicate target")
            seen.add(left.name)
            if left.name.endswith("index.js"):
                if b.extractfile(right).read() != expected_plugin:
                    raise SystemExit("Plugin payload mismatch")
            else:
                before = json.load(a.extractfile(left)); after = json.load(b.extractfile(right))
                before["runtimeVersion"] = "0.3.13-whale-calendar1"
                if before != after:
                    raise SystemExit("Unexpected runtime metadata change")
            continue
        if left.size != right.size:
            raise SystemExit(f"Size mismatch: {left.name}")
        one = hashlib.sha256(); two = hashlib.sha256()
        old_file = a.extractfile(left); new_file = b.extractfile(right)
        for chunk in iter(lambda: old_file.read(1048576), b""):
            one.update(chunk)
        for chunk in iter(lambda: new_file.read(1048576), b""):
            two.update(chunk)
        if one.digest() != two.digest():
            raise SystemExit(f"Content mismatch: {left.name}")
if seen != targets or extracted != manifest["rootfs"]["extractedBytes"]:
    raise SystemExit("Runtime targets or extracted size mismatch")
print("Verified unchanged runtime entries and candidate-memory payload; extracted bytes", extracted)
