#!/usr/bin/env python3
"""
Whale Assistant 轻量远程算力 & 本地 stdio MCP 桥接网关（零第三方依赖，仅需 Python 3.9+）

用法示例：
  python3 scripts/whale-remote-bridge.py --port 8765 --token my-secret-token
  python3 scripts/whale-remote-bridge.py --port 8765 --token my-secret-token --mcp-config mcp.json

mcp.json 示例（把本地 stdio MCP，例如 B 站 MCP / uv / npx 工具，暴露给手机端）：
{
  "bilibili": {
    "command": "uvx",
    "args": ["bilibili-mcp"],
    "env": {"SESSDATA": "..."}
  }
}
"""
import argparse
import json
import os
import platform
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MCP_SERVERS: dict = {}
AUTH_TOKEN: str = ""
DEFAULT_WORKDIR: str = os.getcwd()


def _mcp_rpc(server_name: str, method: str, params: dict, timeout: int = 30) -> dict:
    cfg = MCP_SERVERS.get(server_name)
    if not cfg:
        return {"ok": False, "error": f"未配置 MCP 服务：{server_name}，可用服务：{list(MCP_SERVERS.keys())}"}
    cmd = [cfg["command"], *cfg.get("args", [])]
    env = os.environ.copy()
    env.update({str(k): str(v) for k, v in cfg.get("env", {}).items()})
    proc = subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
        cwd=cfg.get("cwd") or DEFAULT_WORKDIR,
    )
    try:
        init_req = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "whale-remote-bridge", "version": "1.0.0"},
            },
        }
        inited = {"jsonrpc": "2.0", "method": "notifications/initialized"}
        call_req = {"jsonrpc": "2.0", "id": 2, "method": method, "params": params}
        payload = "\n".join(json.dumps(x, ensure_ascii=False) for x in (init_req, inited, call_req)) + "\n"
        stdout, stderr = proc.communicate(payload, timeout=timeout)
        responses = []
        for line in stdout.splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                msg = json.loads(line)
                if msg.get("id") == 2:
                    return {"ok": "error" not in msg, "result": msg.get("result"), "error": msg.get("error")}
                responses.append(msg)
            except json.JSONDecodeError:
                continue
        return {
            "ok": False,
            "error": f"未收到 MCP 响应 (exit={proc.returncode})",
            "stderr": stderr[-1500:],
            "rawCount": len(responses),
        }
    except subprocess.TimeoutExpired:
        proc.kill()
        return {"ok": False, "error": f"MCP 调用超时（>{timeout}s）"}


class BridgeHandler(BaseHTTPRequestHandler):
    def _send(self, code: int, body: dict):
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authed(self) -> bool:
        if not AUTH_TOKEN:
            return True
        auth = self.headers.get("Authorization", "")
        return auth == f"Bearer {AUTH_TOKEN}"

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 2_000_000:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self):
        if not self._authed():
            self._send(401, {"ok": False, "error": "Unauthorized"})
            return
        if self.path == "/health":
            self._send(200, {
                "ok": True,
                "host": platform.node(),
                "os": platform.platform(),
                "python": sys.version.split()[0],
                "mcpServers": list(MCP_SERVERS.keys()),
            })
            return
        self._send(404, {"ok": False, "error": "Not found"})

    def do_POST(self):
        if not self._authed():
            self._send(401, {"ok": False, "error": "Unauthorized"})
            return
        try:
            body = self._read_json()
        except Exception as e:
            self._send(400, {"ok": False, "error": f"Invalid JSON: {e}"})
            return

        if self.path == "/exec":
            command = str(body.get("command", "")).strip()
            language = str(body.get("language", "shell")).strip().lower()
            timeout = max(3, min(180, int(body.get("timeoutSec", 45))))
            if not command:
                self._send(400, {"ok": False, "error": "Missing command"})
                return
            try:
                if language == "python":
                    args = [sys.executable, "-c", command]
                    proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout, cwd=DEFAULT_WORKDIR)
                else:
                    proc = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=timeout, cwd=DEFAULT_WORKDIR)
                self._send(200, {
                    "ok": proc.returncode == 0,
                    "exitCode": proc.returncode,
                    "stdout": proc.stdout[-16000:],
                    "stderr": proc.stderr[-4000:],
                })
            except subprocess.TimeoutExpired:
                self._send(200, {"ok": False, "error": f"执行超时（>{timeout}s）"})
            except Exception as e:
                self._send(500, {"ok": False, "error": str(e)})
            return

        if self.path == "/mcp/list":
            server = str(body.get("server", "")).strip()
            if not server:
                self._send(200, {"ok": True, "servers": list(MCP_SERVERS.keys())})
                return
            self._send(200, _mcp_rpc(server, "tools/list", {}))
            return

        if self.path == "/mcp/call":
            server = str(body.get("server", "")).strip()
            tool = str(body.get("tool", "")).strip()
            arguments = body.get("arguments") or {}
            if not server or not tool:
                self._send(400, {"ok": False, "error": "Missing server or tool"})
                return
            self._send(200, _mcp_rpc(server, "tools/call", {"name": tool, "arguments": arguments}))
            return

        self._send(404, {"ok": False, "error": "Not found"})

    def log_message(self, fmt, *args):
        sys.stderr.write(f"[whale-bridge] {fmt % args}\n")


def main():
    global AUTH_TOKEN, MCP_SERVERS, DEFAULT_WORKDIR
    parser = argparse.ArgumentParser(description="Whale Assistant 远程算力与本地 stdio MCP 桥接网关")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--token", default=os.environ.get("WHALE_BRIDGE_TOKEN", ""))
    parser.add_argument("--mcp-config", default="")
    parser.add_argument("--workdir", default=os.getcwd())
    args = parser.parse_args()

    AUTH_TOKEN = args.token
    DEFAULT_WORKDIR = os.path.abspath(args.workdir)
    if args.mcp_config and os.path.isfile(args.mcp_config):
        with open(args.mcp_config, "r", encoding="utf-8") as f:
            MCP_SERVERS = json.load(f)

    server = ThreadingHTTPServer((args.host, args.port), BridgeHandler)
    print(f"Whale Remote Bridge listening on http://{args.host}:{args.port} (mcp={list(MCP_SERVERS.keys())})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
