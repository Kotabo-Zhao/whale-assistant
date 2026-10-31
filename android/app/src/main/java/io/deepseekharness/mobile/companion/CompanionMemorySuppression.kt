package io.deepseekharness.mobile.companion

/** Exact normalized-text tombstones; semantic rephrasings require a separate reviewed policy. */
internal object CompanionMemorySuppression {
    fun digest(text: String): String {
        val normalized = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        return java.security.MessageDigest.getInstance("SHA-256").digest(CompanionKnowledgeData.utf8(normalized))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    /** Hashed character pairs permit conservative cross-event matching without storing full text. */
    fun signature(text: String): Set<String> {
        val normalized = java.text.Normalizer.normalize(text.trim(), java.text.Normalizer.Form.NFKC)
            .replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        val points = normalized.codePoints().toArray()
        if (points.size !in 6..1201) return emptySet()
        return (0 until points.size - 1).map { digest("gram:" + String(points, it, 2)) }.toSet().takeIf { it.size >= 4 } ?: emptySet()
    }
    fun encodeSignature(text: String): String? = signature(text).takeIf { it.isNotEmpty() }?.let { org.json.JSONArray(it.sorted()).toString() }
    fun parseSignature(value: String): Set<String> {
        val rows = org.json.JSONArray(value)
        require(rows.length() in 4..1200)
        return (0 until rows.length()).map { rows.getString(it).also { hash -> require(hash.matches(Regex("[a-f0-9]{64}"))) } }.toSet().also { require(it.size >= 4) }
    }
    fun matches(text: String, signatures: Set<String>): Boolean {
        val query = signature(text)
        if (query.isEmpty()) return false
        return signatures.any { raw ->
            val old = parseSignature(raw)
            val shared = query.count { it in old }
            shared >= 4 && shared * 100 >= minOf(query.size, old.size) * 85 && shared * 100 >= maxOf(query.size, old.size) * 70
        }
    }
}
