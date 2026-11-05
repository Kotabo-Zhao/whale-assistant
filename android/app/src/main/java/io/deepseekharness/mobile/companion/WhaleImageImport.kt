package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.BitmapFactory
import io.deepseekharness.mobile.runtime.RuntimeStore
import java.io.File

internal object WhaleImageImport {
    private const val MAX_DIMENSION = 2048

    fun parse(app: Context, file: File): WhaleParsedContent? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DIMENSION) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        try {
            val orientation = runCatching { android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val rotation = when (orientation) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 }
            val rotated = if (rotation == 0) bitmap else android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
            try {
                val words = WhaleImageText.read(rotated)
                if (words.isNotBlank()) return WhaleParsedContent.document(words, "image_ocr")
                val key = RuntimeStore(app).readModelCredential("deepseek")?.takeIf { it.isNotBlank() } ?: throw WhaleParseFailure.Error(WhaleParseFailure.MODEL_REQUIRED)
                val bytes = java.io.ByteArrayOutputStream()
                check(rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bytes))
                return WhaleParsedContent(DeepSeekVisionModel(key).analyze(bytes.toByteArray()), "model_description", description = true)
            } finally { if (rotated !== bitmap) rotated.recycle() }
        } finally { bitmap.recycle() }
    }
}
