package io.deepseekharness.mobile.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Produces a bounded preview only after the caller has checked the current authorization. */
internal object KnowledgeImageModel {
    fun preview(context: Context, originalId: String, thumbnail: Boolean = false): String {
        val store = CompanionKnowledgeStore(context)
        val original = store.originalInfo(originalId) ?: error("原件记录不存在")
        require(original.optString("mime").startsWith("image/", ignoreCase = true)) { "该原件不是图片" }
        val file = KnowledgeObjects(context).getVerified(originalId)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth in 1..12_000 && bounds.outHeight in 1..12_000) { "图片尺寸不受支持" }
        var sample = 1
        val maxSide = if (thumbnail) 160 else 1024
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        val rotation = when (runCatching { android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            3 -> 180; 6 -> 90; 8 -> 270; else -> 0
        }
        val rotated = if (rotation == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        try {
            val bytes = ByteArrayOutputStream()
            check(rotated.compress(Bitmap.CompressFormat.PNG, 100, bytes))
            require(bytes.size() <= 6_000_000) { "图片预览过大" }
            return JSONObject().put("imageBase64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)).toString()
        } finally {
            if (rotated !== bitmap) rotated.recycle()
            bitmap.recycle()
        }
    }
}
