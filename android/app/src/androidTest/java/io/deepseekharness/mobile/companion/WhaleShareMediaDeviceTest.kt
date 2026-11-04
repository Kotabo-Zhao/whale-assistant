package io.deepseekharness.mobile.companion

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleShareMediaDeviceTest {
    @Test fun petCaptureSavesEditedTextWithoutSendingTheChatDraft() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val store = CompanionKnowledgeStore(app)
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("captureMode")
        val overlayContext = if (mode in setOf("light", "dark")) {
            val config = android.content.res.Configuration(app.resources.configuration)
            val night = if (mode == "dark") android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            app.createConfigurationContext(config)
        } else app
        val note = "桌宠快速存档 " + UUID.randomUUID()
        val draft = "手头的对话草稿 " + UUID.randomUUID()
        lateinit var pet: CompanionBubbleWindow
        lateinit var input: android.widget.EditText
        lateinit var editor: android.widget.EditText
        lateinit var dialog: android.app.AlertDialog
        fun children(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        try {
            inst.runOnMainSync {
                pet = CompanionBubbleWindow(overlayContext, app.getSystemService(android.view.WindowManager::class.java), autoOpenOnDelivery = false) {
                    intArrayOf(30, 180, 180, 180)
                }
                pet.show()
                val root = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(pet) as android.view.View
                input = children(root).filterIsInstance<android.widget.EditText>().single()
                input.setText(draft)
                check(children(root).single { it.contentDescription == "存入本地资料库" }.performClick())
                dialog = CompanionBubbleWindow::class.java.getDeclaredField("captureDialog").apply { isAccessible = true }.get(pet) as android.app.AlertDialog
                editor = children(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single()
                assertEquals(draft, editor.text.toString())
                editor.setText("")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing && editor.error != null)
                editor.setText("周四下午四点半，和设计组评审鲸鱼助手的资料入口。")
                assertNull(editor.error)
            }
            if (InstrumentationRegistry.getArguments().getString("captureProof") == "true") {
                Thread.sleep(450)
                val rect = android.graphics.Rect()
                inst.runOnMainSync {
                    val view = dialog.window!!.decorView.findViewById<android.view.View>(android.R.id.content)
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    rect.set(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
                }
                val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
                check(rect.intersect(0, 0, screen.width, screen.height))
                val card = Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height())
                java.io.File(app.getExternalFilesDir(null), "whale-quick-capture.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
                card.recycle(); if (!screen.isRecycled) screen.recycle()
            }
            inst.runOnMainSync {
                editor.setText(note)
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            val until = SystemClock.elapsedRealtime() + 10_000
            while (store.searchPage(note, 0, 1).first.length() == 0 && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            val row = store.searchPage(note, 0, 1).first.getJSONObject(0)
            assertEquals("桌宠速存", row.getString("shareOrigin"))
            assertEquals(note, row.getString("text"))
            assertEquals(0, store.searchPage(draft, 0, 1).first.length())
            inst.runOnMainSync { assertEquals(draft, input.text.toString()); assertTrue(input.isEnabled) }
        } finally {
            inst.runOnMainSync { pet.destroy() }
            store.searchPage(note, 0, 1).first.optJSONObject(0)?.let { store.remove(it.getString("id"), note) }
        }
    }

    /** Verifies the file actually selected in the external DocumentsUI share sheet. */
    @Test fun externallySharedFileIsIndexedAndReadable() {
        val tag = InstrumentationRegistry.getArguments().getString("externalShareTag")
        org.junit.Assume.assumeTrue(tag?.matches(Regex("[a-f0-9]{32}")) == true)
        val body = "外部文件投放验收 $tag"
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val store = CompanionKnowledgeStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val end = SystemClock.elapsedRealtime() + 30000
        try {
            while (SystemClock.elapsedRealtime() < end && store.searchPage(body, 0, 2).first.length() == 0) Thread.sleep(200)
            val original = requireNotNull(store.originalInfo(hash)) { "未收到系统文件管理器分享的原件" }
            assertEquals("Whale-$tag.txt", original.getString("displayName"))
            val extracted = store.searchPage(body, 0, 2).first.getJSONObject(0)
            assertEquals(hash, extracted.getString("originalId"))
            assertEquals(body, extracted.getString("text"))
        } finally {
            store.originalInfo(hash)?.let { store.removeOriginal(hash, it.getString("id"), it.getString("text")) }
        }
    }

    @Test fun pickedFileKeepsBothItsOriginalAndThePetNote() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val store = CompanionKnowledgeStore(app)
        val tag = UUID.randomUUID().toString()
        val note = "项目周报附件 $tag"
        val content = "蓝鲸试验本周进度 $tag"
        val bytes = content.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "whale-quick-$tag.txt"); put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        }))
        val selected = android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, Intent().setData(uri))
        val filter = android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val monitor = inst.addMonitor(filter, selected, true)
        try {
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            app.startActivity(Intent(app, WhaleMemoryShareActivity::class.java).apply {
                action = WhaleMemoryShareActivity.PICK; putExtra(Intent.EXTRA_TEXT, note); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            val until = SystemClock.elapsedRealtime() + 10_000
            while ((store.originalInfo(hash) == null || store.searchPage(content, 0, 1).first.length() == 0) && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            assertEquals(1, monitor.hits)
            assertEquals("桌宠速存", requireNotNull(store.originalInfo(hash)).getString("shareOrigin"))
            assertEquals(hash, store.searchPage(content, 0, 1).first.getJSONObject(0).getString("originalId"))
            val savedNote = store.searchPage(note, 0, 1).first.getJSONObject(0)
            assertEquals(note, savedNote.getString("text"))
            assertEquals(hash, store.attachmentPage(savedNote.getString("id"), note, 0, 10).first.getJSONObject(0).getString("originalId"))
        } finally {
            inst.removeMonitor(monitor)
            store.originalInfo(hash)?.let { store.removeOriginal(hash, it.getString("id"), it.getString("text")) }
            store.searchPage(note, 0, 1).first.optJSONObject(0)?.let { store.remove(it.getString("id"), note) }
            app.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun linksSurviveEditsAndDuplicatesButNeverRestoreAnOldOriginal() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "attachment-lifecycle-" + UUID.randomUUID()
        val dir = java.io.File(app.cacheDir, key).apply { check(mkdirs()) }
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(key + name, mode)
        }
        try {
            val store = CompanionKnowledgeStore(isolated)
            val text = "分段说明".repeat(2100)
            val caption = store.saveText(text, "shared_text", null)
            assertEquals(2, caption.recordIds.size)
            assertEquals(caption.recordIds, store.saveText(text, "shared_text", null).recordIds)
            val hash = "a".repeat(64)
            val anchor = store.registerOriginal(hash, "甲.pdf", "application/pdf", captionIds = caption.recordIds)
            store.registerOriginal(hash, "甲.pdf", "application/pdf", captionIds = caption.recordIds)
            val hashB = "b".repeat(64)
            store.registerOriginal(hashB, "乙.pdf", "application/pdf", captionIds = caption.recordIds)
            val id = caption.recordIds.last()
            val last = requireNotNull(store.findById(id)).getString("text")
            val first = store.attachmentPage(id, last, 0, 1)
            assertEquals(1, first.first.length()); assertEquals(1, first.second)
            assertNull(store.attachmentPage(id, last, 1, 1).second)
            assertTrue(store.edit(id, last, "修改后的说明"))
            assertThrows(IllegalStateException::class.java) { store.attachmentPage(id, last, 0, 10) }
            assertTrue(store.remove(id, "修改后的说明"))
            assertThrows(IllegalStateException::class.java) { store.attachmentPage(id, "修改后的说明", 0, 10) }
            assertTrue(store.undoRemove(id))
            assertEquals(2, store.attachmentPage(id, "修改后的说明", 0, 10).first.length())
            assertTrue(store.removeOriginal(hash, anchor.getString("id"), anchor.getString("text")).records > 0)
            store.registerOriginal(hash, "重新导入甲.pdf", "application/pdf")
            val left = store.attachmentPage(id, "修改后的说明", 0, 10).first
            assertEquals(1, left.length()); assertEquals(hashB, left.getJSONObject(0).getString("originalId"))
            val p = org.json.JSONObject().put("id", id).put("query", "").put("revision", CompanionMemorySuppression.digest("修改后的说明"))
            val read = org.json.JSONObject(CompanionKnowledgeModel.execute(true, p.toString(), store::findById,
                attachments = store::attachmentPage) { error("不应扫描全库") })
            assertEquals(hashB, read.getJSONArray("attachments").getJSONObject(0).getString("originalId"))
            assertNull(left.getJSONObject(0).opt("attachmentLinks"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun androidTextSelectionStoresExactlyTheChosenPassage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CompanionKnowledgeStore(app)
        val text = "选中段落 " + UUID.randomUUID()
        try {
            app.startActivity(Intent(app, WhaleMemoryShareActivity::class.java).apply {
                action = Intent.ACTION_PROCESS_TEXT; type = "text/plain"
                putExtra(Intent.EXTRA_PROCESS_TEXT, text); putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            val until = SystemClock.elapsedRealtime() + 8000
            while (store.searchPage(text, 0, 1).first.length() == 0 && SystemClock.elapsedRealtime() < until) Thread.sleep(80)
            assertEquals(text, store.searchPage(text, 0, 1).first.getJSONObject(0).getString("text"))
        } finally {
            store.searchPage(text, 0, 1).first.optJSONObject(0)?.let { store.remove(it.getString("id"), text) }
        }
    }
    @Test fun probeUserSelectedPublicArticle() {
        val url = InstrumentationRegistry.getArguments().getString("articleUrl")
        org.junit.Assume.assumeTrue("请提供公开文章地址", url != null)
        val result = WhalePublicArticle.fetch(requireNotNull(url))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putInt("articleChars", result.text.length); putString("articleTitle", result.title.take(50))
        })
        assertTrue(result.text.length >= 20)
    }
    @Test fun extractsOnlyTheSharedArticleAndRejectsNonPublicAddresses() {
        val fixture = "<html><head><title>笔记</title></head><body><nav>菜单</nav><div id='js_content'>第一次会议在周三举行，请带上笔记本。下次会议在周五。<script>ignore()</script></div></body></html>"
        val article = WhalePublicArticle.parse(fixture.toByteArray(), "UTF-8")
        assertEquals("笔记", article.title)
        assertTrue(article.text.contains("周三")); assertFalse(article.text.contains("菜单")); assertFalse(article.text.contains("ignore()"))
        for (ip in listOf("127.0.0.1", "10.0.0.2", "169.254.169.254", "100.64.0.1", "::1", "fd00::1")) {
            assertFalse(ip, WhalePublicArticle.publicAddress(java.net.InetAddress.getByName(ip)))
        }
        assertThrows(IllegalArgumentException::class.java) { WhalePublicArticle.parse("<main><form>请登录</form></main>".toByteArray(), "UTF-8") }
    }
    @Test fun androidSharePersistsCaptionScreenshotAndSpeech() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val store = CompanionKnowledgeStore(app)
        val tag = UUID.randomUUID().toString()
        val caption = "测试附言 $tag https://www.xiaohongshu.com/explore/$tag"
        val bitmap = Bitmap.createBitmap(1100, 300, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f }
            drawText("会议地点 七号会议室", 24f, 125f, pen)
            pen.textSize = 23f; drawText(tag, 24f, 230f, pen)
        }
        val png = java.io.ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        bitmap.recycle()
        val wav = inst.context.assets.open("whale-knowledge-mandarin.wav").use { it.readBytes() }
        fun id(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // An innocuous RIFF JUNK chunk gives each run its own valid, uncached recording.
        val nonce = tag.toByteArray(Charsets.US_ASCII)
        val audio = wav + java.nio.ByteBuffer.allocate(8 + nonce.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("JUNK".toByteArray()).putInt(nonce.size).put(nonce).array()
        java.nio.ByteBuffer.wrap(audio).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, audio.size - 8)
        val hashes = listOf(id(png), id(audio))
        val previouslyPresent = hashes.filter { store.originalInfo(it) != null }.toSet()
        val uris = arrayListOf<Uri>()
        fun add(collection: Uri, ext: String, type: String, bytes: ByteArray) {
            val uri = requireNotNull(app.contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "whale-share-$tag.$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, type)
            }))
            uris.add(uri)
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        }
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val closed = java.util.concurrent.atomic.AtomicLong(0)
        val callback = androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if (activity is WhaleMemoryShareActivity && stage == androidx.test.runner.lifecycle.Stage.PAUSED)
                closed.compareAndSet(0, SystemClock.elapsedRealtime())
        }
        inst.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "png", "image/png", png)
            add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "wav", "audio/wav", audio)
            val began = SystemClock.elapsedRealtime()
            app.startActivity(Intent(app, WhaleMemoryShareActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE; type = "*/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_TEXT, caption); putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                clipData = ClipData.newUri(app.contentResolver, "验收图片", uris.first()).apply { addItem(ClipData.Item(uris.last())) }
            })
            val end = SystemClock.elapsedRealtime() + 75_000
            fun found(query: String, media: String, hash: String): Boolean {
                val rows = store.searchPage(query, 0, 50).first
                return (0 until rows.length()).map(rows::getJSONObject).any {
                    it.optString("originalId") == hash && it.optString("mediaKind") == media && it.optString("shareOrigin") == "小红书链接"
                }
            }
            while (SystemClock.elapsedRealtime() < end && !(found("笔记本", "audio_transcript", hashes[1]) && found("会议", "image_ocr", hashes[0]))) Thread.sleep(250)
            assertTrue("分享音频转写缺失", found("笔记本", "audio_transcript", hashes[1]))
            assertTrue("分享图片 OCR 缺失", found("会议", "image_ocr", hashes[0]))
            val indexed = SystemClock.elapsedRealtime()
            while (closed.get() == 0L && SystemClock.elapsedRealtime() < end) Thread.sleep(20)
            assertTrue("投放页未退出", closed.get() >= began)
            inst.sendStatus(0, android.os.Bundle().apply {
                putLong("shareExitMs", closed.get() - began); putLong("bothSearchableMs", indexed - began)
            })
            val note = store.searchPage(caption.take(100), 0, 3).first.getJSONObject(0)
            val attached = store.attachmentPage(note.getString("id"), caption, 0, 10).first
            assertEquals(hashes.toSet(), (0 until attached.length()).map { attached.getJSONObject(it).getString("originalId") }.toSet())
        } finally {
            inst.runOnMainSync { monitor.removeLifecycleCallback(callback) }
            for (hash in hashes - previouslyPresent) store.originalInfo(hash)?.let { store.removeOriginal(hash, it.getString("id"), it.getString("text")) }
            val rows = store.searchPage(caption.take(100), 0, 3).first
            for (i in 0 until rows.length()) rows.getJSONObject(i).takeIf { it.getString("text") == caption }?.let { store.remove(it.getString("id"), caption) }
            uris.forEach { app.contentResolver.delete(it, null, null) }
        }
        val seconds = InstrumentationRegistry.getArguments().getString("intakeIdleSeconds")?.toIntOrNull() ?: return
        require(seconds in 30..300)
        val scheduler = app.getSystemService(android.app.job.JobScheduler::class.java)
        fun temperature() = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (scheduler.getPendingJob(WhaleParseJobs.JOB_ID) != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertNull(scheduler.getPendingJob(WhaleParseJobs.JOB_ID))
        val temp = temperature()
        val start = SystemClock.elapsedRealtime()
        val cpu = android.os.Process.getElapsedCpuTime()
        Thread.sleep(seconds * 1000L)
        val usedCpu = android.os.Process.getElapsedCpuTime() - cpu
        assertNull(scheduler.getPendingJob(WhaleParseJobs.JOB_ID))
        inst.sendStatus(0, android.os.Bundle().apply {
            putLong("idleWallMs", SystemClock.elapsedRealtime() - start); putLong("idleProcessCpuMs", usedCpu)
            putInt("startTempDeciC", temp); putInt("endTempDeciC", temperature()); putLong("processPssKiB", android.os.Debug.getPss())
        })
    }
}
