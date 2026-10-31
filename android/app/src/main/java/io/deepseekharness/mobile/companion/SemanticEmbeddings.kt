package io.deepseekharness.mobile.companion

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Offline BGE-base embeddings. The packaged model never sends memory or queries off-device. */
internal class SemanticEmbeddings(context: Context) {
    private val app = context.applicationContext

    companion object {
        const val MODEL = "bge-base-zh-v1.5-int8"
        const val DIMENSIONS = 768
        private const val MODEL_ASSET = "models/bge-base-zh-v1.5-int8.onnx"
        private const val VOCAB_ASSET = "models/bge-base-zh-v1.5-vocab.txt"
        private const val MODEL_BYTES = 102_868_746L
        private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
        private val lock = Any()
        private var runtime: Runtime? = null
    }

    private data class Runtime(val session: OrtSession, val tokenizer: BgeBaseZhTokenizer)

    fun embed(texts: List<String>): List<FloatArray> {
        require(texts.size in 1..16 && texts.all { it.isNotBlank() && it.length <= 1_500 })
        return texts.map { embedOne(it) }
    }

    private fun embedOne(text: String): FloatArray {
        val active = loadRuntime()
        val ids = active.tokenizer.encode(text)
        val masks = LongArray(ids.size) { 1L }
        val types = LongArray(ids.size)
        val tensors = listOf(ids, masks, types).map { values -> OnnxTensor.createTensor(environment, arrayOf(values)) }
        var result: OrtSession.Result? = null
        try {
            result = active.session.run(mapOf("input_ids" to tensors[0], "attention_mask" to tensors[1], "token_type_ids" to tensors[2]))
            @Suppress("UNCHECKED_CAST")
            val hidden = result[0].value as Array<Array<FloatArray>>
            val vector = hidden.single().first().copyOf()
            check(vector.size == DIMENSIONS)
            val norm = kotlin.math.sqrt(vector.sumOf { value -> value.toDouble() * value.toDouble() })
            check(norm.isFinite() && norm > 0)
            for (index in vector.indices) vector[index] = (vector[index] / norm).toFloat()
            return vector
        } finally {
            result?.close()
            tensors.forEach { it.close() }
        }
    }

    private fun loadRuntime(): Runtime = synchronized(lock) {
        runtime ?: run {
            val model = File(app.filesDir, "semantic-models/$MODEL.onnx")
            if (!model.isFile || model.length() != MODEL_BYTES) {
                model.parentFile?.mkdirs()
                val temporary = File(model.parentFile, "${model.name}.part")
                app.assets.open(MODEL_ASSET).use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
                check(temporary.length() == MODEL_BYTES)
                if (model.exists()) check(model.delete())
                check(temporary.renameTo(model))
            }
            val vocab = app.assets.open(VOCAB_ASSET).bufferedReader(Charsets.UTF_8).use { reader -> reader.readLines() }
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            Runtime(environment.createSession(model.absolutePath, options), BgeBaseZhTokenizer(vocab)).also { runtime = it }
        }
    }
}

/** BERT WordPiece tokenizer for the vocabulary shipped with BGE-base-zh-v1.5. */
internal class BgeBaseZhTokenizer(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { (index, token) -> token to index.toLong() }
    private val unknown = requireNotNull(vocab["[UNK]"])
    private val cls = requireNotNull(vocab["[CLS]"])
    private val sep = requireNotNull(vocab["[SEP]"])

    fun encode(text: String): LongArray {
        val pieces = basicTokens(text).flatMap(::wordPieces).take(510)
        return LongArray(pieces.size + 2).also { output ->
            output[0] = cls
            pieces.forEachIndexed { index, piece -> output[index + 1] = vocab[piece] ?: unknown }
            output[output.lastIndex] = sep
        }
    }

    private fun basicTokens(text: String): List<String> {
        val expanded = StringBuilder()
        text.codePoints().forEach { codePoint ->
            when {
                codePoint == 0 || codePoint == 0xfffd -> Unit
                Character.isWhitespace(codePoint) -> expanded.append(' ')
                isChinese(codePoint) -> expanded.append(' ').appendCodePoint(codePoint).append(' ')
                else -> expanded.appendCodePoint(codePoint)
            }
        }
        return expanded.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap(::splitPunctuation)
            .map { Normalizer.normalize(it, Normalizer.Form.NFD).filterNot { character -> Character.getType(character) == Character.NON_SPACING_MARK.toInt() }.lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    private fun splitPunctuation(value: String): List<String> {
        val result = mutableListOf<String>(); val current = StringBuilder()
        value.codePoints().forEach { codePoint ->
            if (isPunctuation(codePoint)) {
                if (current.isNotEmpty()) { result.add(current.toString()); current.clear() }
                result.add(String(Character.toChars(codePoint)))
            } else current.appendCodePoint(codePoint)
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    private fun wordPieces(token: String): List<String> {
        val codePoints = token.codePoints().toArray()
        if (codePoints.size > 100) return listOf("[UNK]")
        val result = mutableListOf<String>(); var start = 0
        while (start < codePoints.size) {
            var end = codePoints.size; var matched: String? = null
            while (end > start) {
                val part = String(codePoints, start, end - start)
                val candidate = if (start == 0) part else "##$part"
                if (candidate in vocab) { matched = candidate; break }
                end--
            }
            if (matched == null) return listOf("[UNK]")
            result.add(matched); start = end
        }
        return result
    }

    private fun isChinese(codePoint: Int): Boolean = codePoint in 0x4e00..0x9fff || codePoint in 0x3400..0x4dbf || codePoint in 0x20000..0x2a6df || codePoint in 0x2a700..0x2b73f || codePoint in 0x2b740..0x2b81f || codePoint in 0x2b820..0x2ceaf || codePoint in 0xf900..0xfaff
    private fun isPunctuation(codePoint: Int): Boolean = (codePoint in 33..47 || codePoint in 58..64 || codePoint in 91..96 || codePoint in 123..126) || when (Character.getType(codePoint)) {
        Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(), Character.CONNECTOR_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt() -> true
        else -> false
    }
}
