#!/usr/bin/env python3
"""Build the candidate-memory runtime from the verified reminder runtime."""
import hashlib
import io
import json
import sys
import tarfile
from pathlib import Path

source, manifest_path, plugin_path, output, output_manifest = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
expected = "c3ad7afefd9ad5616bb77fc507e4205829f0a7623b25effac63e91526e21c37c"
with source.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
if digest != expected or manifest["rootfs"]["sha256"] != expected or manifest["version"] != "0.3.13-whale-calendar1":
    raise SystemExit("Input runtime does not match the pinned reminder bundle")
if output.exists() or output_manifest.exists():
    raise SystemExit("Refusing to overwrite output")

plugin = plugin_path.read_bytes()
if not all(marker in plugin for marker in (b"mobile_reminder_list", b"mobile_memory_save", b"mobile_accessibility_back")):
    raise SystemExit("Memory candidate instructions or reminder tools are missing")
targets = {"opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js", "etc/deepseek-harness-runtime.json"}
seen = set()
delta = 0
with tarfile.open(source, "r|gz") as old, tarfile.open(output, "x:gz", compresslevel=6, format=tarfile.PAX_FORMAT) as new:
    for item in old:
        if item.name in targets:
            if not item.isfile() or item.name in seen:
                raise SystemExit("Unexpected or duplicate runtime target")
            seen.add(item.name)
            if item.name.endswith("index.js"):
                previous = old.extractfile(item).read()
                if hashlib.sha256(previous).hexdigest() != "568848a9e03c63868a7de47b63e9a8c45925e7d140833042fb0eb64067016b1d":
                    raise SystemExit("Unexpected source mobile plugin")
                data = plugin
            else:
                metadata = json.load(old.extractfile(item))
                metadata["runtimeVersion"] = "0.3.14-whale-back1"
                data = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode()
            delta += len(data) - item.size
            item.size = len(data)
            new.addfile(item, io.BytesIO(data))
        elif item.isfile():
            new.addfile(item, old.extractfile(item))
        else:
            new.addfile(item)
if seen != targets:
    raise SystemExit("Runtime target missing")
with output.open("rb") as stream:
    new_hash = hashlib.file_digest(stream, "sha256").hexdigest()
manifest["version"] = "0.3.14-whale-back1"
manifest["rootfs"].update({
    "url": "https://localhost.invalid/embedded-whale-back1.bundle",
    "sha256": new_hash,
    "compressedBytes": output.stat().st_size,
    "extractedBytes": manifest["rootfs"]["extractedBytes"] + delta,
})
output_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("runtime", manifest["version"], "sha256", new_hash, "bytes", output.stat().st_size, "delta", delta)
