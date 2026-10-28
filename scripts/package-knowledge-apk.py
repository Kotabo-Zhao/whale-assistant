#!/usr/bin/env python3
"""Create an unsigned APK with only the verified runtime assets replaced."""
import hashlib
import json
import shutil
import sys
import zipfile
from pathlib import Path

source, bundle, manifest, target = map(Path, sys.argv[1:5])
expected_source = sys.argv[5].lower()
with source.open("rb") as f:
    if hashlib.file_digest(f, "sha256").hexdigest() != expected_source: raise SystemExit("input APK hash mismatch")
metadata = json.loads(manifest.read_text(encoding="utf-8"))
if metadata["version"] != "0.3.18-whale-knowledge1" or bundle.stat().st_size != metadata["rootfs"]["compressedBytes"]: raise SystemExit("invalid runtime manifest")
with bundle.open("rb") as f:
    if hashlib.file_digest(f, "sha256").hexdigest() != metadata["rootfs"]["sha256"]: raise SystemExit("runtime hash mismatch")
if target.exists(): raise SystemExit("refusing to overwrite output")
replacements = {"assets/runtime/rootfs.bundle": bundle, "assets/runtime/runtime-manifest.json": manifest}
seen = set()
with zipfile.ZipFile(source) as old, zipfile.ZipFile(target, "x", allowZip64=True) as new:
    if len(old.namelist()) != len(set(old.namelist())): raise SystemExit("duplicate input entry")
    for entry in old.infolist():
        name = entry.filename
        upper = name.upper()
        if upper.startswith("META-INF/") and (upper.endswith((".RSA", ".DSA", ".EC", ".SF")) or upper == "META-INF/MANIFEST.MF"): continue
        if name in replacements:
            seen.add(name)
            if name.endswith("rootfs.bundle") and entry.compress_type != zipfile.ZIP_STORED: raise SystemExit("runtime must be stored")
            src = replacements[name].open("rb")
        else:
            src = old.open(entry)
        with src, new.open(entry, "w", force_zip64=False) as out: shutil.copyfileobj(src, out, 1024 * 1024)
if seen != set(replacements): raise SystemExit("runtime assets missing")
print("Packaged runtime replacements", target)
