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
                        if (/^[a-f0-9]{64}(?::[a-f0-9-]{36})?$/.test(key) && /^[a-f0-9-]{36}$/.test(id)) saved.set(key,id);
                    }
                } catch {}
                return this._mobileRetryIds = saved;
            }
            mobileActive = new Set();
            mobileAttachmentRequests = new Set();
            mobileRecord(key,id,keep) {
                const storageKey = "dsh.mobile.prompt-retry."+this.sessionId;
                const raw = localStorage.getItem(storageKey);
                let saved;
                try { saved = JSON.parse(raw); } catch { saved = null; }
                if (!saved || typeof saved !== "object" || Array.isArray(saved)) saved = {};

                for (const [oldKey,oldId] of Object.entries(saved)) {
                    if (oldId === id) delete saved[oldKey];
                }
                if (keep) saved[key] = id;
                localStorage.setItem(storageKey,JSON.stringify(saved));
                this._mobileRetryIds = undefined;
            }
            async mobileContentKey(content) {
                const bytes = new TextEncoder().encode(JSON.stringify(content));
                const hash = await crypto.subtle.digest("SHA-256",bytes);
                return Array.from(new Uint8Array(hash),b=>b.toString(16).padStart(2,"0")).join("");
            }
            async mobileChooseRequest(content, fresh = randomUUID()) {
                const hash = await this.mobileContentKey(content);
                this._mobileRetryIds = undefined;
                const matches = [...this.mobileRetryIds].filter(([key,id]) =>
                    (key === hash || key.startsWith(hash+":")) && !this.mobileActive.has(id) &&
                    !this.submissionSettlements.has(id));
                if (!matches.length) return fresh;
                const id = await this.mobileRetryChoice([...new Set(matches.map(row=>row[1]))], fresh);
                if (id !== fresh) this.mobileRetryIds.delete(hash);
                return id;
            }
            mobileRetryChoice(ids, fresh) {
                return new Promise(resolve => {
                    const dialog = document.createElement("dialog");
                    dialog.setAttribute("aria-label","上次发送尚未确认");
                    const title = document.createElement("p");
                    title.textContent = "相同内容有尚未确认的发送记录。";
                    dialog.append(title);
                    const choose = (label,id) => {
                        const button = document.createElement("button");
                        button.textContent = label;
                        button.onclick = () => { dialog.close(); dialog.remove(); resolve(id); };
                        dialog.append(button);
                    };
                    ids.forEach((id,index)=>choose("重试第 "+(index+1)+" 条",id));
                    choose("作为新消息发送",fresh);
                    dialog.addEventListener("cancel",event=>{event.preventDefault();dialog.remove();resolve(null);});
                    document.body.append(dialog);
                    dialog.showModal();
                });
            }
            async mobileRestorePending() {
                if (JSON.parse(localStorage.getItem("dsh.sessions.current") ?? "{}").sessionId !== this.sessionId) return;
                const rows = await mobileJournal(this.sessionId,"list");
                if (!rows.length || JSON.parse(localStorage.getItem("dsh.sessions.current") ?? "{}")?.sessionId !== this.sessionId) return;
                const dialog = document.createElement("dialog");
                dialog.setAttribute("aria-label","恢复待确认消息");
                const title = document.createElement("p");
                title.textContent = "上次离开时，这些消息还在等待确认。";
                dialog.append(title);
                for (const row of rows) {
                    const button = document.createElement("button");
                    const preview = row.content.find(part => part.type === "text")?.text || "附件";
                    button.textContent = "核对并重试：" + preview.slice(0,80);
                    button.dataset.whaleRecover = row.id;
                    button.onclick = async () => {
                        button.disabled = true;
                        try {
                            const result = await this.prompt(row.content,row.mode,undefined,row.id,true);
                            if (result.ok) button.remove();
                        } finally {
                            button.disabled = false;
                            if (!dialog.querySelector("[data-whale-recover]")) dialog.remove();
                        }
                    };
                    dialog.append(button);
                }
                const close = document.createElement("button");
                close.textContent = "稍后处理";
                close.onclick = () => dialog.remove();
                dialog.append(close);
                document.body.append(dialog);
                dialog.showModal();
            }
            async prompt(content, mode, signal, requestId, recovered = false) {
                const owned = requestId && this.submissionSettlements.has(requestId);
                requestId = owned || recovered ? requestId : await this.mobileChooseRequest(content, requestId);
                if (!requestId) return {ok:false};
                const key = (await this.mobileContentKey(content)) + ":" + requestId;
                const remember = () => this.mobileRecord(key,requestId,true);
                this.mobileRetryIds.set(key,requestId);
                // Persist before admitting anything to the Host. Storage errors fail closed.
                const abort = new AbortController();
                const cancel = () => abort.abort(signal.reason);
                signal?.addEventListener("abort",cancel,{once:true});
                if (signal?.aborted) cancel();
                let timer;
                try {
                    await mobileJournal(this.sessionId,"save",{id:requestId,content,mode});
                    remember();
                    this.mobileActive.add(requestId);
                    const timeout = new Promise((_,reject) => {
                        timer = setTimeout(() => {
                            abort.abort();
                            reject(new Error("发送确认超时，消息已保留。重试会核对原请求。"));
                        },15000);
                    });
                    const result = await Promise.race([this.mobilePromptRequest(content,mode,abort.signal,requestId),timeout]);
                    if (result.ok && this.mobileObserved.has(requestId)) this.scheduleObservedRetirement(requestId,this.mobileObserved.get(requestId));
                    if (result.ok && !this.mobileAttachmentRequests.has(requestId)) {
                        // Host acknowledgement is authoritative even if local cleanup fails.
                        try {
                            await mobileJournal(this.sessionId,"delete",{id:requestId});
                            this.mobileRecord(key,requestId,false);
                        } catch (error) {
                            console.warn("Pending message cleanup unavailable",error);
                        }
                        this.mobileObserved.delete(requestId);
                    }
                    return result;
                } catch (error) {
                    // An absent acknowledgement cannot prove that the Host rejected the request.
                    this.mobileRetryIds.set(key,requestId);

                    this.retireFailedSubmission(requestId);
                    this.firstPromptPendingTurn = false;
                    const failure = new RemoteError("gateway/connection-lost",
                        error instanceof Error ? error.message : "发送连接中断，消息已保留，请重试。",{});
                    this.promptError = {op:"send",error:failure};
                    this.notifier.markDirty();
                    return {ok:false,error:failure};
                } finally {
                    this.mobileActive.delete(requestId);
                    clearTimeout(timer);
                    signal?.removeEventListener("abort",cancel);
                }
            }
            async mobilePromptRequest(content, mode, signal, requestId) {"""
    text = replace_once(text, 'beginSubmission(input) {\n\t\t\t\tconst requestId = randomUUID();',
        'beginSubmission(input) {\n\t\t\t\tconst requestId = input.requestId ?? randomUUID();')
    text = replace_once(text,"this.promptAttempted = true;\n\t\t\t\tthis.notifier.markDirty();\n\t\t\t\treturn {\n\t\t\t\t\trequestId,",
        "const owner = this.submissionSettlements.get(requestId);\n\t\t\t\tthis.promptAttempted = true;\n\t\t\t\tthis.notifier.markDirty();\n\t\t\t\treturn {\n\t\t\t\t\trequestId,")
    text = replace_once(text,"abandon: () => {\n\t\t\t\t\t\tthis.retireFailedSubmission(requestId);",
        'abandon: () => {\n                        if (this.submissionSettlements.get(requestId) === owner) this.finishSubmission(requestId,{reason:"failed"});')
    text = replace_once(text,"scheduleFrame(() => {\n\t\t\t\t\tthis.finishSubmission(requestId,", "scheduleFrame(() => {\n                    if (this.submissionSettlements.get(requestId) !== settlement) return;\n\t\t\t\t\tthis.finishSubmission(requestId,")
    text = replace_once(text,anchor,replacement)
    text = replace_once(text,"\t\t\t\tif (!result.ok) {\n\t\t\t\t\tif (requestId !== void 0) this.retireFailedSubmission(requestId);",
        "\t\t\t\tsignal?.throwIfAborted();\n\t\t\t\tif (!result.ok) {\n\t\t\t\t\tthis.firstPromptPendingTurn = false;\n\t\t\t\t\tif (requestId !== void 0) this.retireFailedSubmission(requestId);")
    text = replace_once(text, "this.submissionSettlements.delete(requestId);\n\t\t\t\tthis.pendingSubmissions = this.pendingSubmissions.filter",
        """this.submissionSettlements.delete(requestId);
                if (retirement.reason === "observed" && !this.mobileActive.has(requestId)) {
                    this.mobileObserved.delete(requestId);
                    mobileJournal(this.sessionId,"delete",{id:requestId}).catch(()=>{});
                    try { this.mobileRecord("",requestId,false); } catch {}
                }
                this.mobileAttachmentRequests.delete(requestId);
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
    return Path(__file__).with_name("mobile-prompt-journal.js").read_text(encoding="utf-8") + "\n" + text

def patch_conversation(text):
    text = replace_once(text, "shell.setDraft(readConversationDraft(binding.sessionId));",
        "shell.setDraft(readConversationDraft(binding.sessionId));\n                session.mobileRestorePending().catch(error => console.warn(\"Pending message recovery unavailable\",error));")
    start = text.index("const submission = session.beginSubmission({")
    end = text.index("if (!(await session.prompt",start)
    block = text[start:end]
    split = block.index("let content;")
    create = block[:split].replace("session.beginSubmission({","session.beginSubmission({\n                    requestId,")
    encode = block[split:].replace("submission.abandon();","")
    text = text[:start] + encode + """
                const requestId = await session.mobileChooseRequest(content);
                if (!requestId) return {kind:"error"};
                if (attachments.length) session.mobileAttachmentRequests.add(requestId);
                """ + create + text[end:]
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

def patch_host(text):
    # Claim removes a row from the inbox before the model transcript is appended.
    # Its durable insertion is the admission receipt throughout this interval.
    text = replace_once(text, 'if (event.type !== "user/message") return false;',
        'if (event.type === "agent/inbox/spliced") return event.data.inserted.some(matches);\n        if (event.type !== "user/message") return false;')
    anchor = "const binding = __addDisposableResource$3(env_2, this.ctx.fileUploads.bindPrompt"
    return replace_once(text,anchor,"if (hasPromptRequest(agent, request.requestId)) return {accepted:true};\n                    "+anchor)

def replacements(members):
    result = {}
    for name, content in members.items():
        if name.endswith("/dsh-api-session-controller/lib/index.js"):
            result[name] = patch_host(content.decode()).encode()
        if name.endswith("/dsh-agent-loop/lib/index.js"):
            result[name] = patch_agent(content.decode()).encode()
        if name.endswith("/dsh-api-session-controller/lib/client.js"):
            result[name] = patch_client(content.decode()).encode()
        if name.endswith("/dsh-client-ui-conversation/lib/client.js"):
            result[name] = patch_conversation(content.decode()).encode()
        if name.endswith("/dsh-client-connection/lib/client.browser.js"):
            result[name] = patch_connection(content.decode()).encode()
    if len(result) != 5:
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
