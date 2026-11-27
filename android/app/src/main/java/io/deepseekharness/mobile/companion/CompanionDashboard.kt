package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Settings UI uses the same stores as the companion, with bounded, reviewed writes. */
internal class CompanionDashboard(private val context: Context) {
    private val memory get() = CompanionMemoryStore(context)
    private val knowledge get() = CompanionKnowledgeStore(context)
    private val reminder get() = CompanionReminder(context)
    private val recurring get() = CompanionRecurringReminder(context)

    private fun reviewedRows(rows: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until rows.length()) {
            val original = rows.getJSONObject(i).toString()
            put(JSONObject(original).put("_review", original))
        }
    }
    /** The conversation draft carries a value that mobile_knowledge_read verifies before reading. */
    private fun reviewedKnowledgeRows(rows: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val original = row.toString()
            val view = JSONObject(original).put("_review", original)
                .put("revision", CompanionMemorySuppression.digest(row.getString("text")))
            if (row.optString("source") == "original" && row.optString("coverage") == "partial" && !row.has("audioTranscriptionVersion")) {
                val mime = row.optString("mime")
                if (WhaleAudioTranscript.supports(WhaleOriginalImport.extension(row.optString("displayName"),mime),mime)) {
                    view.put("canContinueAudio",knowledge.withParseDatabase { it.canContinueAudio(row) })
                }
            }
            put(view)
        }
    }

    fun execute(request: JSONObject): JSONObject {
        val op = request.getString("operation")
        fun text(key: String) = request.getString(key)
        fun success(ok: Boolean): JSONObject { require(ok) { "内容已变化，请刷新后重试" }; return JSONObject().put("ok", true) }
        fun withCurrentKnowledge(action: (String, String, String) -> Boolean): JSONObject {
            val id = text("id")
            val expectedText = text("expectedText")
            val hash = CompanionMemorySuppression.digest(expectedText)
            var completed = false
            check(knowledge.withUnchangedRecord(id, expectedText) { completed = action(id, expectedText, hash) }) { "资料已变化，请刷新后重试" }
            return success(completed)
        }
        return when (op) {
            "assistantBriefings" -> JSONObject().put("items", JSONArray(WorkerReportInbox(context).briefings().takeLast(20).reversed()))
            "personaList" -> {
                val res = CompanionPersonaStore(context).list()
                val cards = res.optJSONArray("cards") ?: JSONArray()
                val emotions = JSONObject()
                for (i in 0 until cards.length()) {
                    val card = cards.optJSONObject(i) ?: continue
                    val cid = card.optString("id").trim()
                    if (cid.isNotEmpty()) {
                        emotions.put(cid, WhaleEmotionEngine.load(context, cid, card.optString("name")).json())
                    }
                }
                res.put("emotions", emotions)
            }
            "personaEmotionActive" -> {
                val active = CompanionPersonaStore(context).active()
                val id = active.optString("id").ifBlank { "default" }
                val name = active.optString("name").ifBlank { "鲸鱼娘" }
                JSONObject()
                    .put("ok", true)
                    .put("id", id)
                    .put("name", name)
                    .put("emotion", WhaleEmotionEngine.load(context, id, name).json())
            }
            "personaEmotionGet" -> {
                val id = text("id").trim()
                val name = request.optString("name").trim()
                JSONObject().put("ok", true).put("emotion", WhaleEmotionEngine.load(context, id, name).json())
            }
            "personaEmotionNote" -> {
                val id = text("id").trim()
                val name = request.optString("name").trim()
                val note = text("note").trim()
                require(note.isNotEmpty()) { "默契记录不能为空" }
                val next = WhaleEmotionEngine.addGrowthNote(context, id, name, note)
                JSONObject().put("ok", true).put("emotion", next.json())
            }
            "personaEmotionReset" -> {
                val id = text("id").trim()
                val name = request.optString("name").trim()
                val reset = WhaleEmotionEngine.defaultState(id, name)
                WhaleEmotionEngine.save(context, reset)
                JSONObject().put("ok", true).put("emotion", reset.json())
            }
            "personaSave" -> JSONObject().put("ok", true).put("card", CompanionPersonaStore(context).save(request.getJSONObject("card")))
            "personaActivate" -> { CompanionPersonaStore(context).activate(text("id")); JSONObject().put("ok", true) }
            "personaDelete" -> { val id = text("id"); CompanionPersonaStore(context).delete(id); WhaleEmotionEngine.delete(context, id); JSONObject().put("ok", true) }
            "extensionsList" -> JSONObject()
                .put("skills", WhaleSkillStore.list(context))
                .put("mcpServers", WhaleMcpManager.listServers(context))
            "settleOutbox" -> {
                val key = request.optString("key").trim()
                JSONObject().put("ok", true).put("settled", WhaleSuperagentRelay.settleOutbox(context, key))
            }
            "flushOutbox" -> {
                WhaleSuperagentRelay.resumeInterruptedIfNeeded(context)
                JSONObject().put("ok", true).put("flushed", WhaleSuperagentRelay.flushPendingRelays(context))
            }
            "liveIslandPrefs" -> {
                if (request.has("floatingIsland")) {
                    WhaleNativeLiveNotification.setFloatingIslandEnabled(context, request.optBoolean("floatingIsland", false))
                }
                if (request.has("nativeNotif")) {
                    WhaleNativeLiveNotification.setNativeNotifEnabled(context, request.optBoolean("nativeNotif", true))
                }
                JSONObject()
                    .put("ok", true)
                    .put("floatingIsland", WhaleNativeLiveNotification.isFloatingIslandEnabled(context))
                    .put("nativeNotif", WhaleNativeLiveNotification.isNativeNotifEnabled(context))
            }
            "readinessProbe" -> {
                val app = context.applicationContext
                val overlayOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.provider.Settings.canDrawOverlays(app)
                } else true
                val health = runCatching { WhaleAutoHealBridge.health(app) }.getOrElse { JSONObject() }
                val a11yOk = health.optBoolean("accessibilityConnected", false)
                val shizukuOk = health.optBoolean("shizukuReady", false)
                val notifOk = runCatching {
                    val flat = android.provider.Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners").orEmpty()
                    flat.contains(app.packageName)
                }.getOrDefault(false)
                val store = io.deepseekharness.mobile.runtime.RuntimeStore(app)
                val modelKeyOk = io.deepseekharness.mobile.runtime.ModelProvider.entries.any {
                    !store.readModelCredential(it.wireValue).isNullOrBlank()
                } || store.settings().customModelProviders.any {
                    !store.readModelCredential(it.id).isNullOrBlank()
                }
                val voiceKeyOk = runCatching { WhaleVoiceEngine.readConfig(app).apiKey.isNotBlank() }.getOrDefault(false)
                JSONObject()
                    .put("overlay", overlayOk)
                    .put("accessibility", a11yOk)
                    .put("notifications", notifOk)
                    .put("shizuku", shizukuOk)
                    .put("modelKey", modelKeyOk)
                    .put("voiceKey", voiceKeyOk)
            }
            "skillInstall" -> {
                val url = request.optString("url").trim()
                val name = request.optString("name").trim()
                val desc = request.optString("description").trim()
                val content = request.optString("content").trim()
                if (url.isNotEmpty()) WhaleSkillStore.installFromUrl(context, url, name)
                else WhaleSkillStore.saveLocal(context, name, desc, content)
            }
            "skillManage" -> when (request.optString("action").lowercase()) {
                "enable" -> WhaleSkillStore.setEnabled(context, text("name"), true)
                "disable" -> WhaleSkillStore.setEnabled(context, text("name"), false)
                "delete", "remove" -> WhaleSkillStore.delete(context, text("name"))
                else -> throw IllegalArgumentException("不支持的 Skill 操作")
            }
            "mcpAdd" -> WhaleMcpManager.addOrUpdateServer(
                context = context,
                id = request.optString("id").trim(),
                name = request.optString("name").trim(),
                rawUrl = text("url").trim(),
                authHeader = request.optString("authHeader").trim(),
            )
            "mcpManage" -> when (request.optString("action").lowercase()) {
                "refresh" -> WhaleMcpManager.refreshServer(context, text("id"))
                "enable" -> WhaleMcpManager.setEnabled(context, text("id"), true)
                "disable" -> WhaleMcpManager.setEnabled(context, text("id"), false)
                "remove", "delete" -> WhaleMcpManager.removeServer(context, text("id"))
                else -> throw IllegalArgumentException("不支持的 MCP 操作")
            }
            "workspaceList" -> WhaleCodeRunner.listWorkspaceFiles(context, request.optString("project", ""))
            "workspacePreview" -> WhaleCodeRunner.previewFile(
                context,
                request.optString("path", request.optString("fileName", "")),
                request.optString("project", ""),
            )
            "workspaceDelete" -> WhaleCodeRunner.deleteWorkspaceFile(
                context,
                request.optString("project", ""),
                request.optString("path", ""),
            )
            "workspaceExport" -> WhaleCodeRunner.exportProjectFile(
                context,
                request.optString("project", "scratch"),
                request.optString("path", ""),
                request.optBoolean("share", false),
            )
            "automationHealth" -> WhaleAutoHealBridge.health(context)
            "automationAutoFix" -> WhaleAutoHealBridge.autoHeal(context, request.optBoolean("relaxWhitelist", true))
            "uploadChatFile" -> WhaleCodeRunner.saveUploadedChatFile(
                context,
                request.optString("fileName", ""),
                request.optString("base64", ""),
            )
            "openExportedFile" -> WhaleCodeRunner.openOrShareExportedFile(
                context,
                request.optString("fileName", ""),
                "open",
            )
            "shareExportedFile" -> WhaleCodeRunner.openOrShareExportedFile(
                context,
                request.optString("fileName", ""),
                "share",
            )
            "remoteBridgeGet" -> WhaleRemoteBridge.getConfig(context)
            "remoteBridgeSave" -> WhaleRemoteBridge.saveConfig(
                context,
                request.optString("url", ""),
                request.optString("token", ""),
                request.optBoolean("enabled", true),
            )
            "remoteBridgeTest" -> WhaleRemoteBridge.testConnection(context)
            "workerQuickReply" -> WorkerRuntimeWake.followupOrResume(
                context,
                text("id"),
                text("message"),
            )
            "sharedChatRead" -> {
                val sinceMs = request.optLong("sinceMs", 0L)
                JSONObject().put("messages", WhaleSharedChatStore.list(context, sinceMs))
            }
            "sharedChatAppend" -> {
                WhaleSharedChatStore.appendTurn(
                    context,
                    request.optString("userText", ""),
                    request.optString("assistantText", ""),
                    request.optString("source", "app"),
                )
                JSONObject().put("ok", true)
            }
            "memoryCompact" -> {
                val all = memory.listForUser()
                val seen = HashSet<String>()
                var removed = 0
                for (i in 0 until all.length()) {
                    val row = all.optJSONObject(i) ?: continue
                    val id = row.optString("id").trim()
                    val norm = row.optString("text").replace(Regex("[\\s，。,.!！?？:：;；]+"), "").lowercase()
                    if (id.isEmpty()) continue
                    if (norm.length < 2 || !seen.add(norm)) {
                        runCatching {
                            memory.execute(JSONObject().put("op", "delete").put("id", id).toString())
                            removed++
                        }
                    }
                }
                JSONObject().put("ok", true).put("scanned", all.length()).put("removed", removed)
            }
            "usageAndRouterGet" -> {
                val (fast, deep) = WhaleModelRouter.readSceneOverrides(context)
                JSONObject()
                    .put("usage", WhaleTokenMeter.breakdownJson(context))
                    .put("fastModel", fast)
                    .put("deepModel", deep)
            }
            "routerConfigSave" -> WhaleModelRouter.saveSceneOverrides(
                context,
                request.optString("fastModel", ""),
                request.optString("deepModel", ""),
            ).put("ok", true)
            "overview" -> {
                val store = CompanionStore(context)
                val records = store.read()
                val counts = CompanionBrief.counts(records)
                val tasks = UnifiedUnderstandingStore.open(context).use { it.queue.listActionableItems(3) }
                JSONObject().put("generatedAt", System.currentTimeMillis())
                    .put("captureEnabled", store.notificationCapture)
                    .put("notificationCount", records.length())
                    .put("categories", JSONObject(counts))
                    .put("memoryCount", memory.countForUser())
                    .put("knowledgeCount", knowledge.count())
                    .put("tasks", JSONArray().apply { tasks.forEach { item -> put(JSONObject()
                        .put("id", item.id).put("text", item.title).put("state", item.state)
                        .put("assessment", item.assessment?.let { JSONObject(it) } ?: JSONObject.NULL)
                        .put("reminderAtMs", item.reminderAtMs ?: JSONObject.NULL)) } })
                    .put("reminders", reminder.list()).put("recurring", recurring.list())
            }

            "unifiedStats" -> {
                val memCount = runCatching { memory.countForUser() }.getOrDefault(0)
                val knCount = runCatching { knowledge.count() }.getOrDefault(0)
                JSONObject()
                    .put("memoryCount", memCount)
                    .put("knowledgeCount", knCount)
            }
            "unifiedRecall" -> {
                val q = request.optString("query", "").trim()
                val lim = request.optInt("limit", 6).coerceIn(1, 12)
                val memMatches = JSONArray()
                val knMatches = JSONArray()
                if (q.isNotEmpty()) {
                    val lower = q.lowercase()
                    val allMem = runCatching { memory.listForUser() }.getOrElse { JSONArray() }
                    for (i in 0 until allMem.length()) {
                        val row = allMem.optJSONObject(i) ?: continue
                        if (row.optString("text", "").lowercase().contains(lower)) {
                            memMatches.put(row)
                            if (memMatches.length() >= lim) break
                        }
                    }
                    val knPage = runCatching { knowledge.searchPage(q, 0, lim).first }.getOrElse { JSONArray() }
                    for (i in 0 until knPage.length()) {
                        knPage.optJSONObject(i)?.let { knMatches.put(it) }
                    }
                }
                JSONObject()
                    .put("query", q)
                    .put("memories", memMatches)
                    .put("knowledge", knMatches)
            }
            "memoryList" -> {
                val store = CompanionStore(context)
                val suggestions = if (store.notificationMemorySuggestions)
                    CompanionMemorySuggestions.fromRecords(store.read(), store.excludedPackages(), store.dismissedMemorySuggestions()) else emptyList()
                val page = if (memory.usesDatabase()) memory.pageForUser(null, null) else null
                val enrichedItems = reviewedRows(page?.rows ?: memory.listForUser())
                for (i in 0 until enrichedItems.length()) {
                    enrichedItems.optJSONObject(i)?.let { WhaleMemoryTaxonomy.enrichRow(it) }
                }
                val watchArr = JSONArray().apply {
                    WhaleProactiveEngine.list(context).forEach { put(it.json()) }
                }
                JSONObject().put("items", enrichedItems).put("watchlist", watchArr).put("suggestions", JSONArray().apply {
                    suggestions.forEach { put(JSONObject().put("id", it.id).put("text", it.excerpt).put("source", it.packageName).put("time", it.time)) }
                }).apply {
                    if (page != null) { put("nextBefore", page.next ?: JSONObject.NULL); put("revision", page.revision) }
                }
            }
            "memoryUpdateMeta" -> {
                val id = text("id").trim()
                val cat = request.optString("category").takeIf { it.isNotBlank() }
                val pinned = if (request.has("pinned")) request.optBoolean("pinned") else null
                val salience = if (request.has("salience")) request.optInt("salience") else null
                JSONObject().put("ok", memory.updateMetadataForUser(id, cat, pinned, salience))
            }
            "memoryDigest" -> JSONObject().put("ok", true).put("digest", memory.digestForAgent())
            "proactiveList" -> JSONObject().put("ok", true).put("items", JSONArray().apply {
                WhaleProactiveEngine.list(context).forEach { put(it.json()) }
            })
            "proactiveAct" -> WhaleProactiveEngine.act(context, text("id").trim(), text("action").trim())
            "memoryPage" -> {
                require(memory.usesDatabase()) { "记忆数据库不可用" }
                val before = request.opt("before") as? Number ?: throw IllegalArgumentException("记忆分页参数无效")
                val revision = request.opt("revision") as? Number ?: throw IllegalArgumentException("记忆分页参数无效")
                val page = memory.pageForUser(before.toLong(), revision.toLong())
                JSONObject().put("items", reviewedRows(page.rows)).put("nextBefore", page.next ?: JSONObject.NULL).put("revision", page.revision)
            }
            "memorySuggestionDismiss" -> { CompanionStore(context).dismissMemorySuggestion(text("id")); JSONObject().put("ok", true) }
            "memorySuggestionsReset" -> { CompanionStore(context).clearDismissedMemorySuggestions(); JSONObject().put("ok", true) }
            "memoryReview" -> {
                val review = memory.reviewForUser(text("text"), request.optString("id").ifBlank { null })
                JSONObject().put("snapshot", review.snapshot).put("truncated", review.truncated)
                    .put("candidates", JSONArray().apply { review.candidates.forEach { put(JSONObject().put("status", it.first).put("text", it.second)) } })
            }
            "memoryAdd" -> JSONObject().put("result", memory.addForUser(text("text"), "user_edit", text("snapshot")).name)
            "memoryApprove" -> success(memory.approveForUser(text("id"), text("expectedText"), text("snapshot")))
            "memoryApproveEdit" -> success(memory.approveEditedForUser(text("id"), text("expectedText"), text("text"), text("snapshot")))
            "memoryEdit" -> success(memory.updateForUser(text("id"), text("expected"), text("text"), text("snapshot")))
            "memoryDelete" -> success(memory.deleteForUser(text("id"), text("expected")))
            "memoryBatchDelete" -> {
                val entries = request.optJSONArray("entries") ?: throw IllegalArgumentException("批量删除参数无效")
                val deleted = memory.deleteManyForUser(entries) ?: throw IllegalStateException("记忆已变化，请刷新后重试")
                JSONObject().put("ok", true).put("deleted", deleted)
            }
            "memoryResumeAutomatic" -> { memory.resumeAutomaticSources(); JSONObject().put("ok", true) }
            "memoryClear" -> { require(request.optBoolean("confirmed")); memory.clearForUser(); JSONObject().put("ok", true) }
            "knowledgeRecentCaptures" -> knowledge.withParseDatabase {
                JSONObject().put("items", it.captures.cards())
            }
            "knowledgeCaptureRead" -> knowledge.withParseDatabase { db ->
                val row = db.captures.current(text("id"), request.getLong("createdAt"), request.optString("generation"))
                    ?: return@withParseDatabase JSONObject().put("missing", true)
                // Slot zero holds an image analysis; document chunks start at slot one.
                val index = row.optInt("chunkIndex",0)
                val offset = if (!request.has("offset") && index > 0) index + 1 else request.optInt("offset",0)
                val (parts, next) = db.captures.contents(row, offset)
                JSONObject().put("row", reviewedKnowledgeRows(JSONArray().put(row)).getJSONObject(0))
                    .put("items", parts).put("offset",offset).put("nextOffset", next ?: JSONObject.NULL)
            }
            "knowledgeList" -> {
                val rawQuery = request.opt("query")
                val query = when (rawQuery) {
                    null, JSONObject.NULL -> ""
                    is String -> rawQuery
                    else -> throw IllegalArgumentException("资料查询参数无效")
                }
                val rawOffset = request.opt("offset")
                val offset = when (rawOffset) {
                    null, JSONObject.NULL -> 0
                    is Int -> rawOffset
                    is Long -> rawOffset.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                        ?: throw IllegalArgumentException("资料查询参数无效")
                    else -> throw IllegalArgumentException("资料查询参数无效")
                }
                require(query.length <= 100 && offset >= 0) { "资料查询参数无效" }
                fun filter(key: String, values: Set<String>): String? {
                    if (!request.has(key) || request.isNull(key)) return null
                    return (request.opt(key) as? String)?.takeIf { it in values }
                        ?: throw IllegalArgumentException("资料查询参数无效")
                }
                val source = filter("source", setOf("shared_text", "text_file", "original", "image_analysis"))
                val coverage = filter("coverage", setOf("not_parsed", "partial", "extracted_text", "model_description"))
                val (items, nextOffset) = knowledge.searchPage(query, offset, 20, source, coverage)
                JSONObject().put("items", reviewedKnowledgeRows(items)).put("nextOffset", nextOffset ?: JSONObject.NULL)
                    .put("source", source ?: JSONObject.NULL).put("coverage", coverage ?: JSONObject.NULL)
            }
            "knowledgeAdd" -> JSONObject().put("ok", knowledge.add(text("text"), "text_file"))
            "knowledgeEdit" -> success(knowledge.edit(text("id"), text("expectedText"), text("text")))
            "knowledgeDelete" -> success(knowledge.remove(text("id"), text("expectedText")))
            "knowledgeBatchDelete" -> {
                val entries = request.optJSONArray("entries") ?: throw IllegalArgumentException("批量删除参数无效")
                val removed = knowledge.removeMany(entries) ?: throw IllegalStateException("资料已变化，请刷新后重试")
                JSONObject().put("ok", true).put("deleted", removed.length())
            }
            "knowledgeUndoDelete" -> success(knowledge.undoRemove(text("id")))
            "knowledgeBatchUndoDelete" -> {
                val ids = request.optJSONArray("ids") ?: throw IllegalArgumentException("批量撤销参数无效")
                val values = (0 until ids.length()).map { ids.optString(it) }
                success(knowledge.undoMany(values))
            }
            "knowledgeRetryParse" -> success(knowledge.withParseDatabase {
                it.retryParse(text("id"), text("expected")) { WhaleParseJobs.reserve(context) }
            })
            "knowledgeProcessingStatus" -> {
                val id = text("id")
                val expectedText = text("expectedText")
                var status: JSONObject? = null
                check(knowledge.withUnchangedRecord(id, expectedText) {
                    status = SharedMemoryJobs.status(context, id, CompanionMemorySuppression.digest(expectedText))
                }) { "资料已变化，请刷新后重试" }
                checkNotNull(status).put("memoryEnabled", memory.enabled)
            }
            "knowledgeProcessingRetry" -> {
                require(memory.enabled) { "长期记忆整理已关闭" }
                withCurrentKnowledge { id, _, hash ->
                    SharedMemoryJobs.retryRecord(context, id, hash).also { if (it) SharedMemoryJobs.schedule(context, 0) }
                }
            }
            "knowledgeProcessingCancel" -> withCurrentKnowledge { id, _, hash ->
                SharedMemoryJobs.cancelRecord(context, id, hash)
            }
            "knowledgeProcessingResume" -> {
                require(memory.enabled) { "长期记忆整理已关闭" }
                withCurrentKnowledge { id, value, _ ->
                    SharedMemoryJobs.resumeRecord(context, id, value).also { if (it) SharedMemoryJobs.schedule(context, 0) }
                }
            }
            "knowledgeAttachments" -> {
                val (items, next) = knowledge.attachmentPage(text("id"), text("expectedText"), request.getInt("offset"), 20)
                JSONObject().put("items", reviewedKnowledgeRows(items)).put("nextOffset", next ?: JSONObject.NULL)
            }
            "knowledgeImagePreview" -> {
                val id = text("id")
                val expectedText = text("expectedText")
                val originalId = text("originalId")
                var preview: JSONObject? = null
                check(knowledge.withUnchangedRecord(id, expectedText) {
                    check(knowledge.findById(id)?.optString("originalId") == originalId) { "资料已变化，请刷新后重试" }
                    preview = JSONObject(KnowledgeImageModel.preview(context, originalId, request.optBoolean("thumbnail")))
                }) { "资料已变化，请刷新后重试" }
                checkNotNull(preview)
            }
            "knowledgeRemoveOriginal" -> {
                val removal = knowledge.removeOriginal(text("originalId"), text("id"), text("expectedText"))
                require(removal.records > 0) { "资料已变化，请刷新后重试" }
                JSONObject().put("ok", true).put("records", removal.records)
                    .put("objectDeleted", removal.objectDeleted).put("undoAvailable", false)
            }
            "reminderList" -> {
                val store = CompanionStore(context)
                JSONObject().put("items", reviewedRows(reminder.list())).put("recurring", reviewedRows(recurring.list()))
                    .put("quietStart", store.quietHoursStart).put("quietEnd", store.quietHoursEnd)
                    .put("notificationStatus", CompanionReminderHealth.read(context).name)
            }
            "superagentSettings" -> SuperagentSettings(context).status(request.optBoolean("diagnosticsOnly"))
            "superagentSettingsSave" -> SuperagentSettings(context).save(request)
            "knowledgePickFiles" -> {
                context.startActivity(android.content.Intent(context, WhaleMemoryShareActivity::class.java)
                    .setAction(WhaleMemoryShareActivity.PICK).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                JSONObject().put("ok", true)
            }
            "knowledgeReadLink" -> {
                val id = text("id")
                val expected = text("expectedText")
                val row = requireNotNull(knowledge.findById(id))
                require(row.getString("text") == expected)
                val url = row.getString("sourceUrl")
                val article = runCatching { WhalePublicArticle.fetch(url) }.getOrElse {
                    return JSONObject().put("ok", false).put("reason", "网页没有返回可读取的公开正文。原分享已保留，也可分享截图或导出文件。")
                }
                val origin = WhaleShareSource(row.optString("shareOrigin", "分享网页"), url)
                val digest = CompanionMemorySuppression.digest(url + "\n" + article.text)
                check(knowledge.withUnchangedRecord(id, expected) {
                    check(knowledge.findById(id)?.optString("sourceUrl") == url)
                    knowledge.addDocument(article.text, digest, article.title, origin = origin, mediaKind = "web_article")
                })
                JSONObject().put("ok", true).put("title", article.title)
            }
            "featureTools" -> {
                val fStore = io.deepseekharness.mobile.features.WhaleFeatureStore(context)
                val allRows = fStore.list()
                val byId = allRows.associateBy { it.optString("id") }
                val activeArr = JSONArray()
                fStore.activeTools().forEach { tool ->
                    val meta = byId[tool.id]
                    val latest = meta?.optString("latest").orEmpty().ifBlank { tool.revision }
                    val previous = meta?.optString("previous").orEmpty()
                    activeArr.put(
                        tool.json()
                            .put("latest", latest)
                            .put("previous", previous)
                            .put("hasUpdate", latest.isNotEmpty() && latest != tool.revision)
                            .put("activatedAt", tool.activatedAt),
                    )
                }
                val draftsArr = JSONArray()
                allRows.filter { it.optString("active").isEmpty() }.forEach { row ->
                    val latest = row.optString("latest")
                    if (latest.isNotEmpty()) {
                        draftsArr.put(
                            JSONObject()
                                .put("id", row.optString("id"))
                                .put("title", row.optString("title"))
                                .put("revision", latest)
                                .put("latest", latest)
                                .put("previous", row.optString("previous"))
                                .put("pinned", row.optBoolean("pinned", false))
                                .put("activatedAt", row.optLong("activatedAt")),
                        )
                    }
                }
                JSONObject().put("items", activeArr).put("drafts", draftsArr)
            }
            "featureOpen" -> {
                io.deepseekharness.mobile.features.WhaleFeatureActivity.openTool(
                    context,
                    text("id"),
                    text("revision"),
                    preview = request.optBoolean("preview", false),
                )
                JSONObject().put("ok", true)
            }
            "featureActivate" -> {
                val updated = io.deepseekharness.mobile.features.WhaleFeatureStore(context)
                    .activate(text("id"), request.optString("target", ""), request.optString("expectedActive", ""))
                JSONObject().put("ok", true).put("item", updated)
            }
            "featurePin" -> {
                val updated = io.deepseekharness.mobile.features.WhaleFeatureStore(context)
                    .setPinned(text("id"), request.optBoolean("pinned", false))
                JSONObject().put("ok", true).put("item", updated)
            }
            "featureDelete" -> {
                val deleted = io.deepseekharness.mobile.features.WhaleFeatureStore(context).delete(text("id"))
                JSONObject().put("ok", deleted)
            }
            "featurePinHome" -> {
                val title = text("title")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    io.deepseekharness.mobile.features.WhaleFeatureActivity.pinToHomeScreen(
                        context,
                        text("id"),
                        title,
                        text("revision"),
                    )
                }
                JSONObject().put("ok", true)
            }
            "featureLibrary" -> {
                io.deepseekharness.mobile.features.WhaleFeatureActivity.openLibrary(context)
                JSONObject().put("ok", true)
            }
            "workerPage" -> WorkerDashboardStore(context).page(request.optString("filter","all"),request.optString("group"),
                request.optInt("offset",0),request.optInt("limit",20), withCounts = false)
            "workerGroups" -> JSONObject().put("groups",WorkerDashboardStore(context).groups())
            "workerGroupAnalysisDraft" -> JSONObject().put("draft",WorkerDashboardStore(context).analysisDraft(text("group")))
            "workerDetail" -> WorkerDashboardStore(context).detail(text("id"),request.optInt("offset",0))
            "workerList" -> {
                val base = WorkerDashboardStore(context).list()
                val items = base.optJSONArray("items") ?: JSONArray()
                val byId = LinkedHashMap<String, JSONObject>()
                for (i in 0 until items.length()) {
                    val row = items.optJSONObject(i) ?: continue
                    val id = row.optString("id", "").trim()
                    if (id.isNotEmpty()) {
                        if (!row.has("status") && row.has("state")) row.put("status", row.optString("state"))
                        byId[id] = row
                    }
                }
                val live = WhaleNativeWorkerPool.snapshot()
                for (i in 0 until live.length()) {
                    val row = live.optJSONObject(i) ?: continue
                    val id = row.optString("id", "").trim()
                    if (id.isNotEmpty()) {
                        val statusVal = when {
                            row.optString("attention").isNotBlank() || row.optString("stage") == "person/answer" -> "attention"
                            else -> row.optString("state", "running")
                        }
                        row.put("status", statusVal)
                        byId[id] = row
                    }
                }
                val merged = JSONArray()
                byId.values.forEach { merged.put(it) }
                base.put("items", merged).put("workers", merged)
            }
            "workerPreviewDraft" -> {
                WorkerDashboardStore(context).previewDraft(text("id"), text("revision"))
                JSONObject().put("ok", true)
            }
            "workerSubmit" -> WorkerRuntimeWake.submit(context, text("task"), text("mode"))
            "workerControl" -> WorkerDashboardStore(context).command(request)
            "workerPermission" -> WorkerDashboardStore(context).heldAction(text("id"),text("action"))
            "workerBlockedSettings" -> { WorkerDashboardStore(context).openBlockedSettings(text("id")); JSONObject().put("ok",true) }
            "workerResumeAuthorizedRead" -> WorkerDashboardStore(context).resumeAuthorizedRead(text("id"),request.getLong("writeSeq"))
            "screenWatchStatus" -> ScreenWatchAccess(context).status()
            "screenWatchSet" -> { ScreenWatchAccess(context).enabled(request.getBoolean("enabled")); ScreenWatchAccess(context).status() }
            "screenWatchStop" -> { ScreenWatchAccess(context).stop(); ScreenWatchAccess(context).status() }
            "executionList" -> PetExecutionStore(context).page(request.optInt("offset", 0))
            "taskList" -> UnifiedUnderstandingStore.open(context).use { data ->
                val offset = request.optInt("offset", 0).also { require(it >= 0) }
                val page = data.queue.listItems(100, offset)
                JSONObject().put("items", JSONArray().apply {
                    page.forEach { item ->
                        // The ledger exposes only a stable reference and a current text revision, never the
                        // material itself.  A later Agent read still has to pass its conversation authorization.
                        val sourceRecordId = data.queue.sharedRecordId(item.eventId)
                        val sourceRevision = sourceRecordId?.let { recordId ->
                            knowledge.findById(recordId)?.optString("text")?.takeIf { it.isNotBlank() }
                                ?.let(CompanionMemorySuppression::digest)
                        }
                        put(JSONObject()
                        .put("id", item.id).put("text", item.title).put("state", item.state)
                        .put("assessment", item.assessment?.let { JSONObject(it) } ?: JSONObject.NULL)
                        .put("sourceKind", if (item.eventId.startsWith("share:")) "shared_material" else "notification")
                        .put("sourcePreview", data.queue.sourceEvent(item.eventId)?.second?.take(500) ?: "")
                        .put("sourceRecordId", sourceRecordId ?: JSONObject.NULL)
                        .put("sourceRevision", sourceRevision ?: JSONObject.NULL)
                        .put("reminderStatus", item.reminderStatus).put("reminderAtMs", item.reminderAtMs ?: JSONObject.NULL)
                        .put("revision", CompanionTaskModel.revision(item)))
                    }
                }).put("nextOffset", if (page.size == 100) offset + page.size else JSONObject.NULL)
            }
            "taskDelete" -> {
                val id = text("id")
                val state = text("expectedState")
                val revision = text("expectedRevision")
                val deleted = UnifiedUnderstandingStore.open(context).use { it.queue.deleteItem(id, state, revision) }
                require(deleted) { "事项已变化，请刷新后重试" }
                runCatching { CompanionReminder(context).removeLinked(id) }
                success(true)
            }
            "taskUpdate" -> {
                val id = text("id")
                val expectedState = text("expectedState")
                val expectedRevision = text("expectedRevision")
                val next = request.optString("state").takeIf { it.isNotBlank() }
                val hasReminder = request.has("reminderAtMs") && !request.isNull("reminderAtMs")
                val reminderAtMs = when (val value = request.opt("reminderAtMs")) {
                    is Int -> value.toLong()
                    is Long -> value
                    is Double -> value.toLong().takeIf { it.toDouble() == value }
                    else -> null
                }
                require(expectedState in setOf("candidate", "open", "done", "cancelled")) { "事项状态无效" }
                require((next != null) xor hasReminder) { "请只选择一种事项操作" }
                require(next == null || next in setOf("open", "done", "cancelled")) { "事项状态无效" }
                require(!hasReminder || reminderAtMs != null) { "提醒时间无效" }
                val changed = UnifiedUnderstandingStore.open(context).use { data ->
                    val current = data.queue.item(id) ?: return@use null
                    require(current.state == expectedState && CompanionTaskModel.revision(current) == expectedRevision) { "事项已变化，请刷新后重试" }
                    val updated = if (reminderAtMs != null) data.queue.setItemReminder(id, expectedState, reminderAtMs, System.currentTimeMillis())
                    else data.queue.updateItem(id, expectedState, checkNotNull(next))
                    if (updated) data.queue.item(id) else null
                } ?: throw IllegalStateException("事项已变化，请刷新后重试")
                try {
                    if (reminderAtMs != null) CompanionReminder(context).upsertLinked(changed.id, changed.title, reminderAtMs, changed.reminderRevision)
                    else if (next in setOf("done", "cancelled")) CompanionReminder(context).removeLinked(id)
                } catch (e: Exception) {
                    if (reminderAtMs != null) runCatching { UnifiedUnderstandingStore.open(context).use { it.queue.disableItemReminder(changed.id, reminderAtMs, changed.reminderRevision) } }
                    throw IllegalStateException("事项已更新，但关联提醒未能安排；已撤销本次提醒状态", e)
                }
                success(true).put("revision", CompanionTaskModel.revision(changed))
            }
            "quietHoursSet" -> {
                val start = request.getInt("start"); val end = request.getInt("end")
                require(start in 0 until 1440 && end in 0 until 1440) { "时间无效" }
                val store = CompanionStore(context); store.quietHoursStart = start; store.quietHoursEnd = end
                JSONObject().put("ok", true)
            }
            "reminderAdd" -> { reminder.add(text("text"), request.getLong("at")); JSONObject().put("ok", true) }
            "reminderDelete" -> {
                val id = text("id"); val rows = reminder.list()
                val current = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
                require(current?.toString() == text("expected")) { "提醒已变化，请刷新" }
                reminder.remove(id); JSONObject().put("ok", true)
            }
            "recurringAdd" -> { recurring.add(text("text"), request.getInt("hour"), request.getInt("minute"), request.getInt("weekday")); JSONObject().put("ok", true) }
            "recurringEdit" -> success(recurring.edit(text("id"), text("expected"), text("text"), request.getInt("hour"), request.getInt("minute"), request.getInt("weekday")))
            "recurringDelete" -> success(recurring.removeReviewed(text("id"), text("expected")))
            "recurringRetry" -> { recurring.retry(text("id")); JSONObject().put("ok", true) }
            "previewImport" -> preview(text("kind"), text("contents"))
            "applyImport" -> apply(text("token"))
            else -> throw IllegalArgumentException("不支持的助手操作")
        }
    }

    fun export(kind: String): ByteArray = when (kind) {
        "memory" -> memory.exportBackup()
        "knowledge" -> knowledge.exportForUser()
        else -> throw IllegalArgumentException("不支持的备份类型")
    }
    private fun preview(kind: String, contents: String): JSONObject {
        val bytes = contents.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512_000) { "文件超过大小上限" }
        val token = UUID.randomUUID().toString()
        val entry = when (kind) {
            "memory" -> { val plan = memory.previewImport(bytes); Pending(kind, plan, null, System.currentTimeMillis()) }
            "knowledge" -> { val plan = knowledge.previewImport(bytes); Pending(kind, null, plan, System.currentTimeMillis()) }
            else -> throw IllegalArgumentException("不支持的备份类型")
        }
        synchronized(pending) { pending.clear(); pending[token] = entry }
        val additions = entry.memory?.additions ?: checkNotNull(entry.knowledge).additions
        val skipped = entry.memory?.skipped ?: checkNotNull(entry.knowledge).skipped
        return JSONObject().put("token", token).put("items", JSONArray(additions)).put("skipped", skipped)
    }
    private fun apply(token: String): JSONObject {
        val entry = synchronized(pending) { pending.remove(token) } ?: error("导入预览已失效，请重新选择文件")
        require(System.currentTimeMillis() - entry.at < 15 * 60_000) { "导入预览已过期" }
        val count = when (entry.kind) {
            "memory" -> memory.applyImport(checkNotNull(entry.memory))
            else -> knowledge.applyImport(checkNotNull(entry.knowledge))
        }
        return JSONObject().put("count", count)
    }
    private data class Pending(val kind: String, val memory: MemoryImportPlan?, val knowledge: KnowledgeImportPlan?, val at: Long)
    companion object { private val pending = mutableMapOf<String, Pending>() }
}
