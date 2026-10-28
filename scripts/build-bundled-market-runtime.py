#!/usr/bin/env python3
"""Install a pinned prebuilt marketplace into the verified memory runtime bundle."""
import base64
import hashlib
import io
import json
import posixpath
import sys
import tarfile
from pathlib import Path, PurePosixPath

source, source_manifest, packages_dir, output, output_manifest = map(Path, sys.argv[1:])
expected_source = "403b8629ea351f48b754126f6617a66b5ca54e9b026ab3d1e846052e43048cbc"
pins = {
    "dshmarket-1.73.1.tgz": ("dshmarket", "1.73.1", "mnDEDq743YH2xuLU2qgmssnSiuet5VxS8Bsbggnyez2OfzSnOtIhmnufpe+fA/m+UQr7qI6PkHGCnzW/550tfQ=="),
    "js-yaml-4.3.2.tgz": ("js-yaml", "4.3.2", "SFNOvSJ+Dgf/9An904Yx+CgSlIPCkIpao4qo51lpee25TIRejdH3rhR4EZMGoNx3/TP3O+wzWuiTFl4sqbltzA=="),
    "undici-7.29.1.tgz": ("undici", "7.29.1", "RYONW2MeafgYlkVOKYKkA/Ag7BmXqgIWCa8t1m0JcxrQg9pI9lEqRhAOruOBCbAohOa/gkCF+iPi9hrgvTzu6Q=="),
    "argparse-2.0.1.tgz": ("argparse", "2.0.1", "8+9WqebbFzpX9OR+Wa6O29asIogeRMzcGtAINdpMHHyAg10f05aSFVBbcEqGf/PXw1EjAZ+q2/bEBg3DvurK3Q=="),
}
manifest = json.loads(source_manifest.read_text(encoding="utf-8"))
with source.open("rb") as f:
    if hashlib.file_digest(f, "sha256").hexdigest() != expected_source or manifest["rootfs"]["sha256"] != expected_source:
        raise SystemExit("Unrecognized source runtime")
if output.exists() or output_manifest.exists():
    raise SystemExit("Refusing to overwrite output")

additions = {}
for filename, (pkg, version, integrity) in pins.items():
    path = packages_dir / filename
    data = path.read_bytes()
    if base64.b64encode(hashlib.sha512(data).digest()).decode() != integrity:
        raise SystemExit(f"Package integrity mismatch: {filename}")
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        metadata = json.load(tar.extractfile("package/package.json"))
        if metadata.get("name") != pkg or metadata.get("version") != version:
            raise SystemExit(f"Package identity mismatch: {filename}")
        base = "opt/dsh/plugins/dshmarket" if pkg == "dshmarket" else f"opt/dsh/plugins/dshmarket/node_modules/{pkg}"
        for entry in tar:
            if entry.isdir():
                continue
            if not entry.isfile() or not entry.name.startswith("package/"):
                raise SystemExit(f"Unsupported package entry: {entry.name}")
            tail = PurePosixPath(entry.name).relative_to("package")
            if ".." in tail.parts or any(part in ("", ".") for part in tail.parts):
                raise SystemExit(f"Unsafe package path: {entry.name}")
            name = str(PurePosixPath(base) / tail)
            if name in additions:
                raise SystemExit(f"Duplicate package file: {name}")
            additions[name] = tar.extractfile(entry).read()

version = "0.3.8-whale-market1"
profile_path = "root/.dsh/profiles/web/package.json"
meta_path = "etc/deepseek-harness-runtime.json"
dsh_manifest_suffix = "/node_modules/@deepseek-ai/dsh/package.json"
seen = set()
delta = 0
old_names = set()
dsh_package = None
with tarfile.open(str(source), "r|gz") as old, tarfile.open(str(output), "w|gz", compresslevel=6, format=tarfile.PAX_FORMAT) as new:
    for entry in old:
        old_names.add(entry.name.rstrip("/"))
        if entry.name in (profile_path, meta_path) or entry.name.endswith(dsh_manifest_suffix):
            if not entry.isfile() or entry.name in seen:
                raise SystemExit(f"Unexpected metadata entry: {entry.name}")
            seen.add(entry.name)
            value = json.load(old.extractfile(entry))
            if entry.name == profile_path:
                bundles = value["dsh"]["profile"]["bundles"]
                if "dshmarket" in bundles: raise SystemExit("Marketplace already present")
                bundles.append("dshmarket")
            elif entry.name == meta_path:
                value["runtimeVersion"] = version
            else:
                value["dependencies"]["dshmarket"] = "*"
                dsh_package = entry.name
            data = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
            delta += len(data) - entry.size
            entry.size = len(data)
            new.addfile(entry, io.BytesIO(data))
        elif entry.isfile():
            new.addfile(entry, old.extractfile(entry))
        else:
            new.addfile(entry)
    if len(seen) != 3 or dsh_package is None:
        raise SystemExit("Required metadata not found exactly once")
    base = "opt/dsh/plugins/dshmarket"
    directories = {base, base + "/node_modules", base + "/node_modules/@deepseek-ai"}
    for name in additions:
        parent = PurePosixPath(name).parent
        while str(parent).startswith(base):
            directories.add(str(parent)); parent = parent.parent
    links = {
        "root/.dsh/profiles/node_modules/dshmarket": base,
        str(PurePosixPath(dsh_package).parent.parent.parent / "dshmarket"): base,
    }
    for peer in ("cordis", "schemastery", "dsh-settings"):
        links[f"{base}/node_modules/@deepseek-ai/{peer}"] = f"root/.dsh/profiles/node_modules/@deepseek-ai/{peer}"
    for name in sorted(directories, key=lambda x: (x.count("/"), x)):
        if name in old_names: raise SystemExit(f"Directory collision: {name}")
        item = tarfile.TarInfo(name); item.type = tarfile.DIRTYPE; item.mode = 0o755
        new.addfile(item)
    for name, data in sorted(additions.items()):
        if name in old_names: raise SystemExit(f"File collision: {name}")
        item = tarfile.TarInfo(name); item.size = len(data); item.mode = 0o644
        new.addfile(item, io.BytesIO(data)); delta += len(data)
    for name, target in links.items():
        if name in old_names or target not in old_names | directories:
            raise SystemExit(f"Unsafe or missing link target: {name} -> {target}")
        item = tarfile.TarInfo(name); item.type = tarfile.SYMTYPE; item.mode = 0o777
        item.linkname = posixpath.relpath(target, posixpath.dirname(name))
        new.addfile(item)

with output.open("rb") as f:
    digest = hashlib.file_digest(f, "sha256").hexdigest()
manifest["version"] = version
manifest["rootfs"].update({"sha256": digest, "compressedBytes": output.stat().st_size,
    "extractedBytes": manifest["rootfs"]["extractedBytes"] + delta,
    "url": "https://localhost.invalid/embedded-whale-market1.bundle"})
manifest["profileLinks"] += 1
manifest["mobile"]["dsh"]["profile"]["bundles"].append("dshmarket")
output_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("Bundled market runtime:", digest, "bytes:", output.stat().st_size, "added extracted:", delta)
