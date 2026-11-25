import builtins
import html
import importlib
import importlib.metadata
import io
import json
import os
import re
import sys
import tempfile
import threading
import time
import traceback
import types
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
        _install_android_safe_shutil()
        _configure_android_matplotlib_fonts()
        _HOOKS_INSTALLED = True


def _install_android_safe_shutil():
    """Guard shutil.copystat/copy2 against Android SELinux xattr PermissionError in no_backup dirs."""
    try:
        import shutil
        _orig_copystat = shutil.copystat

        def _safe_copystat(src, dst, *, follow_symlinks=True):
            try:
                return _orig_copystat(src, dst, follow_symlinks=follow_symlinks)
            except (PermissionError, OSError):
                try:
                    st = os.stat(src, follow_symlinks=follow_symlinks)
                    os.utime(dst, ns=(st.st_atime_ns, st.st_mtime_ns))
                except Exception:
                    pass

        shutil.copystat = _safe_copystat
    except Exception:
        pass


def _configure_android_matplotlib_fonts():
    """Auto-wire Android system CJK fonts into matplotlib so Chinese charts never render boxes."""
    try:
        import matplotlib
        matplotlib.use("Agg", force=True)
        from matplotlib import font_manager, rcParams
        candidates = [
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansSC-Regular.otf",
            "/system/fonts/MiSansVF.ttf",
            "/system/fonts/HarmonyOS_Sans_SC.ttf",
            "/system/fonts/DroidSansFallback.ttf",
        ]
        for font_path in candidates:
            if os.path.isfile(font_path):
                font_manager.fontManager.addfont(font_path)
                prop = font_manager.FontProperties(fname=font_path)
                rcParams["font.sans-serif"] = [prop.get_name(), "DejaVu Sans"]
                break
        rcParams["axes.unicode_minus"] = False
    except Exception:
        pass



_AUTO_INSTALL_MAP = {
    "qrcode": "qrcode",
    "markdown": "markdown",
    "jinja2": "jinja2",
    "tabulate": "tabulate",
    "chardet": "chardet",
    "xlrd": "xlrd",
    "httpx": "httpx",
    "sympy": "sympy",
    "reportlab": "reportlab",
    "dateutil": "python-dateutil",
    "feedparser": "feedparser",
    "toml": "toml",
    "rich": "rich",
    "pydantic": "pydantic",
}
_AUTO_INSTALL_TRIED = set()


class _WhaleAutoInstallFinder:
    """sys.meta_path hook: lazily downloads pure-Python packages into writable SITE_PACKAGES on first import."""

    def __init__(self, site_dir: str):
        self._site_dir = site_dir

    def find_spec(self, fullname, path=None, target=None):
        top = fullname.split(".", 1)[0]
        pkg = _AUTO_INSTALL_MAP.get(top)
        if not pkg or top in _AUTO_INSTALL_TRIED:
            return None
        _AUTO_INSTALL_TRIED.add(top)
        try:
            res = json.loads(install_pure_wheel(pkg, self._site_dir))
            if res.get("ok"):
                importlib.invalidate_caches()
                for finder in sys.meta_path:
                    if finder is self:
                        continue
                    spec = getattr(finder, "find_spec", None)
                    if spec:
                        found = spec(fullname, path, target)
                        if found is not None:
                            return found
        except Exception:
            pass
        return None


def _ensure_auto_install_hook(site_dir: str):
    if not any(isinstance(f, _WhaleAutoInstallFinder) for f in sys.meta_path):
        sys.meta_path.append(_WhaleAutoInstallFinder(site_dir))


def _install_virtual_pip_module(site_dir: str):
    """Expose a virtual `pip` module so scripts doing `import pip; pip.main(['install', pkg])` work seamlessly."""
    mod = types.ModuleType("pip")

    def _install(pkg: str, mirror: str = "") -> dict:
        return json.loads(install_pure_wheel(pkg, site_dir, mirror))

    def _main(argv=None) -> int:
        args = list(argv or [])
        pkgs = [a for a in args if a and not a.startswith("-") and a.lower() not in ("install", "upgrade")]
        if not pkgs:
            return 0
        all_ok = True
        for p in pkgs:
            res = _install(p)
            print(json.dumps(res, ensure_ascii=False))
            if not res.get("ok"):
                all_ok = False
        return 0 if all_ok else 1

    mod.install = _install
    mod.main = _main
    sys.modules["pip"] = mod


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

    # Always align cwd with resolved_dir so glob/os.listdir/Path(".") match scratch/project WORK_DIR
    try:
        os.chdir(resolved_dir)
    except Exception:
        pass
    os.environ["TMPDIR"] = tmp_dir
    tempfile.tempdir = tmp_dir
    _mount_extra_paths(extra_paths_json)
    site_dir_resolved = ""
    try:
        parsed_extra = json.loads(extra_paths_json) if extra_paths_json else []
        if isinstance(parsed_extra, list) and parsed_extra and isinstance(parsed_extra[0], str):
            site_dir_resolved = parsed_extra[0].strip()
    except Exception:
        site_dir_resolved = ""
    if not site_dir_resolved:
        site_dir_resolved = os.path.join(resolved_dir, ".site-packages")
    os.makedirs(site_dir_resolved, exist_ok=True)
    if site_dir_resolved not in sys.path:
        sys.path.insert(0, site_dir_resolved)

    _install_virtual_pip_module(site_dir_resolved)
    _ensure_auto_install_hook(site_dir_resolved)

    def _pip_install(package_name: str, mirror_url: str = "") -> dict:
        raw_res = install_pure_wheel(package_name, site_dir_resolved, mirror_url)
        try:
            return json.loads(raw_res)
        except Exception:
            return {"ok": False, "error": raw_res}

    def _fetch_with_auth(url: str, max_chars: int = 8000) -> dict:
        return proxy.call("whale_web_fetch", {"url": str(url), "maxChars": int(max_chars)})

    def _installed_packages() -> list:
        names = set()
        try:
            for dist in importlib.metadata.distributions():
                n = dist.metadata.get("Name")
                if n:
                    names.add(n)
        except Exception:
            pass
        return sorted(names)

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
                "SITE_PACKAGES": site_dir_resolved,
                "pip_install": _pip_install,
                "installed_packages": _installed_packages,
                "fetch_with_auth": _fetch_with_auth,
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

    stdout_total = len(out_text)
    stdout_truncated = stdout_total > 24000
    stdout_file = ""
    if stdout_truncated:
        try:
            spill_path = os.path.join(resolved_dir, ".last_stdout.txt")
            with _ORIG_BUILTINS_OPEN(spill_path, "w", encoding="utf-8") as sf:
                sf.write(out_text)
            stdout_file = ".last_stdout.txt"
        except Exception:
            stdout_file = ""
        omitted = stdout_total - 23000
        stdout_inline = (
            out_text[:18000]
            + f"\n...[已省略中间 {omitted} 字符，完整 {stdout_total} 字符已保存至 {stdout_file or '.last_stdout.txt'}，可直接 open('.last_stdout.txt').read() 分段读取]...\n"
            + out_text[-5000:]
        )
    else:
        stdout_inline = out_text

    elapsed_ms = int((time.monotonic() - t0) * 1000)
    res_obj = {
        "ok": ok,
        "stdout": stdout_inline,
        "stderr": stderr_buf.getvalue()[:6000],
        "error": error_text[:6000],
        "subCalls": proxy.calls,
        "workDir": resolved_dir,
        "sessionId": str(session_id or "").strip(),
        "elapsedMs": elapsed_ms,
        "pythonVersion": sys.version.split()[0],
    }
    if stdout_truncated:
        res_obj["stdoutTruncated"] = True
        res_obj["stdoutTotalChars"] = stdout_total
        if stdout_file:
            res_obj["stdoutFile"] = stdout_file
    return json.dumps(res_obj, ensure_ascii=False)


def _parse_requires_dist(metadata_text: str) -> list:
    """Extract mandatory (non-extra) dependency package names from wheel METADATA."""
    deps = []
    for line in metadata_text.splitlines():
        if not line.startswith("Requires-Dist:"):
            continue
        spec = line.split(":", 1)[1].strip()
        if ";" in spec:
            req_part, marker = spec.split(";", 1)
            if "extra" in marker:
                continue
            if "sys_platform" in marker and "win32" in marker and "==" in marker:
                continue
            spec = req_part.strip()
        m = re.match(r"^([A-Za-z0-9][A-Za-z0-9._-]*)", spec)
        if m:
            deps.append(m.group(1))
    return deps


def _is_dist_installed(pkg_name: str, site_dir: str) -> bool:
    norm = re.sub(r"[-_.]+", "-", pkg_name.strip()).lower()
    mod_guess = norm.replace("-", "_")
    try:
        importlib.metadata.distribution(norm)
        return True
    except Exception:
        pass
    try:
        if importlib.util.find_spec(mod_guess) is not None:
            return True
    except Exception:
        pass
    if os.path.isdir(os.path.join(site_dir, mod_guess)) or os.path.isfile(os.path.join(site_dir, mod_guess + ".py")):
        return True
    return False


def _install_wheel_recursive(
    package_name: str,
    site_dir: str,
    mirror_url: str,
    visited: set,
    installed_list: list,
    skipped_deps: list,
    depth: int = 0,
) -> dict:
    pkg = re.sub(r"[^A-Za-z0-9._-]", "", str(package_name or "").strip()).lower()
    if not pkg:
        return {"ok": False, "error": "包名不能为空"}
    normalized = re.sub(r"[-_.]+", "-", pkg)
    if normalized in visited:
        return {"ok": True, "package": pkg, "alreadyVisited": True}
    visited.add(normalized)
    if depth > 0 and _is_dist_installed(normalized, site_dir):
        return {"ok": True, "package": pkg, "alreadyInstalled": True}

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

    headers = {"User-Agent": "WhaleAssistant/0.5 (Android Python PureWheel Installer)"}
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
        return {"ok": False, "package": pkg, "error": last_error}

    try:
        req = urllib.request.Request(wheel_href, headers=headers)
        with urllib.request.urlopen(req, timeout=45) as resp:
            raw = resp.read(MAX_WHEEL_BYTES + 1)
        if len(raw) > MAX_WHEEL_BYTES:
            return {"ok": False, "package": pkg, "error": "Wheel 包体积超过 25MB 上限"}

        target_root = os.path.realpath(site_dir)
        extracted = 0
        metadata_text = ""
        with zipfile.ZipFile(io.BytesIO(raw), "r") as zf:
            for member in zf.infolist():
                if member.is_dir():
                    continue
                dest = os.path.realpath(os.path.join(target_root, member.filename))
                if not (dest == target_root or dest.startswith(target_root + os.sep)):
                    raise RuntimeError(f"检测到非法 Wheel 路径：{member.filename}")
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                data = zf.read(member)
                if member.filename.endswith(".dist-info/METADATA") and not metadata_text:
                    metadata_text = data.decode("utf-8", errors="replace")
                with _ORIG_BUILTINS_OPEN(dest, "wb") as dst:
                    dst.write(data)
                extracted += 1

        if target_root not in sys.path:
            sys.path.insert(0, target_root)
        importlib.invalidate_caches()
        installed_list.append(wheel_filename)

        # 自动递归安装纯 Python 子依赖（最多 4 层、累计 12 个包）
        if depth < 4 and metadata_text:
            for dep in _parse_requires_dist(metadata_text):
                if len(visited) >= 12:
                    break
                dep_norm = re.sub(r"[-_.]+", "-", dep).lower()
                if dep_norm in visited or _is_dist_installed(dep_norm, target_root):
                    continue
                sub = _install_wheel_recursive(dep_norm, target_root, mirror_url, visited, installed_list, skipped_deps, depth + 1)
                if not sub.get("ok"):
                    skipped_deps.append({"package": dep_norm, "reason": sub.get("error", "")[:120]})

        return {
            "ok": True,
            "package": pkg,
            "wheel": wheel_filename,
            "mirror": used_mirror,
            "filesExtracted": extracted,
            "installedWheels": installed_list,
            "skippedDeps": skipped_deps,
            "siteDir": target_root,
        }
    except Exception as err:
        return {"ok": False, "package": pkg, "error": f"下载或解压失败：{err}"}


def install_pure_wheel(package_name: str, site_dir: str, mirror_url: str = "") -> str:
    """Download and extract a pure-Python .whl (py3-none-any) + recursive pure deps into site_dir."""
    res = _install_wheel_recursive(
        package_name=package_name,
        site_dir=site_dir,
        mirror_url=mirror_url,
        visited=set(),
        installed_list=[],
        skipped_deps=[],
        depth=0,
    )
    return json.dumps(res, ensure_ascii=False)
