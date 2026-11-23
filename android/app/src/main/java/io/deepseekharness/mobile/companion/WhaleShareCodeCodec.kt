package io.deepseekharness.mobile.companion

import android.content.Context
import java.util.Base64
import io.deepseekharness.mobile.features.WhaleFeatureStore
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 社区一键分享口令码编解码器（`WHALE1:<GZIP+Base64>`）。
 *
 * 支持将工坊小软件（workshop）、自造工具（custom_tool）、角色人设卡（persona）或 0-Token 录制宏（macro）
 * 打包为可复制转发的紧凑口令码，并可一键解析导入本机。
 */
internal object WhaleShareCodeCodec {
    private const val PREFIX = "WHALE1:"

    internal fun encodePacket(kind: String, payload: JSONObject): String {
        val envelope = JSONObject()
            .put("v", 1)
            .put("kind", kind)
            .put("payload", payload)
        val gz = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }
            baos.toByteArray()
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(gz)
    }

    internal fun decodePacket(code: String): Pair<String, JSONObject> {
        val clean = code.trim()
        require(clean.startsWith(PREFIX)) { "口令码格式无效（需以 $PREFIX 开头）" }
        val raw = Base64.getUrlDecoder().decode(clean.removePrefix(PREFIX))
        val jsonStr = GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes().toString(Charsets.UTF_8) }
        val env = JSONObject(jsonStr)
        val kind = env.optString("kind").trim()
        val payload = env.optJSONObject("payload") ?: throw IllegalArgumentException("口令码缺少 payload")
        require(kind.isNotEmpty()) { "口令码缺少类型标识" }
        return kind to payload
    }

    fun exportCode(context: Context, kind: String, idOrName: String): JSONObject {
        val app = context.applicationContext
        val cleanKind = kind.trim().lowercase()
        val key = idOrName.trim()
        require(key.isNotEmpty()) { "请提供要导出的对象名称或 ID" }
        val payload = when (cleanKind) {
            "custom_tool" -> {
                WhaleCustomToolEngine.findTool(WhaleCustomToolEngine.storeFile(app), key)
                    ?: throw IllegalArgumentException("未找到自造工具：$key")
            }
            "workshop" -> {
                val store = WhaleFeatureStore(app)
                val row = store.read(key) ?: throw IllegalArgumentException("未找到工坊小软件：$key")
                val rev = row.optString("active").ifBlank { row.optString("latest") }
                val ver = row.optJSONObject("versions")?.optJSONObject(rev)
                    ?: throw IllegalArgumentException("工坊小软件缺少有效版本：$key")
                JSONObject()
                    .put("id", row.optString("id", key))
                    .put("title", ver.optString("title", key))
                    .put("html", ver.optString("html", ""))
            }
            "macro" -> {
                val m = WhaleMacroEngine.list(app).firstOrNull { it.id == key || it.name.equals(key, ignoreCase = true) }
                    ?: throw IllegalArgumentException("未找到录制宏：$key")
                m.json()
            }
            else -> throw IllegalArgumentException("暂不支持导出类型：$cleanKind（支持 custom_tool / workshop / macro）")
        }
        val code = encodePacket(cleanKind, payload)
        return JSONObject()
            .put("ok", true)
            .put("kind", cleanKind)
            .put("idOrName", key)
            .put("code", code)
            .put("length", code.length)
    }

    fun importCode(context: Context, code: String): JSONObject {
        val app = context.applicationContext
        val (kind, payload) = decodePacket(code)
        return when (kind) {
            "custom_tool" -> {
                val name = payload.optString("name").trim()
                val desc = payload.optString("description").trim()
                val engine = payload.optString("kind").trim()
                val params = payload.optJSONObject("params") ?: JSONObject()
                val def = payload.optJSONObject("definition") ?: JSONObject()
                WhaleCustomToolEngine.createOrUpdate(WhaleCustomToolEngine.storeFile(app), name, desc, engine, params, emptyList(), def)
                JSONObject().put("ok", true).put("importedKind", "custom_tool").put("name", name)
            }
            "workshop" -> {
                val id = payload.optString("id").trim()
                val title = payload.optString("title").trim()
                val html = payload.optString("html")
                val store = WhaleFeatureStore(app)
                val prevActive = store.read(id)?.optString("active").orEmpty()
                val drafted = store.draft(JSONObject().put("id", id).put("title", title).put("html", html))
                val rev = drafted.optString("latest")
                if (rev.isNotBlank()) store.activate(id, rev, prevActive)
                JSONObject().put("ok", true).put("importedKind", "workshop").put("id", id).put("title", title)
            }
            else -> throw IllegalArgumentException("暂不支持导入类型：$kind")
        }
    }
}
