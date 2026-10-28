#!/usr/bin/env python3
"""Patch the pinned dshmarket client/routes without mutating the source package."""

ROUTE_ANCHOR = """                        const plainTarget = installTargetFor(entry);
                        if (plainTarget === null) {
                            sendJson(response, 400, { error: 'unsupported source url' });
                            return;
                        }
"""
ROUTE_INSERT = """                        // Android host: never run the desktop pnpm installer here.
                        // The device stores only a proposal; the user must review it in the mobile manager.
                        if (body.force === true || body.version !== undefined || !NPM_NAME_RE.test(plainTarget)) {
                            sendJson(response, 400, { error: '手机端暂不支持指定版本或非 npm 来源，请使用插件管理手动核对。' });
                            return;
                        }
                        const port = process.env.DSH_DEVICE_BRIDGE_PORT || '';
                        const token = process.env.DSH_DEVICE_BRIDGE_TOKEN || '';
                        if (!/^[0-9]{1,5}$/.test(port) || Number(port) < 1024 || Number(port) > 65535 || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
                            sendJson(response, 400, { error: '手机安装请求服务不可用，请在插件管理中操作。' });
                            return;
                        }
                        const mobileAbort = new AbortController();
                        const mobileTimer = setTimeout(() => mobileAbort.abort(), 5000);
                        try {
                            const reply = await fetch(`http://127.0.0.1:${port}/device-command`, {
                                method: 'POST', redirect: 'error', signal: mobileAbort.signal,
                                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                                body: JSON.stringify({ command: 'pluginMarketProposal', param: plainTarget }),
                            });
                            const body = await reply.json();
                            if (!reply.ok || body.ok !== true || body.exitCode !== 0) throw new Error('proposal rejected');
                            sendJson(response, 400, { mobilePending: true, error: `已提交 ${plainTarget}，请打开助手的插件管理，停止运行环境并确认安装。当前尚未安装。` });
                        } catch (_) {
                            sendJson(response, 400, { error: '请求未确认，请到插件管理检查待处理请求；若不存在再重试。' });
                        } finally { clearTimeout(mobileTimer); }
                        return;
"""
CLIENT_ANCHOR = """					setBusyUrl(null);
					sessionStorage.removeItem("dshm-pending");
					if (status === 200 && body.ok && body.hot && pluginCategories(plugin).includes("theme")) {
"""
CLIENT_INSERT = """					if (body.mobilePending === true) {
						setRecords((list) => patch(list, recordId, { state: "warned", reason: String(body.error || "请到手机插件管理确认安装") }));
						setOperationsOpen(true);
						return;
					}
"""
UPDATE_ANCHOR = """        registerRoute(captureLegacy('/dsh-market/update', {
            kind: 'exact',
            path: '/dsh-market/update',
            handler: async (request, response) => {
                if (request.method !== 'POST') {
                    response.writeHead(405, { allow: 'POST' });
                    response.end();
                    return;
                }
                if (!sameOrigin(request)) {
                    sendJson(response, 403, { error: 'untrusted origin' });
                    return;
                }
                try {
                    await withMutationLock(response, 'install', async () => {
                        const body = (await readJsonBody(request));
                        const name = typeof body.name === 'string' ? body.name : '';
"""
UPDATE_INSERT = """                        // Mobile update: propose only; the Android manager performs the transaction after user review.
                        const version = body.eligibleVersion === undefined ? null : body.eligibleVersion;
                        if (!NPM_NAME_RE.test(name) || name.includes('..') || name.length > 214 || body.force === true || body.restore === true || body.compatVersion !== undefined || (version !== null && (typeof version !== 'string' || version.length > 64 || !/^[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?$/.test(version)))) {
                            sendJson(response, 400, { error: '此更新选项不能交给手机管理器，请在插件管理中手动操作。' });
                            return;
                        }
                        const port = process.env.DSH_DEVICE_BRIDGE_PORT || '';
                        const token = process.env.DSH_DEVICE_BRIDGE_TOKEN || '';
                        if (!/^[0-9]{1,5}$/.test(port) || Number(port) < 1024 || Number(port) > 65535 || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
                            sendJson(response, 400, { error: '手机更新请求服务不可用，请在插件管理中操作。' });
                            return;
                        }
                        const mobileAbort = new AbortController();
                        const mobileTimer = setTimeout(() => mobileAbort.abort(), 5000);
                        try {
                            const reply = await fetch(`http://127.0.0.1:${port}/device-command`, {
                                method: 'POST', redirect: 'error', signal: mobileAbort.signal,
                                headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
                                body: JSON.stringify({ command: 'pluginMarketUpdateProposal', param: JSON.stringify({ name, suggestedVersion: version }) }),
                            });
                            const result = await reply.json();
                            if (!reply.ok || result.ok !== true || result.exitCode !== 0) throw new Error('proposal rejected');
                            sendJson(response, 400, { mobilePending: true, error: `已提交 ${name} 的更新请求，请停止运行环境并在手机插件管理确认。当前尚未更新。` });
                        } catch (_) {
                            sendJson(response, 400, { error: '请求未确认，请到插件管理检查待处理请求；若不存在再重试。' });
                        } finally { clearTimeout(mobileTimer); }
                        return;
"""
UPDATE_CLIENT_ANCHOR = """                    sessionStorage.removeItem("dshm-updating");""" # actual client uses tabs, replaced below
UPDATE_CLIENT_ANCHOR = "\t\t\t\t\tsessionStorage.removeItem(\"dshm-updating\");\n\t\t\t\t\tsetUpdatingName(null);\n"
UPDATE_CLIENT_INSERT = """\t\t\t\t\tif (body.mobilePending === true) {
\t\t\t\t\t\tsetRecords((list) => patch(list, updateRecordId, { state: "warned", reason: String(body.error || "请到手机插件管理确认更新") }));
\t\t\t\t\t\tsetOperationsOpen(true);
\t\t\t\t\t\treturn;
\t\t\t\t\t}
"""
def insert_once(data: bytes, anchor: str, addition: str) -> bytes:
    text = data.decode('utf-8')
    if text.count(anchor) != 1:
        raise ValueError('pinned market patch anchor missing or ambiguous')
    return text.replace(anchor, anchor + addition, 1).encode('utf-8')

def patch_routes(data: bytes) -> bytes:
    return insert_once(data, ROUTE_ANCHOR, ROUTE_INSERT)

def patch_client(data: bytes) -> bytes:
    text = data.decode('utf-8')
    if text.count(CLIENT_ANCHOR) != 1:
        raise ValueError('pinned market client anchor missing or ambiguous')
    prefix, suffix = CLIENT_ANCHOR.rsplit('\t\t\t\t\tif (status', 1)
    return insert_once(text.replace(CLIENT_ANCHOR, prefix + CLIENT_INSERT + '\t\t\t\t\tif (status' + suffix, 1).encode('utf-8'), UPDATE_CLIENT_ANCHOR, UPDATE_CLIENT_INSERT)


def patch_routes_v2(data: bytes) -> bytes:
    return insert_once(patch_routes(data), UPDATE_ANCHOR, UPDATE_INSERT)

def patch_client_v2(data: bytes) -> bytes:
    return insert_once(patch_client(data), UPDATE_CLIENT_ANCHOR, UPDATE_CLIENT_INSERT)
