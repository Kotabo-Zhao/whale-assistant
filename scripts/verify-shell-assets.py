#!/usr/bin/env python3
"""拒绝没有 Capacitor 外壳的安装包。

Capacitor 在缺少 capacitor.config.json 时仍使用默认地址 https://localhost。
WebViewLocalServer 打开 assets/public/index.html 失败时返回 null，
WebView 不再拦截这次文档请求，而是真的去连接 localhost，页面就是
net::ERR_CONNECTION_REFUSED。
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

INDEX = "assets/public/index.html"
CONFIG = "assets/capacitor.config.json"
MISSING = (
    "缺少 Capacitor 外壳 assets/public/index.html。"
    "本地服务器打不开首页时会返回空响应，WebView 改为连接 https://localhost/，"
    "系统错误页显示 net::ERR_CONNECTION_REFUSED。"
)


def fail(message: str) -> None:
    raise SystemExit(message)


def read_members(source: Path, apk: bool) -> dict[str, bytes]:
    if apk:
        if not source.is_file():
            fail(f"APK 不存在：{source}")
        with zipfile.ZipFile(source) as archive:
            names = set(archive.namelist())
            if INDEX not in names:
                fail(MISSING)
            if CONFIG not in names:
                fail("缺少 assets/capacitor.config.json。请先执行 pnpm run android:sync。")
            return {INDEX: archive.read(INDEX), CONFIG: archive.read(CONFIG)}
    index = source / "public" / "index.html"
    config = source / "capacitor.config.json"
    if not index.is_file():
        fail(MISSING + f" 检查目录：{source}")
    if not config.is_file():
        fail(f"缺少 {config}。请先执行 pnpm run android:sync。")
    return {INDEX: index.read_bytes(), CONFIG: config.read_bytes()}


def verify_bytes(members: dict[str, bytes]) -> None:
    html = members[INDEX]
    if b'<div id="root">' not in html:
        fail("外壳 index.html 不是当前应用页面：缺少 <div id=\"root\">。")
    if b"/src/main.tsx" in html:
        fail("外壳 index.html 仍是 Vite 源码入口 /src/main.tsx，没有执行 pnpm run build。")
    if b'src="./assets/' not in html and b"src='./assets/" not in html and b'src="/assets/' not in html and b"src='/assets/" not in html:
        fail("外壳 index.html 没有引用构建后的 assets 脚本。")
    try:
        config = json.loads(members[CONFIG].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"capacitor.config.json 无法解析：{error}")
    if not isinstance(config, dict):
        fail("capacitor.config.json 必须是对象。")
    if config.get("appId") != "io.deepseekharness.mobile":
        fail("capacitor.config.json 的 appId 不是 io.deepseekharness.mobile。")
    server = config.get("server") or {}
    if not isinstance(server, dict):
        fail("capacitor.config.json 的 server 必须是对象。")
    url = server.get("url")
    if isinstance(url, str) and url.strip():
        fail("capacitor.config.json 设置了 server.url，外壳会离开内置页面。")
    scheme = server.get("androidScheme", "https")
    if scheme != "https":
        fail(f"androidScheme 必须是 https，实际为 {scheme!r}。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--assets", type=Path)
    args = parser.parse_args()
    if args.apk is not None:
        verify_bytes(read_members(args.apk, True))
        print(f"SHELL_ASSETS_OK: {args.apk}")
    else:
        verify_bytes(read_members(args.assets, False))
        print(f"SHELL_ASSETS_OK: {args.assets}")


if __name__ == "__main__":
    main()
