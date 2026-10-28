#!/usr/bin/env python3
"""Compare all non-signature entries by decompressed SHA-256 and compression method."""
import hashlib
import sys
import zipfile

def signature(name):
    upper = name.upper()
    return upper.startswith("META-INF/") and (upper.endswith((".RSA", ".DSA", ".EC", ".SF")) or upper == "META-INF/MANIFEST.MF")

with zipfile.ZipFile(sys.argv[1]) as old, zipfile.ZipFile(sys.argv[2]) as new:
    original = {x.filename: x for x in old.infolist() if not signature(x.filename)}
    compact = {x.filename: x for x in new.infolist() if not signature(x.filename)}
    if len(old.namelist()) != len(set(old.namelist())) or len(new.namelist()) != len(set(new.namelist())):
        raise SystemExit("Duplicate ZIP entries")
    if original.keys() != compact.keys():
        raise SystemExit("Entry set mismatch")
    for name, a in original.items():
        b = compact[name]
        if (a.file_size, a.compress_type) != (b.file_size, b.compress_type):
            raise SystemExit(f"Entry metadata mismatch: {name}")
        with old.open(a) as f, new.open(b) as g:
            if hashlib.file_digest(f, "sha256").digest() != hashlib.file_digest(g, "sha256").digest():
                raise SystemExit(f"Entry data mismatch: {name}")
    print("Verified unchanged content and compression for", len(original), "entries")
