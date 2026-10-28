#!/usr/bin/env python3
"""Ensure a signed APK changed only the two intended runtime assets."""
import hashlib
import sys
import zipfile

def signature(name):
    upper = name.upper()
    return upper.startswith("META-INF/") and (upper.endswith((".RSA", ".DSA", ".EC", ".SF")) or upper == "META-INF/MANIFEST.MF")

with zipfile.ZipFile(sys.argv[1]) as old, zipfile.ZipFile(sys.argv[2]) as new:
    if len(old.namelist()) != len(set(old.namelist())) or len(new.namelist()) != len(set(new.namelist())): raise SystemExit("duplicate entries")
    first = {x.filename: x for x in old.infolist() if not signature(x.filename)}
    second = {x.filename: x for x in new.infolist() if not signature(x.filename)}
    if first.keys() != second.keys(): raise SystemExit("entry set mismatch")
    targets = {"assets/runtime/rootfs.bundle", "assets/runtime/runtime-manifest.json"}
    if not targets <= first.keys(): raise SystemExit("runtime entries missing")
    for name, item in first.items():
        other = second[name]
        if item.compress_type != other.compress_type: raise SystemExit("compression mismatch: " + name)
        if name in targets: continue
        if item.file_size != other.file_size: raise SystemExit("size mismatch: " + name)
        with old.open(item) as a, new.open(other) as b:
            if hashlib.file_digest(a, "sha256").digest() != hashlib.file_digest(b, "sha256").digest(): raise SystemExit("content mismatch: " + name)
    print("Verified all other APK entries:", len(first) - len(targets))
