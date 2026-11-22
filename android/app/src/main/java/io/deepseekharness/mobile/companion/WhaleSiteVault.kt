package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeCredentialCipher
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * 通用站点登录凭据库（Android Keystore AES-256-GCM 加密存储）。
 *
 * - 按规范化主域名存储用户通过 [WhaleSiteLoginActivity] 显式授权的站点 Cookie；
 * - 仅在原生 HTTP 请求层按域名自动注入 `Cookie` 请求头（仅限 HTTPS），明文凭据永不回传给模型上下文。
 */
internal object WhaleSiteVault {
    private const val PREFS = "whale_site_vault_v1"
    private const val KEY_INDEX = "domains_meta"
    private const val PREFIX_COOKIE = "cookie_enc_"
    private const val MAX_DOMAINS = 32

    private val cipher by lazy { RuntimeCredentialCipher() }

    fun normalizeDomain(hostOrUrl: String): String {
        val raw = hostOrUrl.trim()
        val host = if (raw.contains("://")) {
            runCatching { URI(raw).host }.getOrNull().orEmpty()
        } else {
            raw.substringBefore('/').substringBefore(':')
        }.lowercase(Locale.ROOT).trim('.')
        require(host.isNotEmpty() && host.contains('.') && !WhaleCustomToolEngine.isHostBlocked(host)) {
            "无效或受限制的站点域名：$hostOrUrl"
        }
        return host
    }

    /** 归一到可匹配子域的根域（例如 www.bilibili.com -> bilibili.com）。 */
    fun rootDomain(host: String): String {
        val parts = host.lowercase(Locale.ROOT).trim('.').split('.').filter(String::isNotEmpty)
        return if (parts.size >= 2) "${parts[parts.size - 2]}.${parts[parts.size - 1]}" else host
    }

    @Synchronized
    fun saveCookie(context: Context, hostOrUrl: String, cookieHeader: String, label: String = ""): JSONObject {
        val app = context.applicationContext
        val domain = normalizeDomain(hostOrUrl)
        val root = rootDomain(domain)
        val cleanCookie = cookieHeader.trim()
        require(cleanCookie.isNotEmpty() && cleanCookie.length <= 3_800) { "Cookie 为空或超出安全长度上限" }

        val encrypted = cipher.encrypt(cleanCookie.toByteArray(Charsets.UTF_8))
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val meta = loadMeta(prefs)
        val next = JSONArray()
        for (i in 0 until meta.length()) {
            val item = meta.optJSONObject(i) ?: continue
            if (item.optString("domain") != root) next.put(item)
        }
        if (next.length() >= MAX_DOMAINS) {
            throw IllegalStateException("已授权站点数量达到上限（$MAX_DOMAINS），请先移除不用的站点")
        }
        val entry = JSONObject()
            .put("domain", root)
            .put("host", domain)
            .put("label", label.trim().ifBlank { root }.take(48))
            .put("updatedAt", now)
        next.put(entry)
        prefs.edit()
            .putString("$PREFIX_COOKIE$root", encrypted)
            .putString(KEY_INDEX, next.toString())
            .apply()
        return entry
    }

    /** 仅返回域名与授权时间，绝不返回明文 Cookie。 */
    @Synchronized
    fun listDomains(context: Context): JSONArray =
        loadMeta(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    @Synchronized
    fun revoke(context: Context, hostOrUrl: String): Boolean {
        val app = context.applicationContext
        val root = runCatching { rootDomain(normalizeDomain(hostOrUrl)) }.getOrElse {
            hostOrUrl.trim().lowercase(Locale.ROOT).trim('.')
        }
        if (root.isEmpty()) return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val meta = loadMeta(prefs)
        val next = JSONArray()
        var removed = false
        for (i in 0 until meta.length()) {
            val item = meta.optJSONObject(i) ?: continue
            if (item.optString("domain") == root || item.optString("host") == root) {
                removed = true
            } else {
                next.put(item)
            }
        }
        if (removed) {
            prefs.edit()
                .remove("$PREFIX_COOKIE$root")
                .putString(KEY_INDEX, next.toString())
                .apply()
        }
        return removed
    }

    /** 仅供原生网络层内部使用：仅对 HTTPS 请求匹配已授权主域的加密 Cookie。 */
    @Synchronized
    fun cookieForUrl(context: Context, rawUrl: String): String? {
        if (!rawUrl.startsWith("https://", ignoreCase = true)) return null
        val host = runCatching { URI(rawUrl).host?.lowercase(Locale.ROOT)?.trim('.') }.getOrNull().orEmpty()
        if (host.isEmpty() || WhaleCustomToolEngine.isHostBlocked(host)) return null
        val root = rootDomain(host)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enc = prefs.getString("$PREFIX_COOKIE$host", null)
            ?: prefs.getString("$PREFIX_COOKIE$root", null)
            ?: return null
        return runCatching {
            String(cipher.decrypt(enc), Charsets.UTF_8).trim().takeIf(String::isNotEmpty)
        }.getOrNull()
    }

    private fun loadMeta(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_INDEX, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }
}
