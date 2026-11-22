import builtins
import io
import json
import os
import sys
import threading
import time
import traceback

MAX_PTC_SUB_CALLS = 16

_TL = threading.local()
_STREAM_INSTALL_LOCK = threading.Lock()
_STREAMS_INSTALLED = False


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


def _ensure_thread_local_streams():
    global _STREAMS_INSTALLED
    if _STREAMS_INSTALLED:
        return
    with _STREAM_INSTALL_LOCK:
        if _STREAMS_INSTALLED:
            return
        sys.stdout = _ThreadLocalStream("stdout", sys.stdout)
        sys.stderr = _ThreadLocalStream("stderr", sys.stderr)
        _STREAMS_INSTALLED = True


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


def run_code(code: str, work_dir: str = "", tool_bridge=None) -> str:
    """Execute Python code with strict per-thread stdout/stderr and work_dir isolation."""
    _ensure_thread_local_streams()
    t0 = time.monotonic()
    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()
    inspected = []
    ok = True
    error_text = ""
    proxy = _WhaleToolsProxy(tool_bridge)

    resolved_dir = os.path.abspath(work_dir) if work_dir else os.getcwd()
    os.makedirs(resolved_dir, exist_ok=True)

    def _inspect(*values):
        for v in values:
            if isinstance(v, (dict, list, tuple)):
                inspected.append(json.dumps(v, ensure_ascii=False, default=str)[:4000])
            else:
                inspected.append(str(v)[:4000])

    def _scoped_open(file, *args, **kwargs):
        if isinstance(file, (str, os.PathLike)):
            p = os.fspath(file)
            if not os.path.isabs(p):
                file = os.path.join(resolved_dir, p)
        return builtins.open(file, *args, **kwargs)

    prev_stdout = getattr(_TL, "stdout", None)
    prev_stderr = getattr(_TL, "stderr", None)
    _TL.stdout = stdout_buf
    _TL.stderr = stderr_buf
    try:
        if resolved_dir not in sys.path:
            sys.path.insert(0, resolved_dir)
        namespace = {
            "__name__": "__main__",
            "__file__": os.path.join(resolved_dir, "main.py"),
            "WORK_DIR": resolved_dir,
            "open": _scoped_open,
            "tools": proxy,
            "inspect": _inspect,
        }
        exec(compile(code, "<whale_python>", "exec"), namespace, namespace)
    except Exception:
        ok = False
        error_text = traceback.format_exc()
    finally:
        _TL.stdout = prev_stdout
        _TL.stderr = prev_stderr

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
            "elapsedMs": elapsed_ms,
            "pythonVersion": sys.version.split()[0],
        },
        ensure_ascii=False,
    )
