package io.deepseekharness.mobile.features

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One atomic document per feature. Only the application's library can change the active revision. */
internal class WhaleFeatureStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "whale-features-v1")
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,47}"))) { "功能标识无效" }
        return AtomicFile(File(root, "$id.json"))
    }
    fun read(id: String): JSONObject? = synchronized(lock) {
        try { JSONObject(file(id).openRead().bufferedReader().use { it.readText() }) }
        catch (_: java.io.FileNotFoundException) { null }
    }
    fun readAssigned(id: String): JSONObject? = synchronized(lock) {
        read(id)?.also { check(it.optString("creatorWorkerFeature") == id) { "工单标识与已有功能冲突" } }
    }
    fun list(): List<JSONObject> = synchronized(lock) {
        root.listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.bak") }
            .map { it.name.substringBefore('.') }.distinct().sorted().mapNotNull { read(it)?.let(::summary) }
    }
    data class Tool(val id: String, val title: String, val revision: String, val activatedAt: Long, val pinned: Boolean = false) {
        fun json() = JSONObject().put("id", id).put("title", title).put("revision", revision).put("pinned", pinned)
    }

    /** Cache only launch metadata. Listing tools never inflates a WebView or retains authored HTML. */
    fun activeTools(): List<Tool> = synchronized(lock) {
        if (cachedRoot == root && cachedTools != null) return@synchronized checkNotNull(cachedTools)
        list().mapNotNull { row ->
            val revision = row.getString("active").ifEmpty { return@mapNotNull null }
            Tool(row.getString("id"), row.getString("activeTitle"), revision, row.optLong("activatedAt"), row.optBoolean("pinned", false))
        }.sortedWith(compareByDescending<Tool> { it.pinned }.thenByDescending { it.activatedAt }.thenBy { it.title }.thenBy { it.id })
            .also { cachedRoot = root; cachedTools = it }
    }

    /** 置顶或取消置顶已启用工具，置顶项在功能工坊与桌宠菜单中优先展示。 */
    fun setPinned(id: String, pinned: Boolean): JSONObject = synchronized(lock) {
        val row = requireNotNull(read(id)) { "找不到该功能" }
        row.put("pinned", pinned)
        val target = file(id)
        val out = target.startWrite()
        try {
            out.write(row.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(out)
        } catch (error: Throwable) {
            target.failWrite(out)
            throw error
        }
        cachedTools = null
        summary(row)
    }

    fun <T> withActive(id: String, revision: String, action: (JSONObject) -> T): T = synchronized(lock) {
        val row = requireNotNull(read(id))
        check(revision.isNotEmpty() && row.optString("active") == revision) { "已启用版本有变化，请重新打开" }
        action(row.getJSONObject("versions").getJSONObject(revision))
    }

    private fun summary(row: JSONObject): JSONObject {
        val versions = row.getJSONObject("versions")
        val latest = row.getString("latest")
        return JSONObject().put("id", row.getString("id")).put("title", versions.getJSONObject(latest).getString("title"))
            .put("activeTitle", versions.optJSONObject(row.optString("active"))?.getString("title").orEmpty())
            .put("activatedAt", row.optLong("activatedAt")).put("pinned", row.optBoolean("pinned", false))
            .put("latest", latest).put("active", row.optString("active")).put("previous", row.optString("previous"))
    }
    fun draft(input: JSONObject, assignedId: String? = null): JSONObject = synchronized(lock) {
        val id = input.getString("id")
        val title = input.getString("title").trim()
        val html = input.getString("html").trim()
        require(title.isNotEmpty() && title.length <= 50 && html.isNotEmpty() && html.toByteArray().size <= 512 * 1024)
        // Reject double-encoded formatting, not legitimate escapes inside JavaScript or code examples.
        val literalWhitespace = Regex("(?:\\\\[nrt])+")
        val literalContainers = setOf("script","style","pre","code","textarea")
        val escapedFormatting = org.jsoup.Jsoup.parse(html).allElements.sumOf { element ->
            if (element.tagName() in literalContainers ||
                element.parents().any { it.tagName() in setOf("pre","code","textarea") }) 0
            else element.textNodes().count { it.wholeText.trim().matches(literalWhitespace) }
        }
        if (escapedFormatting >= 3) throw io.deepseekharness.mobile.runtime.RuntimeFailure("FEATURE_HTML_DOUBLE_ESCAPED",
            "HTML 中有多处重复转义的换行，请提交真实 HTML 源码；脚本内部需要的转义保留。")
        val hash = MessageDigest.getInstance("SHA-256").digest((title + "\u0000" + html).toByteArray()).joinToString("") { "%02x".format(it) }
        if (assignedId != null) require(assignedId == id)
        val current = if (assignedId != null) readAssigned(id) else read(id)
        if (assignedId == null && current == null) require(!id.matches(Regex("task-[a-f0-9]{8}-[a-f0-9-]{27}"))) { "此标识保留给后台创作工单" }
        if (current?.optString("latest") == hash) return@synchronized summary(current)
        require(current?.optString("latest").orEmpty() == input.optString("baseRevision")) { "功能版本已更新，请先读取当前版本" }
        require(current != null || list().size < 48) { "本机功能库已满" }
        val row = current ?: JSONObject().put("id", id).put("versions", JSONObject())
        if (assignedId != null) row.put("creatorWorkerFeature", assignedId)
        val versions = row.getJSONObject("versions")
        versions.put(hash, JSONObject().put("title", title).put("html", html))
        row.put("latest", hash)
        val keep = setOf(hash, row.optString("active"), row.optString("previous"))
        versions.keys().asSequence().toList().filterNot(keep::contains).forEach(versions::remove)
        save(row)
        summary(row)
    }
    fun publishPreview(id: String, revision: String, expectedActive: String): JSONObject = synchronized(lock) {
        check(read(id)?.optString("latest") == revision) { "草稿已更新" }
        activate(id, revision, expectedActive)
    }
    fun activate(id: String, target: String, expectedActive: String): JSONObject = synchronized(lock) {
        val row = requireNotNull(read(id))
        require(row.optString("active") == expectedActive && (target.isEmpty() ||
            target in setOf(row.getString("latest"), row.optString("previous")) && row.getJSONObject("versions").has(target))) { "功能版本已变化，请刷新" }
        if (target != expectedActive) {
            row.put("previous", expectedActive.ifEmpty { row.optString("previous") }).put("active", target)
            row.put("activatedAt", System.currentTimeMillis())
            save(row)
            listeners.forEach { listener -> runCatching { listener(id,target) } }
        }
        summary(row)
    }
    fun readHtml(id: String): JSONObject? = synchronized(lock) {
        val row = read(id) ?: return@synchronized null
        val versions = row.optJSONObject("versions") ?: return@synchronized null
        val rev = row.optString("latest").ifEmpty { row.optString("active") }
        val ver = versions.optJSONObject(rev) ?: return@synchronized null
        JSONObject()
            .put("id", row.getString("id"))
            .put("title", ver.optString("title"))
            .put("revision", rev)
            .put("active", row.optString("active"))
            .put("pinned", row.optBoolean("pinned", false))
            .put("html", ver.optString("html"))
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,47}"))) { "功能标识无效" }
        val mainFile = File(root, "$id.json")
        val bakFile = File(root, "$id.json.bak")
        val existed = mainFile.exists() || bakFile.exists()
        if (mainFile.exists()) mainFile.delete()
        if (bakFile.exists()) bakFile.delete()
        if (existed) {
            cachedTools = null
            listeners.forEach { listener -> runCatching { listener(id, "") } }
        }
        existed
    }

    private fun save(row: JSONObject) {
        check(root.isDirectory || root.mkdirs())
        val target = file(row.getString("id"))
        val out = target.startWrite()
        try { out.write(row.toString().toByteArray()); target.finishWrite(out); cachedTools = null }
        catch (e: Exception) { target.failWrite(out); throw e }
    }
    companion object {
        private val lock = Any()
        private var cachedRoot: File? = null
        private var cachedTools: List<Tool>? = null
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<(String,String) -> Unit>()
        fun subscribe(listener: (String,String) -> Unit) { listeners.add(listener) }
        fun unsubscribe(listener: (String,String) -> Unit) { listeners.remove(listener) }
    }
}
