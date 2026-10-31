#!/usr/bin/env python3
"""Replace the built-in mobile bridge plugin in an already verified embedded runtime."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import tarfile
from pathlib import Path

PLUGIN_PATH = "opt/dsh/plugins/dsh-mobile-shizuku/lib/index.js"
METADATA_PATH = "etc/deepseek-harness-runtime.json"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Refresh the embedded mobile bridge plugin without rebuilding the rootfs base.")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--plugin", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--output-manifest", type=Path, required=True)
    parser.add_argument("--runtime-version", required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output_manifest.exists():
        raise SystemExit("refusing to overwrite an existing output")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("rootfs", {}).get("sha256") != digest(args.source):
        raise SystemExit("source runtime does not match its manifest")
    plugin = args.plugin.read_bytes().replace(b"\r\n", b"\n")
    seen: set[str] = set()
    delta = 0
    with tarfile.open(args.source, "r|gz") as source, tarfile.open(args.output, "x:gz", compresslevel=6, format=tarfile.PAX_FORMAT) as output:
        for item in source:
            if item.name not in {PLUGIN_PATH, METADATA_PATH}:
                output.addfile(item, source.extractfile(item) if item.isfile() else None)
                continue
            if not item.isfile() or item.name in seen:
                raise SystemExit("runtime plugin or metadata entry is invalid")
            seen.add(item.name)
            if item.name == PLUGIN_PATH:
                data = plugin
            else:
                metadata = json.load(source.extractfile(item))
                metadata["runtimeVersion"] = args.runtime_version
                data = (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
            delta += len(data) - item.size
            item.size = len(data)
            output.addfile(item, io.BytesIO(data))
    if seen != {PLUGIN_PATH, METADATA_PATH}:
        args.output.unlink(missing_ok=True)
        raise SystemExit("runtime plugin or metadata entry is missing")
    manifest["version"] = args.runtime_version
    manifest["rootfs"].update({
        "url": f"https://localhost.invalid/embedded-{args.runtime_version}.bundle",
        "sha256": digest(args.output),
        "compressedBytes": args.output.stat().st_size,
        "extractedBytes": manifest["rootfs"]["extractedBytes"] + delta,
    })
    args.output_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"runtime={args.runtime_version} sha256={manifest['rootfs']['sha256']} bytes={manifest['rootfs']['compressedBytes']}")


if __name__ == "__main__":
    main()
