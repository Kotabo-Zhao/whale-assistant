#!/usr/bin/env python3
"""Build a mobile marketplace runtime from the verified candidate-memory runtime."""
import hashlib
import io
import json
import sys
import tarfile
from pathlib import Path

source, manifest_path, output, output_manifest = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
expected = "d48c66a2b5ec2e38f4838f2972fce27bd489612491246cf5acbb2a111bd4c701"
with source.open("rb") as stream:
    digest = hashlib.file_digest(stream, "sha256").hexdigest()
if digest != expected or manifest["rootfs"]["sha256"] != expected or manifest["version"] != "0.3.10-whale-memory2":
    raise SystemExit("Input runtime does not match the pinned memory bundle")
if output.exists() or output_manifest.exists():
    raise SystemExit("Refusing to overwrite output")

import importlib.util
spec = importlib.util.spec_from_file_location("market_mobile_patch", Path(__file__).with_name("market-mobile-patch.py"))
patch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patch)
targets = {"opt/dsh/plugins/dshmarket/lib/routes.js", "opt/dsh/plugins/dshmarket/client/client.js", "etc/deepseek-harness-runtime.json"}
seen = set()
delta = 0
with tarfile.open(source, "r|gz") as old, tarfile.open(output, "x:gz", compresslevel=6, format=tarfile.PAX_FORMAT) as new:
    for item in old:
        if item.name in targets:
            if not item.isfile() or item.name in seen:
                raise SystemExit("Unexpected or duplicate runtime target")
            seen.add(item.name)
            if item.name.endswith("routes.js"):
                data = patch.patch_routes_v2(old.extractfile(item).read())
            elif item.name.endswith("client.js"):
                data = patch.patch_client_v2(old.extractfile(item).read())
            else:
                metadata = json.load(old.extractfile(item))
                metadata["runtimeVersion"] = "0.3.12-whale-market3"
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
manifest["version"] = "0.3.12-whale-market3"
manifest["rootfs"].update({
    "url": "https://localhost.invalid/embedded-whale-market3.bundle",
    "sha256": new_hash,
    "compressedBytes": output.stat().st_size,
    "extractedBytes": manifest["rootfs"]["extractedBytes"] + delta,
})
output_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("runtime", manifest["version"], "sha256", new_hash, "bytes", output.stat().st_size, "delta", delta)
