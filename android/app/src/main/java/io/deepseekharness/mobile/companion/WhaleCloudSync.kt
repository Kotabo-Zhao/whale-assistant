package io.deepseekharness.mobile.companion

import android.content.Context
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端 AES-256-GCM 加密 WebDAV 云同步引擎（零云端明文）。
 *
 * 将用户自造 `custom_*` 工具、0-Token 录制宏、主动守护规则、长期目标、习惯 Playbook 与角色卡
 * GZIP 压缩并用口令派生的 AES-256-GCM 密文同步至任意标准 WebDAV（如坚果云 / Nextcloud / 群晖）。
 */
internal object WhaleCloudSync {
    private const val PREFS = "whale_cloud_sync_v1"
    private const val MAGIC = "WHALE_E2EE_V1:"

    fun configure(context: Context, webdavUrl: String, username: String, password: String): JSONObject {
        val cleanUrl = webdavUrl.trim().trimEnd('/')
        require(cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://")) { "WebDAV 地址必须以 https:// 或 http:// 开头" }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", cleanUrl)
            .putString("user", username.trim())
            .putString("pass", password)
            .apply()
        return JSONObject().put("ok", true).put("url", cleanUrl).put("configured", true)
    }

    fun status(context: Context): JSONObject {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = p.getString("url", "").orEmpty()
        return JSONObject()
            .put("ok", true)
            .put("configured", url.isNotBlank())
            .put("url", url)
            .put("lastSyncAt", p.getLong("last_sync_at", 0L))
            .put("lastAction", p.getString("last_action", "").orEmpty())
    }

    internal fun encryptPayload(plainJson: String, passphrase: String): String {
        require(passphrase.length >= 6) { "端到端加密口令至少需要 6 位字符" }
        val gz = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { it.write(plainJson.toByteArray(Charsets.UTF_8)) }
            baos.toByteArray()
        }
        val rng = SecureRandom()
        val salt = ByteArray(16).also(rng::nextBytes)
        val iv = ByteArray(12).also(rng::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(gz)
        val envelope = JSONObject()
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("ct", Base64.getEncoder().encodeToString(ct))
        return MAGIC + Base64.getEncoder().encodeToString(envelope.toString().toByteArray(Charsets.UTF_8))
    }

    internal fun decryptPayload(blob: String, passphrase: String): String {
        require(blob.startsWith(MAGIC)) { "备份密文格式无效" }
        val raw = String(Base64.getDecoder().decode(blob.removePrefix(MAGIC).trim()), Charsets.UTF_8)
        val env = JSONObject(raw)
        val salt = Base64.getDecoder().decode(env.getString("salt"))
        val iv = Base64.getDecoder().decode(env.getString("iv"))
        val ct = Base64.getDecoder().decode(env.getString("ct"))
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val gz = cipher.doFinal(ct)
        return GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 65_536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun exportSnapshotJson(context: Context): JSONObject {
        val app = context.applicationContext
        val customTools = JSONArray().apply {
            runCatching { WhaleCustomToolEngine.loadAll(WhaleCustomToolEngine.storeFile(app)) }.getOrDefault(emptyList()).forEach { put(it) }
        }
        val macros = JSONArray().apply {
            runCatching { WhaleMacroEngine.list(app) }.getOrDefault(emptyList()).forEach { put(it.json()) }
        }
        val routines = JSONArray().apply {
            runCatching { WhaleRoutineEngine.list(app) }.getOrDefault(emptyList()).forEach { put(it.json()) }
        }
        val goals = JSONArray().apply {
            runCatching { WhaleGoalGraphStore.list(app, includeCompleted = true) }.getOrDefault(emptyList()).forEach { put(it.json()) }
        }
        val personas = runCatching { CompanionPersonaStore(app).list() }.getOrDefault(JSONObject())
        return JSONObject()
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("customTools", customTools)
            .put("macros", macros)
            .put("routines", routines)
            .put("goals", goals)
            .put("personas", personas)
    }

    fun push(context: Context, passphrase: String): JSONObject {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = p.getString("url", "").orEmpty().trimEnd('/')
        require(baseUrl.isNotBlank()) { "尚未配置 WebDAV 地址，请先调用 configure" }
        val user = p.getString("user", "").orEmpty()
        val pass = p.getString("pass", "").orEmpty()
        val targetUrl = "$baseUrl/whale-assistant-sync.e2ee"
        val snap = exportSnapshotJson(context)
        val encrypted = encryptPayload(snap.toString(), passphrase)
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            if (user.isNotBlank()) {
                val auth = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $auth")
            }
        }
        try {
            conn.outputStream.use { it.write(encrypted.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            require(code in 200..299) { "WebDAV 上传失败（HTTP $code）" }
            p.edit().putLong("last_sync_at", System.currentTimeMillis()).putString("last_action", "push").apply()
            return JSONObject()
                .put("ok", true)
                .put("action", "push")
                .put("httpStatus", code)
                .put("bytes", encrypted.length)
                .put("url", targetUrl)
        } finally {
            conn.disconnect()
        }
    }

    fun pull(context: Context, passphrase: String): JSONObject {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val baseUrl = p.getString("url", "").orEmpty().trimEnd('/')
        require(baseUrl.isNotBlank()) { "尚未配置 WebDAV 地址" }
        val user = p.getString("user", "").orEmpty()
        val pass = p.getString("pass", "").orEmpty()
        val targetUrl = "$baseUrl/whale-assistant-sync.e2ee"
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            if (user.isNotBlank()) {
                val auth = Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))
                setRequestProperty("Authorization", "Basic $auth")
            }
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "WebDAV 拉取失败（HTTP $code）" }
            val blob = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val plain = decryptPayload(blob, passphrase)
            val snap = JSONObject(plain)
            val app = context.applicationContext
            var importedTools = 0
            val toolsArr = snap.optJSONArray("customTools")
            if (toolsArr != null) {
                for (i in 0 until toolsArr.length()) {
                    val t = toolsArr.optJSONObject(i) ?: continue
                    runCatching {
                        WhaleCustomToolEngine.createOrUpdate(
                            WhaleCustomToolEngine.storeFile(app),
                            t.optString("name"),
                            t.optString("description"),
                            t.optString("kind"),
                            t.optJSONObject("params") ?: JSONObject(),
                            emptyList(),
                            t.optJSONObject("definition") ?: JSONObject(),
                        )
                        importedTools += 1
                    }
                }
            }
            p.edit().putLong("last_sync_at", System.currentTimeMillis()).putString("last_action", "pull").apply()
            return JSONObject()
                .put("ok", true)
                .put("action", "pull")
                .put("importedCustomTools", importedTools)
                .put("exportedAt", snap.optLong("exportedAt", 0L))
        } finally {
            conn.disconnect()
        }
    }
}
