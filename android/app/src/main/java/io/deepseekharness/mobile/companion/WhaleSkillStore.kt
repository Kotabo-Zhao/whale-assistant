package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whale 原生 Skill 引擎：支持标准 SKILL.md（YAML frontmatter + Markdown 指令）。
 *
 * Agent 可以自主：
 * - 从 HTTPS 直链或 GitHub 仓库/文件链接下载安装 SKILL.md；
 * - 自己撰写并持久化本地 Skill；
 * - 列出、启用/停用、读取与删除已安装 Skill；
 * - 每轮对话自动把已启用 Skill 目录摘要注入上下文，命中任务时先读规范再执行。
 */
internal object WhaleSkillStore {
    private const val DIR_NAME = "whale-skills"
    private const val MAX_SKILL_BYTES = 128 * 1024
    private const val MAX_SKILLS = 64

    data class ParsedSkill(
        val slug: String,
        val name: String,
        val description: String,
        val rawMarkdown: String,
    )

    private fun rootDir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    fun slugify(raw: String): String {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9_\\-\u4e00-\u9fa5]+"), "-")
            .trim('-')
            .take(64)
        return cleaned.ifEmpty { "skill-${System.currentTimeMillis() % 100000}" }
    }

    /** 将 GitHub 仓库/blob/tree 链接自动解析为候选 raw SKILL.md 下载地址。 */
    fun resolveCandidateUrls(rawUrl: String): List<String> {
        val trimmed = rawUrl.trim()
        require(trimmed.startsWith("https://")) { "Skill 地址必须使用 https://" }
        val candidates = LinkedHashSet<String>()
        val ghBlob = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$").find(trimmed)
        if (ghBlob != null) {
            val (owner, repo, branch, path) = ghBlob.destructured
            candidates.add("https://raw.githubusercontent.com/$owner/$repo/$branch/$path")
        }
        val ghTree = Regex("^https://github\\.com/([^/]+)/([^/]+)/tree/([^/]+)/(.+?)/?$").find(trimmed)
        if (ghTree != null) {
            val (owner, repo, branch, path) = ghTree.destructured
            val base = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
            candidates.add(if (path.endsWith(".md", ignoreCase = true)) base else "$base/SKILL.md")
        }
        val ghRepo = Regex("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$").find(trimmed)
        if (ghRepo != null) {
            val (owner, repo) = ghRepo.destructured
            candidates.add("https://raw.githubusercontent.com/$owner/$repo/main/SKILL.md")
            candidates.add("https://raw.githubusercontent.com/$owner/$repo/master/SKILL.md")
            candidates.add("https://raw.githubusercontent.com/$owner/$repo/main/skill.md")
        }
        if (candidates.isEmpty()) {
            candidates.add(trimmed)
        }
        return candidates.toList()
    }

    /** 解析标准 SKILL.md（YAML frontmatter name/description + Markdown 正文）。 */
    fun parseSkillMarkdown(raw: String, fallbackName: String = ""): ParsedSkill {
        val text = raw.replace("\r\n", "\n").trim()
        require(text.isNotEmpty()) { "Skill 内容不能为空" }
        var name = fallbackName.trim()
        var description = ""
        if (text.startsWith("---\n")) {
            val end = text.indexOf("\n---", startIndex = 4)
            if (end > 4) {
                val fm = text.substring(4, end)
                for (line in fm.lines()) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim().trim('"', '\'')
                    if (key == "name" && value.isNotEmpty()) name = value
                    if (key == "description" && value.isNotEmpty()) description = value
                }
            }
        }
        if (name.isEmpty()) {
            val heading = text.lines().firstOrNull { it.trimStart().startsWith("# ") }?.trimStart('#', ' ')?.trim()
            name = heading?.take(48)?.ifEmpty { null } ?: "custom-skill"
        }
        if (description.isEmpty()) {
            description = text.lines()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("---") && !it.startsWith("#") }
                ?.take(120)
                ?: "自定义技能"
        }
        return ParsedSkill(
            slug = slugify(name),
            name = name.take(64),
            description = description.take(240),
            rawMarkdown = text,
        )
    }

    fun installFromUrl(context: Context, rawUrl: String, customName: String = ""): JSONObject {
        val candidates = resolveCandidateUrls(rawUrl)
        var downloaded: String? = null
        var resolvedUrl = rawUrl.trim()
        var lastErr = "无法下载 Skill"
        for (url in candidates) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "WhaleAssistant/0.4 (Android Native Skill Installer)")
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        require(bytes.size <= MAX_SKILL_BYTES) { "Skill 文件过大（上限 128KB）" }
                        val body = String(bytes, Charsets.UTF_8).trim()
                        if (body.isNotEmpty() && !body.startsWith("<!DOCTYPE html", ignoreCase = true)) {
                            downloaded = body
                            resolvedUrl = url
                            break
                        }
                    } else {
                        lastErr = "HTTP $code ($url)"
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (err: Throwable) {
                lastErr = err.message ?: err.javaClass.simpleName
            }
        }
        val body = downloaded ?: throw IllegalStateException("安装 Skill 失败：$lastErr")
        val fallback = customName.ifBlank {
            resolvedUrl.substringBeforeLast("/SKILL.md", resolvedUrl)
                .substringAfterLast('/')
                .removeSuffix(".md")
        }
        val parsed = parseSkillMarkdown(body, fallback)
        return saveParsed(context, parsed, resolvedUrl)
    }

    fun saveLocal(
        context: Context,
        name: String,
        description: String,
        content: String,
        sourceUrl: String = "",
    ): JSONObject {
        val trimmedContent = content.trim()
        require(trimmedContent.isNotEmpty()) { "Skill 内容不能为空" }
        val normalized = if (trimmedContent.startsWith("---\n")) {
            trimmedContent
        } else {
            val safeName = name.trim().ifEmpty { "custom-skill" }
            val safeDesc = description.trim().ifEmpty { "自定义技能" }
            "---\nname: $safeName\ndescription: $safeDesc\n---\n\n$trimmedContent"
        }
        require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_SKILL_BYTES) { "Skill 内容超过 128KB 上限" }
        val parsed = parseSkillMarkdown(normalized, name)
        return saveParsed(context, parsed, sourceUrl)
    }

    private fun saveParsed(context: Context, parsed: ParsedSkill, sourceUrl: String): JSONObject {
        val root = rootDir(context)
        val existingDirs = root.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        val skillDir = File(root, parsed.slug)
        if (!skillDir.exists() && existingDirs.size >= MAX_SKILLS) {
            throw IllegalStateException("已安装 Skill 数量达到上限（$MAX_SKILLS），请先删除不用的 Skill")
        }
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText(parsed.rawMarkdown, Charsets.UTF_8)
        val meta = JSONObject()
            .put("slug", parsed.slug)
            .put("name", parsed.name)
            .put("description", parsed.description)
            .put("sourceUrl", sourceUrl)
            .put("enabled", true)
            .put("installedAt", System.currentTimeMillis())
        File(skillDir, "meta.json").writeText(meta.toString(), Charsets.UTF_8)
        return JSONObject()
            .put("ok", true)
            .put("slug", parsed.slug)
            .put("name", parsed.name)
            .put("description", parsed.description)
            .put("sourceUrl", sourceUrl)
            .put("chars", parsed.rawMarkdown.length)
    }

    fun list(context: Context): JSONArray {
        val out = JSONArray()
        val root = rootDir(context)
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        for (dir in dirs) {
            val mdFile = File(dir, "SKILL.md")
            if (!mdFile.isFile) continue
            val metaFile = File(dir, "meta.json")
            val meta = runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrNull()
            val name = meta?.optString("name")?.takeIf(String::isNotBlank) ?: dir.name
            val desc = meta?.optString("description").orEmpty()
            val enabled = meta?.optBoolean("enabled", true) ?: true
            val sourceUrl = meta?.optString("sourceUrl").orEmpty()
            out.put(
                JSONObject()
                    .put("slug", dir.name)
                    .put("name", name)
                    .put("description", desc)
                    .put("enabled", enabled)
                    .put("sourceUrl", sourceUrl)
                    .put("chars", mdFile.length()),
            )
        }
        return out
    }

    fun read(context: Context, queryName: String): JSONObject {
        val dir = findSkillDir(context, queryName)
            ?: throw IllegalArgumentException("未找到 Skill：$queryName")
        val md = File(dir, "SKILL.md").readText(Charsets.UTF_8)
        val meta = runCatching { JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8)) }.getOrNull()
        return JSONObject()
            .put("slug", dir.name)
            .put("name", meta?.optString("name")?.takeIf(String::isNotBlank) ?: dir.name)
            .put("description", meta?.optString("description").orEmpty())
            .put("enabled", meta?.optBoolean("enabled", true) ?: true)
            .put("content", md.take(16_000))
    }

    fun setEnabled(context: Context, queryName: String, enabled: Boolean): JSONObject {
        val dir = findSkillDir(context, queryName)
            ?: throw IllegalArgumentException("未找到 Skill：$queryName")
        val metaFile = File(dir, "meta.json")
        val meta = runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrElse { JSONObject().put("slug", dir.name).put("name", dir.name) }
        meta.put("enabled", enabled)
        metaFile.writeText(meta.toString(), Charsets.UTF_8)
        return JSONObject().put("ok", true).put("slug", dir.name).put("enabled", enabled)
    }

    fun delete(context: Context, queryName: String): JSONObject {
        val dir = findSkillDir(context, queryName)
            ?: return JSONObject().put("removed", false).put("error", "未找到 Skill：$queryName")
        val ok = dir.deleteRecursively()
        return JSONObject().put("removed", ok).put("slug", dir.name)
    }

    fun hasEnabledSkills(context: Context): Boolean {
        val root = File(context.applicationContext.filesDir, DIR_NAME)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return false
        for (dir in dirs) {
            if (!File(dir, "SKILL.md").isFile) continue
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8)) }.getOrNull()
            if (meta?.optBoolean("enabled", true) != false) return true
        }
        return false
    }

    fun catalogPrompt(context: Context): String {
        val items = list(context)
        val lines = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val s = items.optJSONObject(i) ?: continue
            if (!s.optBoolean("enabled", true)) continue
            val name = s.optString("name")
            val desc = s.optString("description").take(100)
            lines.add("- $name：$desc")
            if (lines.size >= 16) break
        }
        if (lines.isEmpty()) return ""
        return "[已安装 Skills（命中任务时先调用 whale_skill_read 读取完整规范再执行）]\n" + lines.joinToString("\n")
    }

    private fun findSkillDir(context: Context, queryName: String): File? {
        val root = rootDir(context)
        val q = queryName.trim()
        if (q.isEmpty()) return null
        val direct = File(root, slugify(q))
        if (direct.isDirectory && File(direct, "SKILL.md").isFile) return direct
        val dirs = root.listFiles { f -> f.isDirectory }.orEmpty()
        return dirs.firstOrNull { dir ->
            if (!File(dir, "SKILL.md").isFile) return@firstOrNull false
            val meta = runCatching { JSONObject(File(dir, "meta.json").readText(Charsets.UTF_8)) }.getOrNull()
            dir.name.equals(q, ignoreCase = true) || meta?.optString("name")?.equals(q, ignoreCase = true) == true
        }
    }
}
