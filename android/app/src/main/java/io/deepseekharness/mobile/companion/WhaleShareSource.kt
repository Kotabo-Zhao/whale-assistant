package io.deepseekharness.mobile.companion

import java.net.URI
import org.json.JSONObject

/** Provenance is a display hint. A web link alone does not provide the linked page's contents. */
internal data class WhaleShareSource(val label: String, val url: String?) {
    fun annotate(row: JSONObject) {
        val origins = row.optJSONArray("shareOrigins") ?: org.json.JSONArray().also {
            val previous = row.optString("shareOrigin")
            if (previous.isNotEmpty()) it.put(JSONObject().put("label", previous).put("url", row.optString("sourceUrl")))
        }
        val existing = (0 until origins.length()).firstOrNull { index ->
            origins.getJSONObject(index).let { it.optString("label") == label && it.optString("url") == url.orEmpty() }
        }
        if (existing != null) origins.remove(existing)
        if (origins.length() >= 12) origins.remove(0)
        origins.put(JSONObject().put("label", label).put("url", url.orEmpty()))
        row.put("shareOrigins", origins)
        row.put("shareOrigin", label)
        if (url != null) row.put("sourceUrl", url) else row.remove("sourceUrl")
    }
    companion object {
        fun labels(row: JSONObject): String {
            return allLabels(row).joinToString(" ")
        }
        fun allLabels(row: JSONObject): List<String> {
            val origins = row.optJSONArray("shareOrigins") ?: return listOf(row.optString("shareOrigin")).filter(String::isNotBlank)
            return (0 until minOf(origins.length(),12)).mapNotNull { origins.optJSONObject(it)?.optString("label")?.takeIf(String::isNotBlank) }.distinct()
        }
        private val links = Regex("""https?://[^\s<>"，。！？【】（）]+""", RegexOption.IGNORE_CASE)
        fun forPet(text: CharSequence?): WhaleShareSource {
            val inferred = from(null, text, false)
            return if (inferred.label == "系统分享") inferred.copy(label = "桌宠速存") else inferred
        }
        fun from(referrerPackage: String?, text: CharSequence?, isFile: Boolean): WhaleShareSource {
            val url = links.findAll((text ?: "").take(16_000)).map { it.value.trimEnd('.', ',', ')') }
                .firstOrNull { it.length <= 2048 && runCatching { URI(it).let { uri -> uri.host != null && uri.userInfo == null } }.getOrDefault(false) }
            val host = url?.let { URI(it).host.lowercase(java.util.Locale.ROOT) }.orEmpty()
            fun domain(name: String) = host == name || host.endsWith(".$name")
            val label = when {
                domain("weixin.qq.com") || domain("wechat.com") -> "微信链接"
                domain("xiaohongshu.com") || domain("xhslink.com") -> "小红书链接"
                referrerPackage == "com.tencent.mm" -> "微信分享"
                referrerPackage == "com.xingin.xhs" -> "小红书分享"
                isFile -> "分享文件"
                else -> "系统分享"
            }
            return WhaleShareSource(label, url)
        }
    }
}
