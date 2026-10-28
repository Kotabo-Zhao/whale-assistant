#!/usr/bin/env python3
import hashlib
import json
import sys
import tarfile
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    manifest = json.loads(apk.read("assets/runtime/runtime-manifest.json"))
    info = apk.getinfo("assets/runtime/rootfs.bundle")
    if info.compress_type != zipfile.ZIP_STORED:
        raise SystemExit("runtime bundle is compressed by APK")
    with apk.open(info) as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    if digest != manifest["rootfs"]["sha256"] or info.file_size != manifest["rootfs"]["compressedBytes"]:
        raise SystemExit("runtime bundle does not match manifest")
    if manifest["version"] not in ("0.3.7-whale-memory1", "0.3.8-whale-market1"):
        raise SystemExit("unexpected runtime version")
    with apk.open(info) as f, tarfile.open(fileobj=f, mode="r|gz") as tar:
        found_memory = False
        found_market = False
        for entry in tar:
            if entry.name == "opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js":
                content = tar.extractfile(entry).read()
                if not all(name in content for name in (b"mobile_memory_save", b"mobile_memory_search", b"mobile_memory_delete")):
                    raise SystemExit("memory tools missing")
                found_memory = True
            if entry.name == "opt/dsh/plugins/dshmarket/package.json":
                found_market = json.load(tar.extractfile(entry)).get("version") == "1.73.1"
        if not found_memory or (manifest["version"] == "0.3.8-whale-market1" and not found_market):
            raise SystemExit("runtime plugin missing")
    print("runtime verified:", manifest["version"], digest, "memory tools present; market:", found_market)
