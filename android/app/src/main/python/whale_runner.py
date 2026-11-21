import io
import json
import os
import sys
import traceback
from contextlib import redirect_stdout, redirect_stderr


def run_code(code: str, work_dir: str = "") -> str:
    """Execute Python code inside the target project directory and capture stdout/stderr."""
    prev_cwd = os.getcwd()
    stdout_buf = io.StringIO()
    stderr_buf = io.StringIO()
    ok = True
    error_text = ""

    try:
        if work_dir:
            os.makedirs(work_dir, exist_ok=True)
            os.chdir(work_dir)
            if work_dir not in sys.path:
                sys.path.insert(0, work_dir)
        namespace = {"__name__": "__main__", "__file__": os.path.join(work_dir or prev_cwd, "main.py")}
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

    return json.dumps(
        {
            "ok": ok,
            "stdout": stdout_buf.getvalue()[:12000],
            "stderr": stderr_buf.getvalue()[:4000],
            "error": error_text[:4000],
            "pythonVersion": sys.version.split()[0],
        },
        ensure_ascii=False,
    )
