import builtins
import html
import io
import json
import os
import re
import sys
import tempfile
import threading
import time
import traceback
import urllib.parse
import urllib.request
import zipfile
from collections import OrderedDict

MAX_PTC_SUB_CALLS = 16
MAX_REPL_SESSIONS = 12
MAX_WHEEL_BYTES = 25 * 1024 * 1024

_TL = threading.local()
_HOOK_LOCK = threading.Lock()
_HOOKS_INSTALLED = False
_SESSIONS_LOCK = threading.Lock()
_REPL_SESSIONS: "OrderedDict[str, dict]" = OrderedDict()

_ORIG_BUILTINS_OPEN = builtins.open
_ORIG_IO_OPEN = io.open


class _ThreadLocalStream:
    """Thread-local multiplexer for sys.stdout/sys.stderr so concurrent Workers never cross-talk."""

    def __init__(self, attr_name: str, fallback):
        self._attr = attr_name
        self._fallback = fallback

    def _target(self):
        buf = getattr(_TL, self._attr, None)
        return buf if buf is not None else self._fallback

    def write(self, s):
        return self._target().write(s)

    def writelines(self, lines):
        return self._target().writelines(lines)

    def flush(self):
        return self._target().flush()

    def isatty(self):
        return False

    def __getattr__(self, name):
        return getattr(self._target(), name)


def _resolve_thread_path(file):
    work_dir = getattr(_TL, "work_dir", None)
    if work_dir and isinstance(file, (str, os.PathLike)):
        p = os.fspath(file)
        if p and not os.path.isabs(p):
            return os.path.join(work_dir, p)
    return file


def _thread_scoped_builtins_open(file, *args, **kwargs):
    return _ORIG_BUILTINS_OPEN(_resolve_thread_path(file), *args, **kwargs)


def _thread_scoped_io_open(file, *args, **kwargs):
    return _ORIG_IO_OPEN(_resolve_thread_path(file), *args, **kwargs)


def _ensure_thread_local_hooks():
    global _HOOKS_INSTALLED
    if _HOOKS_INSTALLED:
        return
    with _HOOK_LOCK:
        if _HOOKS_INSTALLED:
            return
        sys.stdout = _ThreadLocalStream("stdout", sys.stdout)
        sys.stderr = _ThreadLocalStream("stderr", sys.stderr)
        builtins.open = _thread_scoped_builtins_open
        io.open = _thread_scoped_io_open
        _HOOKS_INSTALLED = True


class _WhaleToolsProxy:
    """Programmatic Tool Calling (PTC) bridge from embedded Python to Kotlin WhaleNativeToolRegistry."""

    def __init__(self, bridge):
        self._bridge = bridge
        self.calls = []

    def call(self, tool_name: str, args=None):
        if self._bridge is None:
            raise RuntimeError("当前 Python 运行模式未挂载工具桥接")
        clean_name = str(tool_name or "").strip()
        if not clean_name:
            raise ValueError("tool_name 不能为空")
        if clean_name == "whale_code_run":
            raise RuntimeError("禁止在 Python PTC 中递归调用 whale_code_run")
        if len(self.calls) >= MAX_PTC_SUB_CALLS:
            raise RuntimeError(f"单次 Python PTC 最多允许调用 {MAX_PTC_SUB_CALLS} 次子工具，请汇总已有结果")
        payload_args = args if isinstance(args, dict) else {}
        args_json = json.dumps(payload_args, ensure_ascii=False)
        raw_res = str(self._bridge.call(clean_name, args_json))
        try:
            parsed = json.loads(raw_res)
        except Exception:
            parsed = {"ok": True, "payload": raw_res}
        ok = bool(parsed.get("ok", True)) if isinstance(parsed, dict) else True
        self.calls.append({"tool": clean_name, "ok": ok})
        return parsed

    def __getattr__(self, tool_name: str):
        if tool_name.startswith("_"):
            raise AttributeError(tool_name)

        def _invoke(_args=None, **kwargs):
            merged = dict(_args) if isinstance(_args, dict) else {}
            merged.update(kwargs)
            return self.call(tool_name, merged)

        return _invoke


def _acquire_namespace(session_id: str, reset_session: bool):
    clean_sid = str(session_id or "").strip()
    if not clean_sid:
        return {}
    with _SESSIONS_LOCK:
        if reset_session and clean_sid in _REPL_SESSIONS:
            del _REPL_SESSIONS[clean_sid]
        if clean_sid in _REPL_SESSIONS:
            _REPL_SESSIONS.move_to_end(clean_sid)
            return _REPL_SESSIONS[clean_sid]
        ns = {}
        _REPL_SESSIONS[clean_sid] = ns
        while len(_REPL_SESSIONS) > MAX_REPL_SESSIONS:
            _REPL_SESSIONS.popitem(last=False)
        return ns


def clear_session(session_id: str) -> bool:
    clean_sid = str(session_id or "").strip()
    if not clean_sid:
        return False
    with _SESSIONS_LOCK:
        return _REPL_SESSIONS.pop(clean_sid, None) is not None


def _mount_extra_paths(extra_paths_json: str):
    if not extra_paths_json:
        return
    try:
        paths = json.loads(extra_paths_json)
    except Exception:
        return
    if not isinstance(paths, list):
        return
    for p in paths:
        if isinstance(p, str) and p.strip() and os.path.isdir(p) and p not in sys.path:
            sys.path.insert(0, p)


def run_code(
    code: str,
    work_dir: str = "",
    tool_bridge=None,
    session_id: str = "",
    reset_session: bool = False,
    extra_paths_json: str = "",
) -> str:
    """Execute Python code with strict per-thread stdout/stderr, writable work_dir isolation, and stateful REPL."""
    _ensure_thread_local_hooks()
    t0 = time.monotonic()
    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()
    inspected = []
    ok = True
    error_text = ""
    proxy = _WhaleToolsProxy(tool_bridge)

    resolved_dir = os.path.abspath(work_dir) if work_dir else os.getcwd()
    os.makedirs(resolved_dir, exist_ok=True)
    tmp_dir = os.path.join(resolved_dir, ".tmp")
    os.makedirs(tmp_dir, exist_ok=True)

    # Ensure process-level cwd / tempdir are never left pointing at read-only Android "/"
    try:
        if os.getcwd() == "/":
            os.chdir(resolved_dir)
    except Exception:
        pass
    os.environ["TMPDIR"] = tmp_dir
    tempfile.tempdir = tmp_dir
    _mount_extra_paths(extra_paths_json)

    def _inspect(*values):
        for v in values:
            if isinstance(v, (dict, list, tuple)):
                inspected.append(json.dumps(v, ensure_ascii=False, default=str)[:4000])
            else:
                inspected.append(str(v)[:4000])

    prev_stdout = getattr(_TL, "stdout", None)
    prev_stderr = getattr(_TL, "stderr", None)
    prev_work_dir = getattr(_TL, "work_dir", None)
    _TL.stdout = stdout_buf
    _TL.stderr = stderr_buf
    _TL.work_dir = resolved_dir
    try:
        if resolved_dir not in sys.path:
            sys.path.insert(0, resolved_dir)
        namespace = _acquire_namespace(session_id, reset_session)
        namespace.update(
            {
                "__name__": "__main__",
                "__file__": os.path.join(resolved_dir, "main.py"),
                "WORK_DIR": resolved_dir,
                "open": _thread_scoped_builtins_open,
                "tools": proxy,
                "inspect": _inspect,
            }
        )
        exec(compile(code, "<whale_python>", "exec"), namespace, namespace)
    except Exception:
        ok = False
        error_text = traceback.format_exc()
    finally:
        _TL.stdout = prev_stdout
        _TL.stderr = prev_stderr
        _TL.work_dir = prev_work_dir

    out_text = stdout_buf.getvalue()
    if inspected:
        inspect_block = "\n".join(inspected)
        out_text = (out_text + "\n" + inspect_block).strip() if out_text.strip() else inspect_block

    elapsed_ms = int((time.monotonic() - t0) * 1000)
    return json.dumps(
        {
            "ok": ok,
            "stdout": out_text[:12000],
            "stderr": stderr_buf.getvalue()[:4000],
            "error": error_text[:4000],
            "subCalls": proxy.calls,
            "workDir": resolved_dir,
            "sessionId": str(session_id or "").strip(),
            "elapsedMs": elapsed_ms,
            "pythonVersion": sys.version.split()[0],
        },
        ensure_ascii=False,
    )


def install_pure_wheel(package_name: str, site_dir: str, mirror_url: str = "") -> str:
    """Download and extract a pure-Python .whl (py3-none-any) from domestic/PyPI mirrors into site_dir."""
    pkg = re.sub(r"[^A-Za-z0-9._-]", "", str(package_name or "").strip()).lower()
    if not pkg:
        return json.dumps({"ok": False, "error": "包名不能为空"}, ensure_ascii=False)
    normalized = re.sub(r"[-_.]+", "-", pkg)
    os.makedirs(site_dir, exist_ok=True)

    mirrors = []
    if mirror_url and str(mirror_url).strip().startswith("https://"):
        mirrors.append(str(mirror_url).strip().rstrip("/") + "/")
    mirrors.extend(
        [
            "https://pypi.tuna.tsinghua.edu.cn/simple/",
            "https://mirrors.aliyun.com/pypi/simple/",
            "https://pypi.org/simple/",
        ]
    )

    headers = {"User-Agent": "WhaleAssistant/0.4 (Android Python PureWheel Installer)"}
    last_error = "无法连接任何 PyPI 镜像源"
    wheel_href = None
    wheel_filename = None
    used_mirror = ""

    for base in mirrors:
        index_url = f"{base}{normalized}/"
        try:
            req = urllib.request.Request(index_url, headers=headers)
            with urllib.request.urlopen(req, timeout=15) as resp:
                page = resp.read(2 * 1024 * 1024).decode("utf-8", errors="replace")
            matches = re.findall(r'<a[^>]+href="([^"]+)"[^>]*>([^<]+)</a>', page, flags=re.IGNORECASE)
            candidates = []
            for href, label in matches:
                clean_label = html.unescape(label).strip()
                if clean_label.endswith("-none-any.whl") and ("py3" in clean_label or "py2.py3" in clean_label):
                    clean_href = html.unescape(href).split("#", 1)[0]
                    abs_url = urllib.parse.urljoin(index_url, clean_href)
                    candidates.append((abs_url, clean_label))
            if candidates:
                wheel_href, wheel_filename = candidates[-1]
                used_mirror = base
                break
            last_error = f"镜像 {base} 未找到 {pkg} 的纯 Python (none-any.whl) 包（该包可能包含原生 C 扩展）"
        except Exception as err:
            last_error = f"{base}: {err}"

    if not wheel_href:
        return json.dumps({"ok": False, "package": pkg, "error": last_error}, ensure_ascii=False)

    try:
        req = urllib.request.Request(wheel_href, headers=headers)
        with urllib.request.urlopen(req, timeout=45) as resp:
            raw = resp.read(MAX_WHEEL_BYTES + 1)
        if len(raw) > MAX_WHEEL_BYTES:
            return json.dumps({"ok": False, "package": pkg, "error": "Wheel 包体积超过 25MB 上限"}, ensure_ascii=False)

        target_root = os.path.realpath(site_dir)
        extracted = 0
        with zipfile.ZipFile(io.BytesIO(raw), "r") as zf:
            for member in zf.infolist():
                if member.is_dir():
                    continue
                dest = os.path.realpath(os.path.join(target_root, member.filename))
                if not (dest == target_root or dest.startswith(target_root + os.sep)):
                    raise RuntimeError(f"检测到非法 Wheel 路径：{member.filename}")
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                with zf.open(member, "r") as src, _ORIG_BUILTINS_OPEN(dest, "wb") as dst:
                    dst.write(src.read())
                extracted += 1

        if target_root not in sys.path:
            sys.path.insert(0, target_root)

        return json.dumps(
            {
                "ok": True,
                "package": pkg,
                "wheel": wheel_filename,
                "mirror": used_mirror,
                "filesExtracted": extracted,
                "siteDir": target_root,
            },
            ensure_ascii=False,
        )
    except Exception as err:
        return json.dumps({"ok": False, "package": pkg, "error": f"下载或解压失败：{err}"}, ensure_ascii=False)
