import io
import json
import os
import sys
import traceback
from contextlib import redirect_stdout, redirect_stderr

MAX_PTC_SUB_CALLS = 16


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
    """Execute Python code inside the target project directory with optional PTC tool bridge."""
    prev_cwd = os.getcwd()
    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()
    inspected = []
    ok = True
    error_text = ""
    proxy = _WhaleToolsProxy(tool_bridge)

    def _inspect(*values):
        for v in values:
            if isinstance(v, (dict, list, tuple)):
                inspected.append(json.dumps(v, ensure_ascii=False, default=str)[:4000])
            else:
                inspected.append(str(v)[:4000])

    try:
        if work_dir:
            os.makedirs(work_dir, exist_ok=True)
            os.chdir(work_dir)
            if work_dir not in sys.path:
                sys.path.insert(0, work_dir)
        namespace = {
            "__name__": "__main__",
            "__file__": os.path.join(work_dir or prev_cwd, "main.py"),
            "tools": proxy,
            "inspect": _inspect,
        }
        with redirect_stdout(stdout_buf), redirect_stderr(stderr_buf):
            exec(compile(code, "<whale_python>", "exec"), namespace, namespace)
    except Exception:
        ok = False
        error_text = traceback.format_exc()
    finally:
        try:
            os.chdir(prev_cwd)
        except Exception:
            pass

    out_text = stdout_buf.getvalue()
    if inspected:
        inspect_block = "\n".join(inspected)
        out_text = (out_text + "\n" + inspect_block).strip() if out_text.strip() else inspect_block

    return json.dumps(
        {
            "ok": ok,
            "stdout": out_text[:12000],
            "stderr": stderr_buf.getvalue()[:4000],
            "error": error_text[:4000],
            "subCalls": proxy.calls,
            "pythonVersion": sys.version.split()[0],
        },
        ensure_ascii=False,
    )
