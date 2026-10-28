#!/usr/bin/env python3
"""Copy referenced APK entries into a fresh unsigned archive, preserving contents."""
import hashlib
import shutil
import sys
import zipfile
from pathlib import Path

src, dst = map(Path, sys.argv[1:3])
expected = sys.argv[3].lower() if len(sys.argv) == 4 else "691a4373a0fa2f9edd2cd509280aa2b8d9384ce8a9c00962bf9b0b3173880a40"
with src.open("rb") as f:
    actual = hashlib.file_digest(f, "sha256").hexdigest()
if actual != expected:
    raise SystemExit("Input differs from pinned APK")
if dst.exists():
    raise SystemExit("Refusing to overwrite an existing output")
with zipfile.ZipFile(src) as old, zipfile.ZipFile(dst, "x", allowZip64=True) as new:
    names = old.namelist()
    if len(names) != len(set(names)):
        raise SystemExit("Duplicate input entry")
    for entry in old.infolist():
        upper = entry.filename.upper()
        if upper.startswith("META-INF/") and (upper.endswith((".RSA", ".DSA", ".EC", ".SF")) or upper == "META-INF/MANIFEST.MF"):
            continue
        with old.open(entry) as source, new.open(entry, "w", force_zip64=entry.file_size >= 0xffffffff) as target:
            shutil.copyfileobj(source, target, 1024 * 1024)
print("Referenced entries copied:", dst)
