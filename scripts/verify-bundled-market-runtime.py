#!/usr/bin/env python3
"""Compare the source runtime to the bundled-market runtime without extracting it."""
import hashlib
import json
import sys
import tarfile

source, target, manifest_path = sys.argv[1:]
manifest = json.load(open(manifest_path, encoding="utf-8"))
changed = {"root/.dsh/profiles/web/package.json", "etc/deepseek-harness-runtime.json"}
seen = set(); extras = {}; total = 0; links = 0
with tarfile.open(source, "r|gz") as old, tarfile.open(target, "r|gz") as new:
    for left in old:
        right = new.next()
        if right is None or (left.name, left.type, left.linkname, left.mode, left.uid, left.gid, left.mtime) != (right.name, right.type, right.linkname, right.mode, right.uid, right.gid, right.mtime):
            raise SystemExit(f"Original entry metadata differs: {left.name}")
        links += int(right.issym() and right.name.startswith("root/.dsh/profiles/node_modules/"))
        total += right.size if right.isfile() else 0
        modified = left.name in changed or left.name.endswith("/node_modules/@deepseek-ai/dsh/package.json")
        if left.isfile() and modified:
            seen.add(left.name)
            data = json.load(new.extractfile(right))
            if left.name == "root/.dsh/profiles/web/package.json" and "dshmarket" not in data["dsh"]["profile"]["bundles"]: raise SystemExit("market missing from profile")
            if left.name == "etc/deepseek-harness-runtime.json" and data["runtimeVersion"] != manifest["version"]: raise SystemExit("runtime version mismatch")
            if left.name.endswith("/node_modules/@deepseek-ai/dsh/package.json") and data["dependencies"].get("dshmarket") != "*": raise SystemExit("market missing from dsh dependencies")
        elif left.isfile():
            if left.size != right.size: raise SystemExit(f"Size differs: {left.name}")
            a = hashlib.sha256(); b = hashlib.sha256(); af = old.extractfile(left); bf = new.extractfile(right)
            for chunk in iter(lambda: af.read(1048576), b""): a.update(chunk)
            for chunk in iter(lambda: bf.read(1048576), b""): b.update(chunk)
            if a.digest() != b.digest(): raise SystemExit(f"Content differs: {left.name}")
    for entry in iter(new.next, None):
        if entry.name in extras: raise SystemExit(f"Duplicate added entry: {entry.name}")
        extras[entry.name] = entry
        total += entry.size if entry.isfile() else 0
        links += int(entry.issym() and entry.name.startswith("root/.dsh/profiles/node_modules/"))
        if entry.isfile():
            # Exercise the entire stream and ZIP CRC/gzip integrity.
            f = new.extractfile(entry)
            for _ in iter(lambda: f.read(1048576), b""): pass
if len(seen) != 3: raise SystemExit("Required metadata not present")
required = ["opt/dsh/plugins/dshmarket/package.json", "opt/dsh/plugins/dshmarket/lib/index.js",
    "opt/dsh/plugins/dshmarket/client/client.js", "opt/dsh/plugins/dshmarket/cordis.patch.yml",
    "opt/dsh/plugins/dshmarket/node_modules/js-yaml/package.json", "opt/dsh/plugins/dshmarket/node_modules/undici/package.json",
    "opt/dsh/plugins/dshmarket/node_modules/argparse/package.json", "root/.dsh/profiles/node_modules/dshmarket"]
if any(name not in extras for name in required): raise SystemExit("Market files or link missing")
if total != manifest["rootfs"]["extractedBytes"] or links != manifest["profileLinks"]:
    raise SystemExit(f"Manifest accounting mismatch: bytes {total}, links {links}")
with open(target, "rb") as f:
    if hashlib.file_digest(f, "sha256").hexdigest() != manifest["rootfs"]["sha256"]: raise SystemExit("Runtime hash mismatch")
print(f"Verified unchanged source entries, bundled market, {total} extracted bytes, {links} profile links")
