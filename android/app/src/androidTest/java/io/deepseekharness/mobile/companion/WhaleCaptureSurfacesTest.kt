package io.deepseekharness.mobile.companion

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleCaptureSurfacesTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext
    private fun shell(cmd: String) = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
    private fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime()+ms
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return true; Thread.sleep(100) }
        return condition()
    }
    private fun find(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (i in 0 until root.childCount) find(root.getChild(i), predicate)?.let { return it }
        return null
    }
    @org.junit.Before fun enableWindowInspection() {
        val service = inst.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        inst.uiAutomation.serviceInfo = service
    }
    private fun button(pkg: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val ui = inst.uiAutomation
        val active = ui.rootInActiveWindow
        if (active?.packageName == pkg) find(active, predicate)?.let { return it }
        for (window in ui.windows) {
            val root = window.root ?: continue
            if (root.packageName == pkg) find(root, predicate)?.let { return it }
        }
        return null
    }
    private fun diagnostic(): String {
        val roots = inst.uiAutomation.windows.mapNotNull { it.root }
        val actions = mutableSetOf<String>()
        fun inspect(node: AccessibilityNodeInfo) {
            if (node.isClickable || node.isLongClickable) actions.add(node.viewIdResourceName ?: node.className?.toString().orEmpty())
            for (i in 0 until node.childCount) node.getChild(i)?.let(::inspect)
        }
        roots.forEach(::inspect)
        return "windows=" + roots.map { it.packageName } + " actions=" + actions.take(40)
    }
    private fun tap(node: AccessibilityNodeInfo, long: Boolean = false) {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var parent: AccessibilityNodeInfo? = node
        while (parent != null) {
            if (!long && parent.isClickable && parent.performAction(action)) return
            parent = parent.parent
        }
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        val x = if (long) rect.left + minOf(rect.width() / 2f, 42f) else rect.exactCenterX()
        val start = SystemClock.uptimeMillis()
        fun touch(kind: Int) {
            val event = android.view.MotionEvent.obtain(start, SystemClock.uptimeMillis(), kind, x, rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { check(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        touch(android.view.MotionEvent.ACTION_DOWN)
        Thread.sleep(if (long) 850L else 80L)
        touch(android.view.MotionEvent.ACTION_UP)
    }
    private fun withTile(service: Class<*>, action: (String) -> Unit) {
        val name = ComponentName(app, service).flattenToShortString()
        val before = shell("settings get secure sysui_qs_tiles").trim()
        val exists = "custom($name)" in before
        try {
            if (!exists) { shell("cmd statusbar add-tile $name"); Thread.sleep(650) }
            action(name)
        } finally {
            shell("cmd statusbar collapse")
            if (!exists) shell("cmd statusbar remove-tile $name")
        }
    }
    private fun clickTile(name: String) {
        shell("cmd statusbar expand-settings"); Thread.sleep(450); shell("cmd statusbar click-tile $name")
    }

    private fun withChromePage(token: String, action: () -> Unit) {
        val title = "蓝鲸资料验收"
        val server = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))
        val html = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <body style="background:#eaf4f6;color:#183642;font:24px sans-serif;margin:0;padding:16px 28px"><h1 style="margin:8px 0;font-size:36px">$title</h1>
            <p style="margin:6px 0">文档编号 $token</p><p style="width:max-content;user-select:text;margin:8px 0">whalesave$token</p><p>这是一页用于收藏测试的本地资料。</p></body>""".trimIndent().toByteArray()
        val serving = Thread {
            runCatching {
                while (!server.isClosed) server.accept().use { client ->
                    client.soTimeout = 3000
                    val input = client.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) { }
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(html); out.flush()
                }
            }
        }.apply { isDaemon = true; start() }
        val url = "http://127.0.0.1:${server.localPort}/whale-$token"
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Chrome 没有显示指定的本地测试页", waitFor(16000) {
                button("com.android.chrome") { it.text?.toString()?.contains(token) == true && it.className != "android.widget.EditText" } != null
            })
            action()
        } finally { server.close(); serving.join(1000) }
    }

    @Test fun realChromeScreenUsesOneFrameThenReturnsToChrome() {
        val store = CompanionKnowledgeStore(app)
        val token = (100000..999999).random().toString()
        val title = "蓝鲸资料验收"
        var original: String? = null
        try {
            withChromePage(token) {
            withTile(WhaleScreenTileService::class.java) { name ->
                clickTile(name)
                var accept: AccessibilityNodeInfo? = null
                assertTrue("没有显示单次屏幕授权框", waitFor(8000) {
                    accept = button("com.android.systemui") { it.viewIdResourceName == "android:id/button1" || it.text?.toString() in setOf("立即开始", "Start now", "开始录制") }
                    accept != null
                })
                tap(accept!!)
                assertTrue("没有检索到当前 Chrome 页中的编号", waitFor(20000) {
                    val items = store.searchPage(token,0,20).first
                    original = (0 until items.length()).map(items::getJSONObject)
                        .firstOrNull { it.optString("mediaKind") == "image_ocr" && it.optString("shareOrigin") == "屏幕收藏" }?.getString("originalId")
                    original != null
                })
                val id = requireNotNull(original)
                assertTrue(store.originalInfo(id)?.getString("mime") == "image/png")
                val dashboard = CompanionDashboard(app)
                val cards = dashboard.execute(org.json.JSONObject().put("operation","knowledgeRecentCaptures")).getJSONArray("items")
                val anchor = requireNotNull(store.originalInfo(id))
                val card = (0 until cards.length()).map(cards::getJSONObject).single { it.getString("id") == anchor.getString("id") }
                val reading = dashboard.execute(org.json.JSONObject().put("operation","knowledgeCaptureRead").put("id",card.getString("id"))
                    .put("generation",card.getString("generation")).put("createdAt",card.getLong("createdAt")).put("offset",0))
                assertTrue((0 until reading.getJSONArray("items").length()).map(reading.getJSONArray("items")::getJSONObject)
                    .any { it.optString("mediaKind") == "image_ocr" && it.getString("text").contains(token) })

                assertTrue(waitFor(6000) { inst.uiAutomation.rootInActiveWindow?.packageName == "com.android.chrome" &&
                    !shell("dumpsys media_projection").contains("io.deepseekharness.mobile, uid=") })
                if (InstrumentationRegistry.getArguments().getString("captureProof") == "true") {
                    val image = android.graphics.BitmapFactory.decodeFile(KnowledgeObjects(app).getVerified(id).path)
                    val heading = android.graphics.Rect()
                    val number = android.graphics.Rect()
                    button("com.android.chrome") { it.text?.toString() == title }?.getBoundsInScreen(heading)
                    button("com.android.chrome") { it.text?.toString() == "文档编号 $token" }?.getBoundsInScreen(number)
                    if (!heading.isEmpty && !number.isEmpty) {
                        heading.union(number); heading.inset(-8, -8)
                        if (heading.intersect(0,0,image.width,image.height)) {
                            val excerpt = android.graphics.Bitmap.createBitmap(image, heading.left, heading.top, heading.width(), heading.height())
                            java.io.File(app.getExternalFilesDir(null),"whale-chrome-page.png").outputStream().use { excerpt.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                            excerpt.recycle()
                        }
                    }
                    if (!image.isRecycled) image.recycle()
                }
                inst.sendStatus(0, Bundle().apply { putString("screenSource", "Chrome local fixture"); putString("ocr", "matched unique page number") })
            }
            }
        } finally {
            original?.let { id -> store.originalInfo(id)?.let { store.removeOriginal(id,it.getString("id"),it.getString("text")) } }
        }
    }

    @Test fun chromeTextMenuSendsSelectedWordToMemory() {
        val token = (100000..999999).random().toString()
        val word = "whalesave$token"
        val store = CompanionKnowledgeStore(app)
        try {
            withChromePage(token) {
                inst.uiAutomation.waitForIdle(250, 3000)
                val passage = requireNotNull(button("com.android.chrome") { it.text?.toString() == word })
                val rect = android.graphics.Rect(); passage.getBoundsInScreen(rect)
                inst.sendStatus(0, Bundle().apply { putString("selectedTargetBounds", rect.toShortString()) })
                tap(passage, true)
                fun saveAction() = button("com.android.chrome") { it.text?.toString() == "存入鲸鱼记忆" }
                if (!waitFor(1000) { saveAction() != null }) {
                    val more = button("com.android.chrome") {
                        it.viewIdResourceName?.contains("overflow") == true || it.contentDescription?.toString() in setOf("更多选项","更多","More options","More","打开溢出菜单","Open overflow")
                    }
                    assertNotNull("Chrome 此界面没有显示选词扩展菜单 " + diagnostic(),more)
                    tap(more!!)
                }
                assertTrue("Chrome 选词菜单未显示存入鲸鱼记忆", waitFor(2500) { saveAction() != null })
                tap(saveAction()!!)
                assertTrue(waitFor(9000) { store.searchPage(word,0,3).first.let { rows ->
                    (0 until rows.length()).any { rows.getJSONObject(it).getString("text") == word }
                }})
                assertTrue(waitFor(5000) { inst.uiAutomation.rootInActiveWindow?.packageName == "com.android.chrome" })
            }
        } finally {
            store.searchPage(word,0,3).first.let { rows ->
                for (i in 0 until rows.length()) rows.getJSONObject(i).takeIf { it.getString("text") == word }?.let { store.remove(it.getString("id"),word) }
            }
        }
    }

    private fun pickWhaleShareTarget(files: String): Long {
        fun target() = button("android") { it.text?.toString() == "存入鲸鱼记忆" } ?:
            button("com.android.intentresolver") { it.text?.toString() == "存入鲸鱼记忆" }
        var share: AccessibilityNodeInfo? = null
        val fileMenuFound = waitFor(5500) {
            val systemChooser = inst.uiAutomation.rootInActiveWindow?.packageName in setOf("android","com.android.intentresolver")
            share = button(files) { it.viewIdResourceName?.endsWith(":id/action_menu_share") == true ||
                it.contentDescription?.toString() in setOf("Share", "分享","共享") }
            systemChooser || target() != null || share != null
        }
        assertTrue("文件管理器未打开分享菜单 " + diagnostic(), fileMenuFound)
        share?.let { tap(it) }
        var whale = target()
        repeat(4) {
            if (whale == null) {
                Thread.sleep(550)
                whale = target()
                if (whale == null) {
                    val r = android.graphics.Rect()
                    inst.uiAutomation.rootInActiveWindow?.getBoundsInScreen(r)
                    if (!r.isEmpty) shell("input swipe ${r.centerX()} ${r.bottom-35} ${r.centerX()} ${r.top+35} 400")
                }
            }
        }
        assertNotNull("系统分享面板未显示鲸鱼记忆",whale)
        val began = SystemClock.elapsedRealtime()
        tap(whale!!)
        return began
    }

    @Test fun systemFilesShareSavesTheChosenFileAndReturns() {
        val tag = UUID.randomUUID().toString().take(8)
        val dir = "Download/WhaleCollect-$tag"
        val fileName = "whale-$tag.txt"
        val content = "系统文件直接投放 $tag"
        val store = CompanionKnowledgeStore(app)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,fileName); put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH,dir)
        }))
        try {
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(content.toByteArray()) }
            val folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$dir")
            val files = "com.android.documentsui"
            app.startActivity(Intent(Intent.ACTION_VIEW).setPackage(files).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var file: AccessibilityNodeInfo? = null
            assertTrue("文件管理器未打开测试文件夹", waitFor(10000) { file = button(files) { it.text?.toString() == fileName }; file != null })
            inst.uiAutomation.waitForIdle(250, 3000)
            val item = requireNotNull(button(files) { it.viewIdResourceName == "com.android.documentsui:id/item_root" })
            val bounds = android.graphics.Rect(); item.getBoundsInScreen(bounds)
            shell("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 850")
            pickWhaleShareTarget(files)
            assertTrue("文件没有从系统分享进入资料库", waitFor(10000) { store.searchPage(content,0,1).first.length() > 0 })
            assertEquals(hash,store.searchPage(content,0,1).first.getJSONObject(0).getString("originalId"))
            assertTrue(waitFor(5000) { inst.uiAutomation.rootInActiveWindow?.packageName == files })
        } finally {
            store.originalInfo(hash)?.let { store.removeOriginal(hash,it.getString("id"),it.getString("text")) }
            app.contentResolver.delete(uri,null,null)
        }
    }

    @Test fun systemFilesReturnsWhileSelectedAudioAndNoteAreStillBeingParsed() {
        val tag = UUID.randomUUID().toString()
        val dir = "Download/WhaleBatch-$tag"
        val files = "com.android.documentsui"
        val store = CompanionKnowledgeStore(app)
        val note = "批量投放文件管理器 $tag"
        val source = inst.context.assets.open("whale-knowledge-mandarin.wav").use { it.readBytes() }
        val nonce = tag.toByteArray()
        val recording = source + java.nio.ByteBuffer.allocate(8 + nonce.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("JUNK".toByteArray()).putInt(nonce.size).put(nonce).array()
        java.nio.ByteBuffer.wrap(recording).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(4, recording.size - 8)
        fun hash(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val noteHash = hash(note.toByteArray())
        val audioHash = hash(recording)
        val uris = mutableListOf<Uri>()
        fun putFile(name: String, mime: String, bytes: ByteArray) {
            val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime); put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            }))
            uris.add(uri)
            requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        }
        try {
            val noteName = "01-note-${tag.take(8)}.txt"
            val audioName = "02-audio-${tag.take(8)}.wav"
            putFile(noteName, "text/plain", note.toByteArray())
            putFile(audioName, "audio/wav", recording)
            val folder = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$dir")
            app.startActivity(Intent(Intent.ACTION_VIEW).setPackage(files).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun item(name: String) = button(files) { it.text?.toString() == name }
            assertTrue(waitFor(10000) { item(noteName) != null && item(audioName) != null })
            inst.uiAutomation.waitForIdle(350, 3000)
            fun row(name: String): AccessibilityNodeInfo {
                var node = requireNotNull(item(name))
                while (node.viewIdResourceName?.endsWith(":id/item_root") != true) node = node.parent ?: error("未找到测试文件所在行")
                return node
            }
            fun selected() = button(files) { it.viewIdResourceName?.endsWith(":id/action_menu_share") == true } != null
            val first = row(noteName)
            if (first.isLongClickable) first.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            if (!waitFor(700) { selected() }) {
                val r = android.graphics.Rect(); row(noteName).getBoundsInScreen(r)
                shell("input swipe ${r.centerX()} ${r.centerY()} ${r.centerX()} ${r.centerY()} 850")
            }
            assertTrue("文件管理器未进入多选状态 " + diagnostic(), waitFor(4000) { selected() })
            val next = row(audioName)
            tap(next)
            val began = pickWhaleShareTarget(files)
            assertTrue("原件已保存后未返回文件管理器", waitFor(10000) {
                inst.uiAutomation.rootInActiveWindow?.packageName == files && store.originalInfo(noteHash) != null && store.originalInfo(audioHash) != null
            })
            val returned = SystemClock.elapsedRealtime()
            val audioState = requireNotNull(store.originalInfo(audioHash)).getString("parseState")
            assertEquals(note, KnowledgeObjects(app).getVerified(noteHash).readText())
            assertArrayEquals(recording, KnowledgeObjects(app).getVerified(audioHash).readBytes())
            assertTrue(waitFor(30000) { store.originalInfo(audioHash)?.optString("coverage") == "extracted_text" })
            val hits = store.searchPage("笔记本", 0, 50).first
            assertTrue((0 until hits.length()).any { hits.getJSONObject(it).optString("originalId") == audioHash })
            assertEquals(noteHash, store.searchPage(note, 0, 1).first.getJSONObject(0).getString("originalId"))
            inst.sendStatus(0, Bundle().apply {
                putLong("filesReturnMs", returned - began); putString("audioAtReturn", audioState)
                putLong("bothSearchableMs", SystemClock.elapsedRealtime() - began)
            })
            if (InstrumentationRegistry.getArguments().getString("openReader") == "true") {
                openSavedAudio(audioName)
            }
        } finally {
            for (id in listOf(noteHash, audioHash)) store.originalInfo(id)?.let { store.removeOriginal(id, it.getString("id"), it.getString("text")) }
            uris.forEach { app.contentResolver.delete(it, null, null) }
            shell("rmdir /sdcard/$dir")
        }
    }

    private fun openSavedAudio(audioName: String) {
        val main = inst.startActivitySync(Intent(app, io.deepseekharness.mobile.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as io.deepseekharness.mobile.MainActivity
        fun js(script: String): String {
            val latch = java.util.concurrent.CountDownLatch(1)
            var out = ""
            inst.runOnMainSync { main.bridge.webView.evaluateJavascript(script) { out = it; latch.countDown() } }
            check(latch.await(4, java.util.concurrent.TimeUnit.SECONDS))
            return out
        }
        fun visibleLabel(label: String) = button(app.packageName) {
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label ||
                (it.className == "android.widget.Button" && (it.text?.contains(label) == true || it.contentDescription?.contains(label) == true)))
        }
        fun tapLabel(label: String) {
            var target: AccessibilityNodeInfo? = null
            check(waitFor(8000) { target = visibleLabel(label); target != null }) { "未显示按钮 $label" }
            val rect = android.graphics.Rect()
            requireNotNull(target).getBoundsInScreen(rect)
            check(!rect.isEmpty)
            shell("input tap ${rect.centerX()} ${rect.centerY()}")
        }
        fun navState() = js("[history.length,history.state?.dshView,history.state?.whaleKnowledgeView?.view,!!document.querySelector('.capture-reader'),!!document.querySelector('#knowledge-detail-title')]")
        try {
            assertTrue("主应用导航未就绪", waitFor(16000) {
                if (visibleLabel("记忆") != null) true else {
                    button(app.packageName) { it.viewIdResourceName?.endsWith(":id/harness_manage") == true }?.let { tap(it) }
                    false
                }
            })
            tapLabel("记忆"); tapLabel("资料")
            val title = org.json.JSONObject.quote(audioName)
            val card = "Array.from(document.querySelectorAll('.capture-card')).find(b=>b.querySelector('strong')?.textContent===$title)"
            assertTrue("实际分享的录音没有出现在最近投放", waitFor(7000) { js("!!($card)") == "true" })
            tapLabel(audioName)
            val reader = "document.querySelector('.capture-reader')"
            assertTrue("录音详情没有显示实际转写", waitFor(7000) {
                js("($reader)?.innerText.includes('录音转写') && ($reader)?.innerText.includes('笔记本')") == "true"
            })
            // Crop only the opaque reader displaying this generated fixture, never the surrounding collection.
            if (InstrumentationRegistry.getArguments().getString("captureProof") == "true") {
                val bounds = org.json.JSONArray(js("(()=>{const r=($reader).getBoundingClientRect();return [r.left,r.top,r.right,r.bottom,window.innerWidth]})()"))
                inst.waitForIdleSync()
                val web = main.bridge.webView
                val origin = IntArray(2)
                inst.runOnMainSync { web.getLocationInWindow(origin) }
                val scale = web.width / bounds.getDouble(4)
                val box = android.graphics.Rect(origin[0]+kotlin.math.ceil(bounds.getDouble(0)*scale).toInt()+2,
                    origin[1]+kotlin.math.ceil(bounds.getDouble(1)*scale).toInt()+2,
                    origin[0]+kotlin.math.floor(bounds.getDouble(2)*scale).toInt()-2,
                    origin[1]+kotlin.math.floor(bounds.getDouble(3)*scale).toInt()-2)
                val bitmap = android.graphics.Bitmap.createBitmap(box.width(), box.height(), android.graphics.Bitmap.Config.ARGB_8888)
                val latch = java.util.concurrent.CountDownLatch(1); var result = -1
                android.view.PixelCopy.request(main.window, box, bitmap, { result = it; latch.countDown() },
                    android.os.Handler(android.os.Looper.getMainLooper()))
                check(latch.await(4, java.util.concurrent.TimeUnit.SECONDS) && result == android.view.PixelCopy.SUCCESS)
                java.io.File(app.getExternalFilesDir(null),"whale-audio-reader.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
            tapLabel("打开资料详情")
            assertTrue(waitFor(5000) { js("!!document.querySelector('#knowledge-detail-title')") == "true" })
            shell("input keyevent KEYCODE_BACK")
            check(waitFor(5000) { js("($reader)?.innerText.includes('笔记本')") == "true" }) { "系统返回没有回到同一份转写 " + navState() }
            shell("input keyevent KEYCODE_BACK")
            assertTrue("再次返回没有回到最近投放", waitFor(5000) { js("!($reader) && !!($card) && !document.querySelector('#knowledge-detail-title')") == "true" })
            // Keep a deterministic parse pending while the same visible reader is open.
            val liveStore = CompanionKnowledgeStore(app)
            val body = "本机阅读事件 " + UUID.randomUUID()
            val hash = CompanionMemorySuppression.digest(body)
            val name = "更新验收-" + hash.take(8) + ".txt"
            val scratch = java.io.File.createTempFile("reader-event", ".txt", app.cacheDir)
            try {
                scratch.writeText(body)
                KnowledgeObjects(app).store(scratch, hash, 65536)
                val anchor = liveStore.registerOriginal(hash, name, "text/plain", WhaleShareSource("本地文件", null))
                val ticket = liveStore.withParseDatabase { it.appendRecordIfAbsent(anchor, explicitReimport = true, reserveParse = {}) }.getString("parseTicket")
                val liveCard = "Array.from(document.querySelectorAll('.capture-card')).find(b=>b.querySelector('strong')?.textContent===" + org.json.JSONObject.quote(name) + ")"
                assertTrue("可见资料页没有自动接到新的投放", waitFor(5000) { js("!!($liveCard)") == "true" })
                tapLabel(name)
                assertTrue(waitFor(5000) { js("($reader)?.innerText.includes('后台解析完成后')") == "true" })
                liveStore.commitParsed(ticket, WhaleParsedContent.document(body, null))
                assertTrue("打开阅读页后未显示刚完成的正文",waitFor(5000) { js("($reader)?.innerText.includes(" + org.json.JSONObject.quote(body) + ")") == "true" })
                liveStore.removeOriginal(hash, anchor.getString("id"), anchor.getString("text"))
                assertTrue("原件已删除仍显示正文",waitFor(5000) {
                    js("($reader)?.innerText.includes('已删除或替换') && !($reader)?.innerText.includes(" + org.json.JSONObject.quote(body) + ")") == "true"
                })
            } finally {
                scratch.delete()
                liveStore.originalInfo(hash)?.let { liveStore.removeOriginal(hash,it.getString("id"),it.getString("text")) }
            }
            inst.sendStatus(0, Bundle().apply { putString("captureReader", "system Files, recent audio card, local transcript, original detail, hardware back, live intake, live parse completion, live deletion") })
        } finally { inst.runOnMainSync { main.finish() } }
    }

    @Test fun appIntakeGuideOpensSystemTileSetup() {
        val pkg = app.packageName
        fun label(text: String) = button(pkg) { it.text?.toString() == text || it.contentDescription?.toString() == text }
        fun clickLabel(text: String) {
            var view: AccessibilityNodeInfo? = null
            assertTrue("找不到 $text " + diagnostic(), waitFor(8500) { view = label(text); view != null })
            tap(view!!)
        }
        shell("am start -W -n $pkg/.MainActivity")
        assertTrue("记忆导航未就绪 " + diagnostic(), waitFor(18000) {
            if (label("记忆") != null) true else {
                button(pkg) { it.viewIdResourceName?.endsWith(":id/harness_manage") == true }?.let { tap(it) }
                false
            }
        })
        clickLabel("记忆"); clickLabel("资料"); clickLabel("从其他 App 投放")
        val capture = "添加「收藏剪贴板」"
        assertTrue(waitFor(5000) { label(capture) != null })
        if (InstrumentationRegistry.getArguments().getString("captureProof") == "true") {
            val screen = requireNotNull(inst.uiAutomation.takeScreenshot())
            val rect = android.graphics.Rect()
            val start = android.graphics.Rect()
            label("从其他 App 投放")?.getBoundsInScreen(start)
            label(capture)?.getBoundsInScreen(rect)
            label("添加「收藏这一屏」")?.let { val last = android.graphics.Rect(); it.getBoundsInScreen(last); rect.union(last) }
            if (!rect.isEmpty && !start.isEmpty) {
                rect.union(start); rect.inset(-4,-4)
                if (rect.intersect(0,0,screen.width,screen.height)) {
                    val crop = android.graphics.Bitmap.createBitmap(screen,rect.left,rect.top,rect.width(),rect.height())
                    java.io.File(app.getExternalFilesDir(null),"whale-intake-guide.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    crop.recycle()
                }
            }
            if (!screen.isRecycled) screen.recycle()
        }
        val originalTiles = shell("settings get secure sysui_qs_tiles").trim()
        clickLabel(capture)
        if (originalTiles.contains(WhaleMemoryTileService::class.java.simpleName)) {
            assertTrue(waitFor(5000) { label("可从快捷设置点“收藏剪贴板”。") != null })
        } else {
            var cancel: AccessibilityNodeInfo? = null
            assertTrue("未见系统磁贴添加请求 " + diagnostic(), waitFor(6000) {
                cancel = button("com.android.systemui") { it.viewIdResourceName == "android:id/button2" ||
                    it.text?.toString() in setOf("取消","不添加","Cancel","Don't add","Do not add") }
                cancel != null
            })
            tap(cancel!!)
            assertEquals(originalTiles, shell("settings get secure sysui_qs_tiles").trim())
        }
    }

    @Test fun verifyManualSystemFilesSelection() {
        val tag = InstrumentationRegistry.getArguments().getString("probeTag") ?: return org.junit.Assume.assumeTrue("仅核验手动系统分享样本",false)
        require(tag.matches(Regex("whaledrop[0-9]{6}")))
        val content = "鲸鱼文件夹实际投放 $tag"
        val store = CompanionKnowledgeStore(app)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        val anchor = requireNotNull(store.originalInfo(hash))
        try {
            assertEquals("$tag.txt", anchor.getString("displayName"))
            assertArrayEquals(content.toByteArray(), KnowledgeObjects(app).getVerified(hash).readBytes())
            assertEquals(hash, store.searchPage(content,0,1).first.getJSONObject(0).getString("originalId"))
            inst.sendStatus(0, Bundle().apply { putString("sourcePath", "DocumentsUI share > Whale Memory > DocumentsUI"); putLong("verifiedBytes", content.toByteArray().size.toLong()) })
        } finally { store.removeOriginal(hash, anchor.getString("id"), anchor.getString("text")) }
    }

    @Test fun realClipboardTileReadsOnlyTheSelectedTestCopy() {
        val source = inst.context.packageName
        val note = "tile-clipboard-" + UUID.randomUUID()
        val store = CompanionKnowledgeStore(app)
        try {
            shell("am start -W -f 0x10008000 -n $source/${ForeignDropSource::class.java.name} --es mode clipboard --es note $note")
            Thread.sleep(450)
            var copy: AccessibilityNodeInfo? = null
            assertTrue(waitFor(7000) { copy = button(source) { it.text?.toString() == "复制验收文字" }; copy != null })
            tap(copy!!)
            assertTrue("测试来源的复制动作未执行", waitFor(6500) { button(source) { it.text?.toString() == "清除本次测试副本" || it.text?.toString() == "已有剪贴板内容" } != null })
            org.junit.Assume.assumeTrue("保留设备现有剪贴板；这项测试仅在空剪贴板上执行", button(source) { it.text?.toString() == "已有剪贴板内容" } == null)
            withTile(WhaleMemoryTileService::class.java) { name ->
                clickTile(name)
                assertTrue("磁贴未保存", waitFor(10000) { store.searchPage(note,0,1).first.length() > 0 })
                assertEquals("剪贴板投放",store.searchPage(note,0,1).first.getJSONObject(0).getString("shareOrigin"))
                assertTrue(waitFor(6000) { button(source) { it.text?.toString() == "清除本次测试副本" } != null })
                inst.sendStatus(0, Bundle().apply { putString("clipboardPath","source copy, system tile, indexed text, source resumed") })
            }
        } finally {
            button(source) { it.text?.toString() == "清除本次测试副本" }?.let { tap(it) }
            store.searchPage(note,0,1).first.optJSONObject(0)?.let { store.remove(it.getString("id"),note) }
        }
    }
}
