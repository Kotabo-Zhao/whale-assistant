package io.deepseekharness.mobile.companion

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

internal object WhaleImageText {
    /** Called only on the serialized file-import worker. The bundled recognizer works offline. */
    private var client: com.google.mlkit.vision.text.TextRecognizer? = null
    private val cleanup = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var release: java.util.concurrent.ScheduledFuture<*>? = null
    fun read(bitmap: Bitmap): String = recognize(bitmap).text.trim()
    @Synchronized fun recognize(bitmap: Bitmap): com.google.mlkit.vision.text.Text {
        val recognizer = client ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { client = it }
        release?.cancel(false)
        try {
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        } finally {
            release = cleanup.schedule({ synchronized(this) { client?.close(); client = null } },60,java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
