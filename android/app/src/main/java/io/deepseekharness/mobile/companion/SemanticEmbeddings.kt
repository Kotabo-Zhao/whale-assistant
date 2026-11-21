package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/**
 * 云端向量嵌入接口（已移除 103MB 离线 BGE ONNX 模型与 onnxruntime 原生库）。
 *
 * 默认不启用，日常检索走零成本本机 SQLite 词法 + 大模型自主检索；
 * 如需开启云端语义检索，配置 OpenAI 兼容 /embeddings 接口（如硅基流动 BAAI/bge-base-zh-v1.5）即可无缝接入。
 */
internal class SemanticEmbeddings(context: Context) {
    private val app = context.applicationContext

    data class CloudConfig(
        val url: String,
        val apiKey: String,
        val model: String,
    )

    companion object {
        const val MODEL = "BAAI/bge-base-zh-v1.5"
        const val DIMENSIONS = 768
        private const val PREFS = "semantic_search_v1"
        private const val KEY_CLOUD_URL = "cloud_embedding_url"
        private const val KEY_CLOUD_KEY = "cloud_embedding_key"
        private const val KEY_CLOUD_MODEL = "cloud_embedding_model"
        private const val DEFAULT_CLOUD_URL = "https://api.siliconflow.cn/v1/embeddings"

        fun readCloudConfig(context: Context): CloudConfig? {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val url = prefs.getString(KEY_CLOUD_URL, DEFAULT_CLOUD_URL)?.trim().orEmpty()
            val key = prefs.getString(KEY_CLOUD_KEY, "")?.trim().orEmpty()
            val model = prefs.getString(KEY_CLOUD_MODEL, MODEL)?.trim().orEmpty().ifEmpty { MODEL }
            if (url.isEmpty() || key.isEmpty()) return null
            return CloudConfig(url, key, model)
        }

        fun saveCloudConfig(context: Context, url: String = DEFAULT_CLOUD_URL, apiKey: String, model: String = MODEL) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CLOUD_URL, url.trim())
                .putString(KEY_CLOUD_KEY, apiKey.trim())
                .putString(KEY_CLOUD_MODEL, model.trim().ifEmpty { MODEL })
                .apply()
        }

        fun isCloudConfigured(context: Context): Boolean = readCloudConfig(context) != null

        fun releaseWhenIdle() = Unit
    }

    fun embed(texts: List<String>): List<FloatArray> {
        require(texts.size in 1..16 && texts.all { it.isNotBlank() && it.length <= 1_500 })
        val cfg = readCloudConfig(app)
            ?: throw IllegalStateException("未配置云端向量模型接口，当前默认使用本机词法与大模型检索")
        val payload = JSONObject()
            .put("model", cfg.model)
            .put("input", JSONArray(texts))
        val conn = (URL(cfg.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        }
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val raw = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("云端向量接口错误 ($code)：${raw.take(200)}")
            val data = JSONObject(raw).getJSONArray("data")
            val vectors = ArrayList<FloatArray>(data.length())
            for (i in 0 until data.length()) {
                val arr = data.getJSONObject(i).getJSONArray("embedding")
                val vec = FloatArray(arr.length()) { idx -> arr.getDouble(idx).toFloat() }
                vectors.add(vec)
            }
            require(vectors.size == texts.size) { "云端向量返回数量不匹配" }
            return vectors
        } finally {
            conn.disconnect()
        }
    }
}

/** 轻量纯 Kotlin WordPiece 分词工具（保留供单测与本地词法切分使用，零原生依赖）。 */
internal class BgeBaseZhTokenizer(vocabulary: List<String>) {
    private val ids = HashMap<String, Int>(vocabulary.size * 2).apply { vocabulary.forEachIndexed { index, token -> put(token, index) } }
    private val cls = ids.getValue("[CLS]")
    private val sep = ids.getValue("[SEP]")
    private val unk = ids.getValue("[UNK]")

    fun encode(text: String, maxTokens: Int = 512): LongArray {
        val tokens = ArrayList<Long>(minOf(maxTokens, 64)).apply { add(cls.toLong()) }
        for (word in basicTokens(text)) {
            if (tokens.size >= maxTokens - 1) break
            for (piece in wordPieces(word)) {
                if (tokens.size >= maxTokens - 1) break
                tokens.add(piece.toLong())
            }
        }
        tokens.add(sep.toLong())
        return tokens.toLongArray()
    }

    private fun basicTokens(text: String): List<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() { if (current.isNotEmpty()) { out.add(current.toString()); current.setLength(0) } }
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val count = Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == 0 || cp == 0xFFFD || type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> flush()
                Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR.toInt() -> flush()
                isCjk(cp) || isPunctuation(cp, type) -> { flush(); out.add(String(Character.toChars(cp)).lowercase(Locale.ROOT)) }
                else -> current.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
            }
            i += count
        }
        flush()
        return out
    }

    private fun wordPieces(word: String): List<Int> {
        if (word.length > 100) return listOf(unk)
        ids[word]?.let { return listOf(it) }
        val result = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var matched: Int? = null
            while (start < end) {
                val piece = if (start > 0) "##${word.substring(start, end)}" else word.substring(start, end)
                val id = ids[piece]
                if (id != null) { matched = id; break }
                end--
            }
            if (matched == null) return listOf(unk)
            result.add(matched)
            start = end
        }
        return result
    }

    private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
        cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    private fun isPunctuation(cp: Int, type: Int) = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
        type in setOf(Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(), Character.CURRENCY_SYMBOL.toInt(), Character.OTHER_SYMBOL.toInt())
}
