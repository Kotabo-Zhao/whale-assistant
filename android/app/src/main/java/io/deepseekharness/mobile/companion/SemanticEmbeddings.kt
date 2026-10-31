package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Remote embeddings are used only after the user explicitly enables semantic indexing. */
internal class SemanticEmbeddings(private val key: String) {
    companion object {
        const val MODEL = "text-embedding-3-small"
        const val DIMENSIONS = 256
    }

    fun embed(texts: List<String>): List<FloatArray> {
        require(key.isNotBlank() && '\n' !in key && '\r' !in key)
        require(texts.size in 1..16 && texts.all { it.isNotBlank() && it.length <= 1500 })
        val input = JSONArray(); texts.forEach { input.put(it) }
        val payload = JSONObject().put("model", MODEL).put("dimensions", DIMENSIONS).put("input", input)
        val connection = URL("https://api.openai.com/v1/embeddings").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 45_000
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw UnderstandingRequestException(when (status) {
                401, 403 -> "credentials"; 429 -> "rate_limited"; in 500..599 -> "service_unavailable"; else -> "request_rejected"
            }, "语义索引请求未成功")
            val response = connection.inputStream.use { inputStream ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    check(output.size() + count <= 1_000_000) { "向量响应过大" }
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString("UTF-8"))
            }
            val rows = response.getJSONArray("data")
            require(rows.length() == texts.size)
            val vectors = arrayOfNulls<FloatArray>(texts.size)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val index = row.getInt("index")
                require(index in texts.indices && vectors[index] == null)
                val array = row.getJSONArray("embedding")
                require(array.length() == DIMENSIONS)
                val vector = FloatArray(DIMENSIONS) { j -> array.getDouble(j).toFloat().also { require(it.isFinite()) } }
                val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
                require(norm.isFinite() && norm > 0)
                for (j in vector.indices) vector[j] = (vector[j] / norm).toFloat()
                vectors[index] = vector
            }
            return vectors.map { requireNotNull(it) }
        } finally { connection.disconnect() }
    }
}
