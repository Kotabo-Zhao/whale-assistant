#!/usr/bin/env python3
"""Build a memory-enabled bundle from the verified upstream 0.3.6 runtime.

Writes new files only. Tar metadata, entry order, symlinks and hardlinks are retained;
only the built-in plugin's index.js is replaced. Do not use on an installed rootfs.
"""
import hashlib
import io
import json
import sys
import tarfile
from pathlib import Path

upstream, manifest_path, plugin, output, output_manifest = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
expected = "9108b7f83f922697f1433f2f8ea018911d655232e5bfa151f7d7223a147ab9f4"
with upstream.open("rb") as f:
    digest = hashlib.file_digest(f, "sha256").hexdigest()
if digest != expected or manifest["rootfs"]["sha256"] != expected:
    raise SystemExit("Upstream runtime hash does not match pinned release")
if output.exists() or output_manifest.exists():
    raise SystemExit("Refusing to overwrite output")
name = "opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js"
replacement = plugin.read_bytes().replace(b"\r\n", b"\n")
count = 0
delta = 0
with tarfile.open(str(upstream), "r|gz") as source, tarfile.open(str(output), "w|gz", compresslevel=6, format=tarfile.PAX_FORMAT) as dest:
    for item in source:
        if item.name == name:
            if not item.isfile():
                raise SystemExit("Plugin entry is not a regular file")
            count += 1
            delta = len(replacement) - item.size
            item.size = len(replacement)
            dest.addfile(item, io.BytesIO(replacement))
        elif item.isfile():
            dest.addfile(item, source.extractfile(item))
        else:
            dest.addfile(item)
if count != 1:
    output.unlink(missing_ok=True)
    raise SystemExit(f"Expected exactly one plugin entry, found {count}")
with output.open("rb") as f:
    new_hash = hashlib.file_digest(f, "sha256").hexdigest()
manifest["version"] = "0.3.7-whale-memory1"
manifest["rootfs"]["sha256"] = new_hash
manifest["rootfs"]["compressedBytes"] = output.stat().st_size
manifest["rootfs"]["extractedBytes"] += delta
# The file is embedded, not published; remove the upstream URL's implication that
# the modified bytes can be downloaded there. The installer schema requires HTTPS.
manifest["rootfs"]["url"] = "https://localhost.invalid/embedded-whale-memory1.bundle"
output_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("runtime sha256:", new_hash, "compressed bytes:", output.stat().st_size, "delta:", delta)
