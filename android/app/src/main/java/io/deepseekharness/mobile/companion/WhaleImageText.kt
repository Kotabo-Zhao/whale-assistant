package io.deepseekharness.mobile.companion

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

internal object WhaleImageText {
    /** Called only on the serialized file-import worker. The bundled recognizer works offline. */
    fun read(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text.trim()
        } finally { recognizer.close() }
    }
}
