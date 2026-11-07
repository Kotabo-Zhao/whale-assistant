"""Pinned runtime repairs for the main conversation, applied before packaging."""
from pathlib import Path

def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("Main queue patch anchor changed")
    return text.replace(old, new, 1)

def patch_agent(text):
    helper = Path(__file__).with_name("mobile-main-queue.mjs").read_text(encoding="utf-8").replace("export function ", "function ")
    text = helper + "\n" + text
    text = replace_once(text, "\t\t\tconst request = this.buildRequest(config, preparedCall, assembly.tools, {",
        "\t\t\tconst queueRequest = mobileQueueRequest(signal, () => this.inbox.hasPending);\n\t\t\tconst request = this.buildRequest(config, preparedCall, assembly.tools, {")
    text = replace_once(text, "}, startsRequestSeries, signal);", "}, startsRequestSeries, queueRequest.signal);")
    text = replace_once(text, "for await (const chunk of stream) {", "for await (const chunk of queueRequest.stream(stream)) {")
    text = replace_once(text, "\tasync kick() {\n\t\ttry {",
        "\tasync kick() {\n\t\tconst head = this.inbox.nextTurn[0] ?? this.inbox.nextStep[0];\n\t\tlet failed = false;\n\t\ttry {")
    text = replace_once(text, "} catch (_error) {} finally {", "} catch (_error) { failed = true; } finally {")
    text = replace_once(text, "const { turn, wakeRequested } = this.phase;",
        "const { turn, wakeRequested, abort } = this.phase;\n\t\t\t\tconst resume = failed && !abort.signal.aborted && head !== (this.inbox.nextTurn[0] ?? this.inbox.nextStep[0]);")
    text = replace_once(text, "if (wakeRequested && this.inbox.hasPending) this.wakeDriver();",
        "if ((wakeRequested || resume) && this.inbox.hasPending) this.wakeDriver();")
    return text

def patch_client(text):
    anchor = "\t\t\tasync prompt(content, mode, signal, requestId) {"
    replacement = """\t\t\t_mobileRetryIds;
            mobileObserved = new Map();
            get mobileRetryIds() {
                if (this._mobileRetryIds) return this._mobileRetryIds;
                const saved = new Map();
                try {
                    for (const [key,id] of Object.entries(JSON.parse(localStorage.getItem("dsh.mobile.prompt-retry."+this.sessionId) ?? "{}"))) {
                        if (/^[a-f0-9]{64}$/.test(key) && /^[a-f0-9-]{36}$/.test(id)) saved.set(key,id);
                    }
                } catch {}
                return this._mobileRetryIds = saved;
            }
            async prompt(content, mode, signal, requestId) {
                const bytes = new TextEncoder().encode(JSON.stringify(content));
                const hash = await crypto.subtle.digest("SHA-256",bytes);
                const key = Array.from(new Uint8Array(hash),b=>b.toString(16).padStart(2,"0")).join("");
                const storageKey = "dsh.mobile.prompt-retry." + this.sessionId;
                try {
                    const saved = JSON.parse(localStorage.getItem(storageKey) ?? "{}");
                    for (const [hash,id] of Object.entries(saved)) {
                        if (/^[a-f0-9]{64}$/.test(hash) && /^[a-f0-9-]{36}$/.test(id)) this.mobileRetryIds.set(hash,id);
                    }
                } catch {}
                const remember = () => {
                    try { localStorage.setItem(storageKey,JSON.stringify(Object.fromEntries(this.mobileRetryIds))); } catch {}
                };
                const originalId = requestId;
                requestId = this.mobileRetryIds.get(key) ?? requestId ?? randomUUID();
                if (originalId !== undefined && requestId !== originalId) {
                    const settlement = this.submissionSettlements.get(originalId);
                    if (settlement) {
                        this.submissionSettlements.delete(originalId);
                        this.submissionSettlements.set(requestId,settlement);
                        this.pendingSubmissions = this.pendingSubmissions.map(row =>
                            row.requestId === originalId ? {...row,requestId} : row);
                    }
                }
                const abort = new AbortController();
                const cancel = () => abort.abort(signal.reason);
                signal?.addEventListener("abort",cancel,{once:true});
                if (signal?.aborted) cancel();
                let timer;
                try {
                    const timeout = new Promise((_,reject) => {
                        timer = setTimeout(() => {
                            abort.abort();
                            reject(new Error("发送确认超时，消息已保留。重试会核对原请求。"));
                        },15000);
                    });
                    const result = await Promise.race([this.mobilePromptRequest(content,mode,abort.signal,requestId),timeout]);
                    if (result.ok && this.mobileObserved.has(requestId)) this.scheduleObservedRetirement(requestId,this.mobileObserved.get(requestId));
                    if (!result.ok || this.submissionSettlements.has(requestId)) this.mobileRetryIds.set(key,requestId);
                    else this.mobileRetryIds.delete(key);
                    remember();
                    return result;
                } catch (error) {
                    // An absent acknowledgement cannot prove that the Host rejected the request.
                    this.mobileRetryIds.set(key,requestId);
                    remember();
                    this.retireFailedSubmission(requestId);
                    this.firstPromptPendingTurn = false;
                    const failure = new RemoteError("gateway/connection-lost",
                        error instanceof Error ? error.message : "发送连接中断，消息已保留，请重试。",{});
                    this.promptError = {op:"send",error:failure};
                    this.notifier.markDirty();
                    return {ok:false,error:failure};
                } finally {
                    clearTimeout(timer);
                    signal?.removeEventListener("abort",cancel);
                }
            }
            async mobilePromptRequest(content, mode, signal, requestId) {"""
    text = replace_once(text,anchor,replacement)
    text = replace_once(text,"\t\t\t\tif (!result.ok) {\n\t\t\t\t\tif (requestId !== void 0) this.retireFailedSubmission(requestId);",
        "\t\t\t\tsignal?.throwIfAborted();\n\t\t\t\tif (!result.ok) {\n\t\t\t\t\tthis.firstPromptPendingTurn = false;\n\t\t\t\t\tif (requestId !== void 0) this.retireFailedSubmission(requestId);")
    text = replace_once(text, "this.submissionSettlements.delete(requestId);\n\t\t\t\tthis.pendingSubmissions = this.pendingSubmissions.filter",
        """this.submissionSettlements.delete(requestId);
                if (retirement.reason === "observed") {
                    this.mobileObserved.delete(requestId);
                    for (const [key,id] of this.mobileRetryIds) if (id === requestId) this.mobileRetryIds.delete(key);
                    try { localStorage.setItem("dsh.mobile.prompt-retry."+this.sessionId,JSON.stringify(Object.fromEntries(this.mobileRetryIds))); } catch {}
                }
                if (retirement.reason === "failed" && this.submissionSettlements.size === 0) this.firstPromptPendingTurn = false;
                this.pendingSubmissions = this.pendingSubmissions.filter""")
    text = replace_once(text,
        'observeSubmissionMessage(message, admitted) {\n\t\t\t\tconst source = message.source;\n\t\t\t\tif (source.kind !== "user" || !("rpcId" in source)) return;',
        'observeSubmissionMessage(message, admitted) {\n\t\t\t\tconst source = message.source;\n\t\t\t\tif (source.kind !== "user" || !("rpcId" in source)) return;\n' +
        '                if (this.submissionSettlements.has(source.rpcId) || [...this.mobileRetryIds.values()].includes(source.rpcId)) this.mobileObserved.set(source.rpcId,attachmentRefsIn(message.content));')
    guard = "if (this.submissionSettlements.size === 0) return;"
    if text.count(guard) != 2:
        raise ValueError("Submission observation guard changed")
    text = text.replace(guard,"if (this.submissionSettlements.size === 0 && this.mobileRetryIds.size === 0) return;")
    return text

def patch_conversation(text):
    return replace_once(text,
        'if (retirement !== void 0 && (await retirement).reason !== "observed") return { kind: "error" };',
        """if (retirement !== void 0) {
                    let deadline;
                    try {
                        const observed = await Promise.race([retirement,new Promise(resolve => {
                            deadline = setTimeout(() => resolve({reason:"timeout"}),15000);
                        })]);
                        if (observed.reason !== "observed") {
                            submission.abandon();
                            return {kind:"error",text:"发送已获确认，但对话同步中断。消息已保留，重试会核对原请求。"};
                        }
                    } finally { clearTimeout(deadline); }
                }""")

def replacements(members):
    result = {}
    for name, content in members.items():
        if name.endswith("/dsh-agent-loop/lib/index.js"):
            result[name] = patch_agent(content.decode()).encode()
        if name.endswith("/dsh-api-session-controller/lib/client.js"):
            result[name] = patch_client(content.decode()).encode()
        if name.endswith("/dsh-client-ui-conversation/lib/client.js"):
            result[name] = patch_conversation(content.decode()).encode()
        if name.endswith("/dsh-client-connection/lib/client.browser.js"):
            result[name] = patch_connection(content.decode()).encode()
    if len(result) != 4:
        raise ValueError("Main queue runtime members missing")
    return result

def patch_connection(text):
    text = Path(__file__).with_name("mobile-main-browser-compat.js").read_text(encoding="utf-8") + "\n" + text
    text = replace_once(text,
        "\t\t\t\t\tawait failed;\n\t\t\t\t\tif (!this.isRunning()) return;",
        "\t\t\t\t\tawait Promise.race([failed,waitForAbort(ac.signal)]);\n\t\t\t\t\tif (!this.isRunning()) return;")
    return replace_once(text,
        "stopNetworkWatch: watchBrowserNetwork(controller)",
        "stopNetworkWatch: handle.isLoopback ? () => {} : watchBrowserNetwork(controller)")
