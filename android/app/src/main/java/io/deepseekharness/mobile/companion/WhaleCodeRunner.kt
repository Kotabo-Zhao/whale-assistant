package io.deepseekharness.mobile.companion

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生代码执行引擎：
 * 1. 内嵌原生 CPython 3.11（Chaquopy）：无需 Linux / Ubuntu / 联网，毫秒级真机执行 Python 脚本并读写工作区文件；
 * 2. 支持 DeepSeek Harness 风格编程式工具调用（PTC）：Python 内可直接通过 `tools.<工具名>(...)` 编排多个原生工具并用 `inspect(...)` 返回精炼结果；
 * 3. 轻量本地 Shell（sh）：在项目沙箱目录内执行脚本或文件检查（30 秒超时与 16KB 输出上限）。
 */
internal object WhaleCodeRunner {
    /** 暴露给 Chaquopy Python 的工具回调接口（方法名 `call` 供 Python 直接调用）。 */
    fun interface ToolBridge {
        fun call(toolName: String, argsJson: String): String
    }

    private val runnerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "whale-code-runner").apply { isDaemon = true }
    }

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
        timeoutSec: Long = 30L,
        toolBridge: ToolBridge? = null,
    ): JSONObject {
        projectDir.mkdirs()
        val future = runnerPool.submit<JSONObject> {
            runCatching {
                val py = ensurePython(context)
                val mod = py.getModule("whale_runner")
                val raw = if (toolBridge != null) {
                    mod.callAttr("run_code", code, projectDir.absolutePath, toolBridge).toString()
                } else {
                    mod.callAttr("run_code", code, projectDir.absolutePath).toString()
                }
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
            future.get(timeoutSec.coerceIn(3L, 60L), TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            JSONObject()
                .put("ok", false)
                .put("error", "Python 执行超过 ${timeoutSec} 秒已自动中止，请精简循环或改用后台 Worker 分阶段运行。")
        }
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
            val finished = proc.waitFor(timeoutSec.coerceIn(3L, 45L), TimeUnit.SECONDS)
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
}
