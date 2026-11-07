"""Extract the actual bundled methods for isolated fault injection."""
import runpy
import sys
import tarfile
from pathlib import Path
patches = runpy.run_path(str(Path(__file__).with_name("main-queue-patches.py")))
out = Path(sys.argv[2])
with tarfile.open(sys.argv[1], "r|gz") as archive:
    for member in archive:
        if not member.isfile():
            continue
        if member.name.endswith("/dsh-agent-loop/lib/index.js"):
            source = archive.extractfile(member).read().decode()
            if "const head = this.inbox.nextTurn[0]" not in source:
                source = patches["patch_agent"](source)
            start = source.index("\tasync kick() {")
            end = source.index("\n\tasync preStep(",start)
            (out / "kick.txt").write_text(source[start:end],encoding="utf-8")
        if member.name.endswith("/dsh-api-session-controller/lib/client.js"):
            source = archive.extractfile(member).read().decode()
            if "async mobilePromptRequest(" not in source:
                source = patches["patch_client"](source)
            start = source.index("_mobileRetryIds;") if "_mobileRetryIds;" in source else source.index("mobileRetryIds = new Map()")
            end = source.index("\n\t\t\t/**\n\t\t\t* Resolve one image",start)
            (out / "prompt.txt").write_text(source[start:end],encoding="utf-8")
        if member.name.endswith("/dsh-client-ui-conversation/lib/client.js"):
            source = archive.extractfile(member).read().decode()
            if "发送已获确认，但对话同步中断" not in source:
                source = patches["patch_conversation"](source)
            start = source.index("async sendSession(session, text, attachmentIds, mode, signal)")
            end = source.index("\n\t\t\t/**",start)
            (out / "send-session.txt").write_text(source[start:end],encoding="utf-8")
        if member.name.endswith("/dsh-client-connection/lib/client.browser.js"):
            source = archive.extractfile(member).read().decode()
            if "Promise.race([failed,waitForAbort(ac.signal)])" not in source:
                source = patches["patch_connection"](source)
            start = source.index("var ConnectionController = class")
            end = source.index("\n\t\tfunction waitForReady(",start)
            (out / "connection.txt").write_text(source[start:end],encoding="utf-8")
            start = source.index("function installConnection(ctx, options = {})")
            end = source.index("\n\t\tfunction apply(ctx)",start)
            (out / "install-connection.txt").write_text(source[start:end],encoding="utf-8")
