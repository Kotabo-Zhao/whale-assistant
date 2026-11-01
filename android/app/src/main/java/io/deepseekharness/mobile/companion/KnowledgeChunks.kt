package io.deepseekharness.mobile.companion

internal object KnowledgeChunks {
    fun split(text: String): List<String> {
        require(text.isNotBlank() && text.length <= CompanionKnowledgeData.MAX_DOCUMENT_TEXT)
        val result = mutableListOf<String>(); var offset = 0
        while (offset < text.length) {
            var end = minOf(text.length, offset + CompanionKnowledgeData.MAX_RECORD_TEXT)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            val chunk = text.substring(offset, end)
            // Even an all-whitespace segment is part of the original document.
            result.add(chunk)
            offset = end
        }
        return result
    }
}
