package io.deepseekharness.mobile.companion

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生代码执行引擎：
 * 1. 内嵌原生 CPython 3.11（Chaquopy）：无需 Linux / Ubuntu / 联网，毫秒级真机执行 Python 脚本并读写工作区文件；
 * 2. 支持按 [sessionId] 复用的有状态 REPL 会话、自动挂载已安装纯 Python 包与 Skill scripts/ 目录，以及 PTC；
 * 3. 支持运行时从国内镜像安装纯 Python Wheel（[installPurePythonPackage]）与一键导出文件至手机下载目录/系统分享（[exportProjectFile]）；
 * 4. 轻量本地 Shell（sh）：在项目沙箱目录内执行脚本或文件检查（超时与 16KB 输出上限）。
 */
internal object WhaleCodeRunner {
    /** 暴露给 Chaquopy Python 的工具回调接口（方法名 `call` 供 Python 直接调用）。 */
    fun interface ToolBridge {
        fun call(toolName: String, argsJson: String): String
    }

    private val runnerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "whale-code-runner").apply { isDaemon = true }
    }

    fun sitePackagesDir(context: Context): File =
        File(context.applicationContext.filesDir, "whale-py-site").apply { mkdirs() }

    @Synchronized
    private fun ensurePython(context: Context): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    fun runPython(
        context: Context,
        projectDir: File,
        code: String,
        timeoutSec: Long = 45L,
        toolBridge: ToolBridge? = null,
        sessionId: String = "",
        resetSession: Boolean = false,
    ): JSONObject {
        projectDir.mkdirs()
        val app = context.applicationContext
        val extraPaths = JSONArray().apply {
            put(sitePackagesDir(app).absolutePath)
            WhaleSkillStore.skillScriptDirs(app).forEach { put(it) }
        }.toString()
        val future = runnerPool.submit<JSONObject> {
            runCatching {
                val py = ensurePython(app)
                val mod = py.getModule("whale_runner")
                val raw = mod.callAttr(
                    "run_code",
                    code,
                    projectDir.absolutePath,
                    toolBridge,
                    sessionId,
                    resetSession,
                    extraPaths,
                ).toString()
                JSONObject(raw)
            }.getOrElse { err ->
                JSONObject()
                    .put("ok", false)
                    .put("stdout", "")
                    .put("stderr", "")
                    .put("error", "Python 启动或执行异常：${err.message ?: err.javaClass.simpleName}")
            }
        }
        return try {
            future.get(timeoutSec.coerceIn(3L, 180L), TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            JSONObject()
                .put("ok", false)
                .put("error", "Python 执行超过 ${timeoutSec} 秒已自动中止，请精简循环或改用后台 Worker 分阶段运行。")
        }
    }

    /** 运行时从国内镜像（清华/阿里/PyPI）下载并解压纯 Python Wheel 到私有 site-packages。 */
    fun installPurePythonPackage(
        context: Context,
        packageName: String,
        mirrorUrl: String = "",
        timeoutSec: Long = 60L,
    ): JSONObject {
        val app = context.applicationContext
        val siteDir = sitePackagesDir(app)
        val future = runnerPool.submit<JSONObject> {
            runCatching {
                val py = ensurePython(app)
                val mod = py.getModule("whale_runner")
                val raw = mod.callAttr("install_pure_wheel", packageName, siteDir.absolutePath, mirrorUrl).toString()
                JSONObject(raw)
            }.getOrElse { err ->
                JSONObject().put("ok", false).put("error", "安装 Python 包异常：${err.message ?: err.javaClass.simpleName}")
            }
        }
        return try {
            future.get(timeoutSec.coerceIn(10L, 120L), TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            JSONObject().put("ok", false).put("error", "安装 $packageName 超时（>${timeoutSec}s），请检查网络或更换镜像源。")
        }
    }

    /** 将代码工作区生成的文件（如 .pptx/.docx/.xlsx/.pdf/.zip）导出至手机「下载/WhaleAssistant」并可选拉起系统分享。 */
    fun exportProjectFile(
        context: Context,
        project: String,
        relativePath: String,
        shareNow: Boolean = false,
    ): JSONObject {
        val app = context.applicationContext
        val safeProj = project.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(48).ifBlank { "scratch" }
        val projectsRoot = File(app.filesDir, "whale-projects").canonicalFile
        val projectDir = File(projectsRoot, safeProj).canonicalFile
        require(projectDir.path.startsWith(projectsRoot.path + File.separator)) { "项目路径越界" }
        val source = File(projectDir, relativePath.trimStart('/')).canonicalFile
        require(source.path.startsWith(projectDir.path + File.separator)) { "文件路径越界" }
        require(source.isFile) { "未找到待导出文件：$relativePath" }
        require(source.length() <= 50L * 1024L * 1024L) { "单个导出文件不能超过 50MB" }

        val fileName = source.name
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pdf" -> "application/pdf"
            "csv" -> "text/csv"
            "md" -> "text/markdown"
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        var savedLocation = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/WhaleAssistant")
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入系统下载目录")
            app.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法打开下载输出流")
            savedLocation = "Download/WhaleAssistant/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val dlDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WhaleAssistant").apply { mkdirs() }
            val dest = File(dlDir, fileName)
            source.copyTo(dest, overwrite = true)
            savedLocation = dest.absolutePath
        }

        val shareDir = File(app.cacheDir, "share").apply { mkdirs() }
        val shareCopy = File(shareDir, fileName)
        runCatching { source.copyTo(shareCopy, overwrite = true) }

        var shared = false
        if (shareNow) {
            runCatching {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", shareCopy)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, "分享或打开 $fileName").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(chooser)
                shared = true
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("fileName", fileName)
            .put("bytes", source.length())
            .put("mimeType", mime)
            .put("savedTo", savedLocation)
            .put("shared", shared)
    }

    fun runShell(
        projectDir: File,
        command: String,
        timeoutSec: Long = 20L,
    ): JSONObject {
        val clean = command.trim()
        if (clean.isEmpty()) return JSONObject().put("ok", false).put("error", "缺少要执行的 Shell 命令")
        val blocked = listOf("rm -rf /", "mkfs", "dd if=", "reboot", "su -c", "chmod 777 /")
        if (blocked.any { clean.contains(it, ignoreCase = true) }) {
            return JSONObject().put("ok", false).put("error", "命令触发安全拦截，禁止执行高危系统命令。")
        }
        projectDir.mkdirs()
        return runCatching {
            val proc = ProcessBuilder("sh", "-c", clean)
                .directory(projectDir)
                .redirectErrorStream(false)
                .start()
            val finished = proc.waitFor(timeoutSec.coerceIn(3L, 120L), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return JSONObject().put("ok", false).put("error", "Shell 命令超时（>${timeoutSec}s）已中止。")
            }
            val stdout = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(12_000)
            val stderr = proc.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(4_000)
            val exitCode = proc.exitValue()
            JSONObject()
                .put("ok", exitCode == 0)
                .put("exitCode", exitCode)
                .put("stdout", stdout)
                .put("stderr", stderr)
        }.getOrElse { err ->
            JSONObject().put("ok", false).put("error", "Shell 执行失败：${err.message ?: err.javaClass.simpleName}")
        }
    }

    /** 将对话中用户选择的文档（PDF/DOCX/XLSX/PPTX/TXT/代码）落盘到工作区 uploads 目录，供 Python 与代码工具直接读取。 */
    fun saveUploadedChatFile(context: Context, fileName: String, base64Data: String): JSONObject {
        val app = context.applicationContext
        val rawBytes = android.util.Base64.decode(base64Data.substringAfter(","), android.util.Base64.DEFAULT)
        require(rawBytes.isNotEmpty()) { "上传文件内容为空" }
        require(rawBytes.size <= 15 * 1024 * 1024) { "单个文档附件不能超过 15MB" }
        val cleanName = fileName.trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .take(80)
            .ifBlank { "upload_${System.currentTimeMillis()}.bin" }
        val projectsRoot = File(app.filesDir, "whale-projects").canonicalFile
        val uploadsDir = File(projectsRoot, "uploads").apply { mkdirs() }
        val target = File(uploadsDir, cleanName).canonicalFile
        require(target.path.startsWith(uploadsDir.canonicalPath + File.separator)) { "文件路径越界" }
        target.writeBytes(rawBytes)
        return JSONObject()
            .put("ok", true)
            .put("project", "uploads")
            .put("path", cleanName)
            .put("fileName", cleanName)
            .put("absolutePath", target.absolutePath)
            .put("bytes", rawBytes.size)
    }

    /** 打开或分享已导出的产出文件（优先读取 cacheDir/share 副本，其次检索工作区与下载目录）。 */
    fun openOrShareExportedFile(context: Context, fileName: String, action: String = "open"): JSONObject {
        val app = context.applicationContext
        val cleanName = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        require(cleanName.isNotEmpty()) { "缺少文件名" }
        val shareDir = File(app.cacheDir, "share").apply { mkdirs() }
        var target = File(shareDir, cleanName)
        if (!target.isFile) {
            val projectsRoot = File(app.filesDir, "whale-projects")
            val found = projectsRoot.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name == cleanName }
            if (found != null) {
                found.copyTo(target, overwrite = true)
            }
        }
        if (!target.isFile) {
            @Suppress("DEPRECATION")
            val dlFile = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WhaleAssistant"), cleanName)
            if (dlFile.isFile) {
                dlFile.copyTo(target, overwrite = true)
            }
        }
        require(target.isFile) { "未找到文件：$cleanName" }
        val ext = cleanName.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pdf" -> "application/pdf"
            "csv" -> "text/csv"
            "md" -> "text/markdown"
            "zip" -> "application/zip"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", target)
        val intent = if (action == "share") {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val chooser = Intent.createChooser(intent, if (action == "share") "分享 $cleanName" else "打开 $cleanName").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(chooser)
        return JSONObject().put("ok", true).put("fileName", cleanName).put("action", action)
    }

}
