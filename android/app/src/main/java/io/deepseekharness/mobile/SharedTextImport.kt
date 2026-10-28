package io.deepseekharness.mobile

/** Bounds shared text before displaying or persisting it. Keep its original bytes. */
internal object SharedTextImport {
    private const val MAX_CHARS = 65_536
    private const val MAX_BYTES = 131_072
    fun canInsertDraft(value: String): Boolean = value.isNotBlank() && value.length <= 2000
    fun encode(value: CharSequence?): ByteArray? {
        if (value == null || value.length > MAX_CHARS) return null
        val text = value.toString()
        if (text.isBlank()) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        return bytes.takeIf { it.size <= MAX_BYTES }
    }
}
