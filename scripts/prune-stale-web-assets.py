#!/usr/bin/env python3
"""Prune stale hashed Vite assets from an explicit build snapshot after validating the current dist."""
import hashlib
import json
import re
import sys
from pathlib import Path

pattern = re.compile(r"^.+-[A-Za-z0-9_-]{8}\.(?:js|css|png|svg|webp|woff2?)$")
def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

def run(source, public, receipt, apply):
    source = source.resolve(strict=True)
    public = public.resolve(strict=True)
    receipt = receipt.resolve(strict=False)
    if source == public or public in receipt.parents or source in receipt.parents:
        raise ValueError("source, target and receipt must be separate")
    source_assets = source / "assets"
    target_assets = public / "assets"
    for folder in (source_assets, target_assets):
        if folder.is_symlink() or not folder.is_dir():
            raise ValueError("assets directory must be a real directory")
    for f in (source / "index.html", public / "index.html"):
        if f.is_symlink() or not f.is_file(): raise ValueError("missing regular index")
    if digest(source / "index.html") != digest(public / "index.html"):
        raise ValueError("target index does not match current build")
    current = {}
    for f in source_assets.iterdir():
        if f.is_symlink() or not f.is_file() or not pattern.fullmatch(f.name):
            raise ValueError("unexpected current asset")
        current[f.name] = digest(f)
    if not current: raise ValueError("current asset set is empty")
    for name, expected in current.items():
        f = target_assets / name
        if f.is_symlink() or not f.is_file() or digest(f) != expected:
            raise ValueError("target missing or changed current asset: " + name)
    stale = []
    untouched = []
    for f in target_assets.iterdir():
        if f.name in current: continue
        if f.is_symlink() or not f.is_file() or not pattern.fullmatch(f.name):
            untouched.append(f.name); continue
        stale.append({"name": f.name, "bytes": f.stat().st_size, "sha256": digest(f)})
    stale.sort(key=lambda row: row["name"])
    summary = {"source": str(source), "target": str(public), "indexSha256": digest(source / "index.html"),
        "current": len(current), "stale": stale, "untouched": sorted(untouched), "state": "plan"}
    if apply:
        with receipt.open("x", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)
        for row in stale:
            f = target_assets / row["name"]
            if f.is_symlink() or not f.is_file() or f.stat().st_size != row["bytes"] or digest(f) != row["sha256"]:
                raise RuntimeError("asset changed after audit: " + row["name"])
            f.unlink()
    print(json.dumps({"current": len(current), "stale": len(stale), "staleBytes": sum(x["bytes"] for x in stale), "untouched": len(untouched), "applied": apply}))

if __name__ == "__main__":
    if len(sys.argv) not in (4, 5) or (len(sys.argv) == 5 and sys.argv[4] != "--apply"):
        raise SystemExit("usage: prune-stale-web-assets.py DIST PUBLIC RECEIPT [--apply]")
    run(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), len(sys.argv) == 5)
