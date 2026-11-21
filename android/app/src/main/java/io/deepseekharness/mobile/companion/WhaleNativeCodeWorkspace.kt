package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 100% 纯原生 Kotlin 本地代码工程工作区（零 PRoot、零 Bash、零 Node 依赖）。
 *
 * 允许 Whale Native Agent 在受控目录（app filesDir/whale-projects）内创建与枚举项目、
 * 读写多文件源码、精准片段替换（patchFile）、安全删除单文件以及跨文件搜索；
 * 严格校验规范路径防止目录穿越。
 */
internal class WhaleNativeCodeWorkspace(private val rootDir: File) {
    init {
        rootDir.mkdirs()
    }

    private val canonicalRoot: File by lazy { rootDir.canonicalFile }

    private fun resolveSafe(project: String, relativePath: String = ""): File {
        val cleanProject = project.trim()
        require(cleanProject.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "项目名称仅支持字母、数字、点、横线或下划线" }
        val projectDir = File(canonicalRoot, cleanProject)
        val target = if (relativePath.isBlank()) projectDir else File(projectDir, relativePath.trimStart('/'))
        val canonicalTarget = target.canonicalFile
        val rootPath = canonicalRoot.path
        require(canonicalTarget.path == rootPath || canonicalTarget.path.startsWith(rootPath + File.separator)) {
            "禁止访问代码工作区以外的路径"
        }
        return canonicalTarget
    }

    fun listProjects(): JSONObject {
        val projects = JSONArray()
        canonicalRoot.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedByDescending { it.lastModified() }
            .take(MAX_PROJECTS)
            .forEach { dir ->
                var fileCount = 0
                var totalBytes = 0L
                for (f in dir.walkTopDown().maxDepth(8)) {
                    if (f.isFile) {
                        fileCount += 1
                        totalBytes += f.length()
                    }
                }
                projects.put(
                    JSONObject()
                        .put("name", dir.name)
                        .put("files", fileCount)
                        .put("bytes", totalBytes)
                        .put("updatedAt", dir.lastModified()),
                )
            }
        return JSONObject().put("projects", projects)
    }

    fun listFiles(project: String, subPath: String = ""): JSONObject {
        val dir = resolveSafe(project, subPath)
        val items = JSONArray()
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles().orEmpty()
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                .take(MAX_LIST_ITEMS)
                .forEach { file ->
                    items.put(
                        JSONObject()
                            .put("name", file.name)
                            .put("type", if (file.isDirectory) "dir" else "file")
                            .put("bytes", if (file.isFile) file.length() else 0L),
                    )
                }
        }
        return JSONObject()
            .put("project", project)
            .put("path", subPath)
            .put("items", items)
    }

    fun readFile(project: String, path: String, startLine: Int = 1, endLine: Int = 200): JSONObject {
        require(path.isNotBlank()) { "请提供文件路径" }
        val file = resolveSafe(project, path)
        require(file.isFile) { "文件不存在：$path" }
        require(file.length() <= MAX_FILE_BYTES) { "文件超过 512 KiB 读取上限" }
        WhaleHarnessCore.FsObservationGate.recordObserved(file)
        val lines = file.readLines(Charsets.UTF_8)
        val from = startLine.coerceAtLeast(1)
        val to = endLine.coerceIn(from, (from + 300).coerceAtMost(lines.size.coerceAtLeast(from)))
        val slice = if (lines.isEmpty() || from > lines.size) {
            ""
        } else {
            lines.subList(from - 1, to.coerceAtMost(lines.size))
                .mapIndexed { idx, line -> "${from + idx}: $line" }
                .joinToString("\n")
        }
        return JSONObject()
            .put("project", project)
            .put("path", path)
            .put("totalLines", lines.size)
            .put("startLine", from)
            .put("endLine", to.coerceAtMost(lines.size))
            .put("content", slice)
    }

    fun writeFile(project: String, path: String, content: String): JSONObject {
        require(path.isNotBlank()) { "请提供文件路径" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "单文件写入上限为 512 KiB" }
        val file = resolveSafe(project, path)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.writeBytes(bytes)
            tmp.delete()
        }
        WhaleHarnessCore.FsObservationGate.recordObserved(file)
        return JSONObject()
            .put("ok", true)
            .put("project", project)
            .put("path", path)
            .put("bytes", bytes.size)
    }

    /** 精准替换源码中的唯一匹配片段，避免整文件重写造成截断或高额 token 消耗。 */
    fun patchFile(project: String, path: String, oldText: String, newText: String): JSONObject {
        require(path.isNotBlank()) { "请提供文件路径" }
        require(oldText.isNotEmpty()) { "请提供需要替换的原始代码片段" }
        val file = resolveSafe(project, path)
        require(file.isFile) { "文件不存在：$path" }
        WhaleHarnessCore.FsObservationGate.checkFreshForEdit(file, path)
        require(file.length() <= MAX_FILE_BYTES) { "文件超过 512 KiB 上限" }
        val current = file.readText(Charsets.UTF_8)
        val firstIdx = current.indexOf(oldText)
        require(firstIdx >= 0) { "未在文件中找到完全匹配的原始代码片段" }
        val secondIdx = current.indexOf(oldText, firstIdx + oldText.length)
        if (secondIdx >= 0) {
            val matchLines = ArrayList<Int>()
            var pos = firstIdx
            while (pos >= 0 && matchLines.size < 10) {
                val lineNo = current.substring(0, pos).count { it == '\n' } + 1
                matchLines.add(lineNo)
                pos = current.indexOf(oldText, pos + oldText.length)
            }
            throw IllegalArgumentException("原始代码片段在文件中出现多次（匹配行号：${matchLines.joinToString(", ")}），请补充更多上下文确保唯一匹配")
        }
        val updated = current.substring(0, firstIdx) + newText + current.substring(firstIdx + oldText.length)
        return writeFile(project, path, updated).put("patched", true)
    }

    fun deleteFile(project: String, path: String): JSONObject {
        require(path.isNotBlank()) { "请提供要删除的文件相对路径" }
        val projectDir = resolveSafe(project)
        val target = resolveSafe(project, path)
        require(target.path != projectDir.path && target.path.startsWith(projectDir.path + File.separator)) {
            "仅允许删除项目内部文件"
        }
        require(target.exists() && target.isFile) { "目标文件不存在或不是普通文件：$path" }
        WhaleHarnessCore.FsObservationGate.checkFreshForEdit(target, path)
        val deleted = target.delete()
        if (deleted) WhaleHarnessCore.FsObservationGate.forget(target)
        return JSONObject()
            .put("ok", deleted)
            .put("project", project)
            .put("path", path)
    }

    fun searchCode(project: String, query: String, maxMatches: Int = 40): JSONObject {
        val needle = query.trim()
        require(needle.isNotEmpty() && needle.length <= 120) { "搜索词长度无效" }
        val base = resolveSafe(project)
        val matches = JSONArray()
        if (base.isDirectory) {
            val bound = maxMatches.coerceIn(1, 80)
            for (file in base.walkTopDown().maxDepth(8)) {
                if (matches.length() >= bound) break
                if (!file.isFile || file.length() > MAX_FILE_BYTES) continue
                val rel = file.relativeTo(base).path.replace('\\', '/')
                val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: continue
                for ((idx, line) in lines.withIndex()) {
                    if (line.contains(needle, ignoreCase = true)) {
                        matches.put(
                            JSONObject()
                                .put("path", rel)
                                .put("line", idx + 1)
                                .put("text", line.trim().take(200)),
                        )
                        if (matches.length() >= bound) break
                    }
                }
            }
        }
        return JSONObject()
            .put("project", project)
            .put("query", needle)
            .put("matches", matches)
    }

    companion object {
        const val MAX_FILE_BYTES = 512 * 1024
        const val MAX_LIST_ITEMS = 200
        const val MAX_PROJECTS = 64
    }
}
