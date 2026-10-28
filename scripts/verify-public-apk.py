#!/usr/bin/env python3
"""Confirm the APK contains exactly the current Vite assets and their unmodified contents."""
import hashlib
import sys
import zipfile
from pathlib import Path

dist = Path(sys.argv[1]); apk_path = Path(sys.argv[2])
expected = {p.name: p for p in (dist / "assets").iterdir() if p.is_file() and not p.is_symlink()}
if not expected: raise SystemExit("empty frontend")
with zipfile.ZipFile(apk_path) as apk:
    names = [n for n in apk.namelist() if n.startswith("assets/public/assets/") and not n.endswith("/")]
    if len(names) != len(set(names)) or {n.removeprefix("assets/public/assets/") for n in names} != set(expected):
        raise SystemExit("APK frontend assets do not match current build")
    if apk.read("assets/public/index.html") != (dist / "index.html").read_bytes():
        raise SystemExit("APK frontend index mismatch")
    for name, file in expected.items():
        with apk.open("assets/public/assets/" + name) as stream, file.open("rb") as source:
            if hashlib.file_digest(stream, "sha256").digest() != hashlib.file_digest(source, "sha256").digest():
                raise SystemExit("APK frontend asset mismatch: " + name)
print("verified current frontend assets", len(expected))
