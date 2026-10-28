package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.text.DateFormat
import java.util.Date
import java.util.Calendar

/** 权限与数据在此由用户直接控制；无需解锁或无障碍读取其他应用私有数据。 */
class CompanionActivity : AppCompatActivity() {
    private lateinit var store: CompanionStore
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val dark: Boolean get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink: Int get() = Color.parseColor(if (dark) "#E8F3FF" else "#173454")
    private val muted: Int get() = Color.parseColor(if (dark) "#ADC4D9" else "#526B82")
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun surface(color: String, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(16).toFloat()
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }
    private val memoryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val memoryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var memoryBusy = false
    private var notificationVisible = CompanionNotificationPaging.PAGE_SIZE
    @Volatile private var memoryCountGeneration = 0L
    private val briefExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "companion-brief").apply { isDaemon = true } }
    private var notificationDeleteBusy = false
    private var briefBusy = false
    private var usageBusy = false
    private val usageExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "companion-usage").apply { isDaemon = true } }
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        android.widget.Toast.makeText(this, if (granted) "已授权；可打开简报查看日历。" else "日历未授权；简报仍可查看通知。", android.widget.Toast.LENGTH_LONG).show()
    }
    private val readinessExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "companion-readiness").apply { isDaemon = true } }
    @Volatile private var readinessGeneration = 0L

    private fun <T> runMemoryIo(message: String, work: () -> T, done: (Result<T>) -> Unit) {
        if (memoryBusy || isFinishing || isDestroyed) {
            android.widget.Toast.makeText(this, "记忆文件操作正在进行，请稍候。", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        memoryBusy = true
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        memoryExecutor.execute {
            val result = runCatching(work)
            memoryHandler.post {
                if (!isFinishing && !isDestroyed) {
                    memoryBusy = false
                    done(result)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A confirmed write may finish; do not show its result on a destroyed activity.
        memoryCountGeneration++
        memoryExecutor.shutdown()
        briefExecutor.shutdownNow()
        usageExecutor.shutdownNow()
        readinessGeneration++
        readinessExecutor.shutdownNow()
    }

    private val memoryImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runMemoryIo("正在读取记忆文件…", {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) error("无法读取文件")
                        require(output.size() + count <= CompanionMemoryImport.MAX_BYTES) { "文件超过大小上限" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("无法读取文件")
                CompanionMemoryStore(this).previewImport(bytes)
            }) { result ->
            result.onSuccess { plan ->
                val rows = org.json.JSONArray(plan.additions)
                val details = (0 until rows.length()).joinToString("\n\n") { index -> "${index + 1}. ${rows.getJSONObject(index).getString("text")}" }
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("核对要导入的记忆")
                    .setMessage("将新增 ${rows.length()} 条，跳过 ${plan.skipped} 条已有 ID 或相同内容。现有记忆不会被覆盖。\n\n" + if (details.isEmpty()) "没有需要新增的记忆。" else details)
                    .setNegativeButton("取消", null).setPositiveButton("确认合并") { _, _ ->
                        runMemoryIo("正在合并记忆…", { CompanionMemoryStore(this).applyImport(plan) }) { outcome ->
                            outcome.onSuccess { android.widget.Toast.makeText(this, "已导入 $it 条记忆", android.widget.Toast.LENGTH_LONG).show() }
                                .onFailure { android.widget.Toast.makeText(this, "导入失败，原记忆已保留；请重新选择文件核对。", android.widget.Toast.LENGTH_LONG).show() }
                        }
                    }.show()
            }.onFailure {
                android.widget.Toast.makeText(this, "无法读取或验证记忆文件，原记忆已保留。", android.widget.Toast.LENGTH_LONG).show()
            }
            }
        }
    }

    private val memoryExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runMemoryIo("正在导出记忆…", {
                // Read and validate before opening the selected output, so a bad store cannot overwrite it.
                val bytes = CompanionMemoryStore(this).exportForUser()
                val stream = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开文件")
                stream.use { it.write(bytes); it.flush() }
            }) { result ->
                android.widget.Toast.makeText(this, if (result.isSuccess) "记忆已导出" else "导出失败，请检查保存位置；原记忆未改变。", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private val knowledgeImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runMemoryIo("正在读取资料备份…", {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) error("无法读取文件")
                    require(output.size() + count <= CompanionKnowledgeData.MAX_BYTES) { "备份超过大小上限" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("无法读取文件")
            CompanionKnowledgeStore(this).previewImport(bytes)
        }) { result ->
            result.onSuccess { plan ->
                val additions = org.json.JSONArray(plan.additions)
                val preview = (0 until minOf(additions.length(), 10)).joinToString("\n\n") { index ->
                    "${index + 1}. " + additions.getJSONObject(index).getString("text").replace('\n', ' ').take(120)
                }
                val suffix = if (additions.length() > 10) "\n仅预览前 10 条；确认将导入全部新增资料。" else ""
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("核对资料备份")
                    .setMessage("将新增 ${additions.length()} 条，跳过 ${plan.skipped} 条重复标识或内容；现有资料不会被覆盖。\n\n$preview$suffix")
                    .setNegativeButton("取消", null).setPositiveButton("确认合并") { _, _ ->
                        runMemoryIo("正在合并资料…", { CompanionKnowledgeStore(this).applyImport(plan) }) { outcome ->
                            android.widget.Toast.makeText(this, outcome.fold({ "已导入 $it 条资料。" }, { "导入失败或资料已变化，请重新选择文件核对。" }), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }.onFailure { android.widget.Toast.makeText(this, "无法读取或验证资料备份，原资料已保留。", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private val knowledgeTextFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runMemoryIo("正在读取文本文件…", {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) error("无法读取文件")
                    require(output.size() + count <= CompanionKnowledgeTextFile.MAX_FILE_BYTES) { "文件过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("无法读取文件")
            CompanionKnowledgeTextFile.decode(bytes)
        }) { result ->
            result.onSuccess { text ->
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("核对要导入的文本")
                    .setMessage("以下完整内容将保存到本地资料库。对话访问开启时，匹配内容可能发送给模型服务。\n\n$text")
                    .setNegativeButton("取消", null).setPositiveButton("保存资料") { _, _ ->
                        runMemoryIo("正在保存资料…", { CompanionKnowledgeStore(this).add(text, "text_file") }) { outcome ->
                            android.widget.Toast.makeText(this, outcome.fold({ if (it) "文本已保存。" else "资料库中已有相同内容。" }, { "保存失败，请检查资料库容量。" }), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.show()
            }.onFailure { android.widget.Toast.makeText(this, "无法读取文本：请选取不超过 8000 字的 UTF-8 文本文件。", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    private val knowledgeExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runMemoryIo("正在导出资料…", {
            val bytes = CompanionKnowledgeStore(this).exportForUser()
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("无法打开文件")
            stream.use { it.write(bytes); it.flush() }
        }) { result ->
            android.widget.Toast.makeText(this, if (result.isSuccess) "资料备份已导出。" else "导出失败，请检查保存位置；原资料未改变。", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (text.isBlank()) { paragraph("没有识别到语音，请重试或输入文字。"); return@registerForActivityResult }
        val edit = android.widget.EditText(this).apply { setText(text) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("识别结果")
            .setMessage("请核对结果；可填入当前对话草稿，不会自动发送。语音识别服务可能联网。")
            .setView(edit).setNegativeButton("取消", null)
            .setNeutralButton("复制") { _, _ -> copyVoice(edit.text.toString()) }
            .setPositiveButton("填入对话") { _, _ ->
                val value = edit.text.toString()
                if (!io.deepseekharness.mobile.MainHarnessSurface.stageCompanionVoice(value)) {
                    offerVoiceCopy(value)
                } else {
                    val opened = runCatching {
                        startActivity(Intent(this, io.deepseekharness.mobile.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }.isSuccess
                    if (!opened) { io.deepseekharness.mobile.MainHarnessSurface.cancelCompanionVoice(); offerVoiceCopy(value) }
                }
            }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CompanionRecurringReminder.requestRecovery(applicationContext)
        store = CompanionStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor(if (dark) "#0D1A2A" else "#F1F7FC"))
        }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(if (dark) "#0D1A2A" else "#F1F7FC"))
            addView(sectionNavigation(), LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume(); render()
        if (intent?.getBooleanExtra("open_voice_input", false) == true) {
            intent?.removeExtra("open_voice_input")
            startVoiceInput()
        } else if (intent?.getBooleanExtra("open_recurring_reminders", false) == true) {
            intent?.removeExtra("open_recurring_reminders")
            showRecurringReminders()
        } else if (intent?.getBooleanExtra("open_pending_memories", false) == true) {
            intent?.removeExtra("open_pending_memories")
            showMemories()
        } else if (intent?.getBooleanExtra("open_calendar_brief", false) == true) {
            intent?.removeExtra("open_calendar_brief")
            showBrief()
        } else if (intent?.getBooleanExtra("open_usage_suggestions", false) == true) {
            intent?.removeExtra("open_usage_suggestions")
            showUsageSuggestions()
        } else if (intent?.getBooleanExtra("open_memory_suggestions", false) == true) {
            intent?.removeExtra("open_memory_suggestions")
            showMemorySuggestions()
        } else if (intent?.getBooleanExtra("open_notification_candidates", false) == true) {
            intent?.removeExtra("open_notification_candidates")
            showNotificationCandidates()
        }
    }

    private fun render() {
        val previousScroll = scroll.scrollY
        content.removeAllViews()
        hero()
        readinessCard()
        heading("语音对话", "voice")
        paragraph("通知和使用数据默认在本机处理；单独开启对话访问后，相关内容可能发送给当前模型服务。系统和应用可能限制读取。")
        button("语音输入任务") { startVoiceInput() }
        paragraph("使用手机提供的语音识别服务；核对后可填入已打开的对话草稿，不自动发送；服务可能联网。")
        val memory = CompanionMemoryStore(this)
        heading("长期记忆", "memory")
        toggle("允许助手使用长期记忆", memory.enabled) { memory.enabled = it }
        paragraph("助手提出的记忆先等待你确认；已确认的记忆可供助手检索，相关内容可能发送给当前模型服务。记忆保存在应用私有存储，运行环境更新不会清空；卸载应用会删除。")
        val pendingStatus = TextView(this).apply {
            text = "正在读取记忆状态…"; textSize = 14f; setTextColor(muted); setPadding(dp(2), dp(6), dp(2), dp(8))
        }
        content.addView(pendingStatus)
        val countGeneration = ++memoryCountGeneration
        memoryExecutor.execute {
            val pendingCount = runCatching { memory.listForUser() }.getOrNull()?.let { rows ->
                (0 until rows.length()).count { rows.optJSONObject(it)?.optString("status") == "pending" }
            }
            memoryHandler.post {
                if (!isFinishing && !isDestroyed && countGeneration == memoryCountGeneration) {
                    pendingStatus.text = if (pendingCount == null) "无法读取长期记忆，原数据已保留。"
                        else if (pendingCount > 0) "有 $pendingCount 条记忆等待确认。" else "暂无待确认记忆。"
                }
            }
        }
        toggle("从通知中寻找记忆候选", store.notificationMemorySuggestions) { store.notificationMemorySuggestions = it; render() }
        paragraph("开启后在本机从已采集通知中寻找短句，供你核对；通知可能来自其他人，不能直接当作你的偏好。候选不会提供给模型，清除原通知记录后消失。")
        button("查看通知记忆候选") { showMemorySuggestions() }
        button("重置已忽略的通知记忆候选") { store.clearDismissedMemorySuggestions(); android.widget.Toast.makeText(this, "已重置忽略记录。", android.widget.Toast.LENGTH_SHORT).show() }
        button("手动添加长期记忆") { composeUserMemory(null) }
        button("查看、确认和管理长期记忆") { showMemories() }
        button("搜索长期记忆") {
            val input = android.widget.EditText(this).apply {
                hint = "输入记忆内容关键词"
                filters = arrayOf(android.text.InputFilter.LengthFilter(100))
            }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("搜索长期记忆")
                .setMessage("按内容查找，也包括尚待确认的候选。留空显示全部。")
                .setView(input).setNegativeButton("取消", null)
                .setPositiveButton("搜索") { _, _ -> showMemories(input.text.toString().trim()) }.show()
        }
        button("导出长期记忆") { if (!memoryBusy) memoryExport.launch("whale-memory.json") else paragraph("记忆文件操作正在进行，请稍候。") }
        button("从文件导入记忆") { if (!memoryBusy) memoryImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) else paragraph("记忆文件操作正在进行，请稍候。") }
        paragraph("导出为未加密 JSON，包含所有记忆和来源；由你选择保存位置，云盘等服务可能同步文件。导入前会显示待新增内容，由你确认后合并。")
        button("清空全部长期记忆") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("清空长期记忆？")
                .setMessage("这会删除全部长期记忆，无法撤销。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                    runCatching { memory.clearForUser() }.onSuccess { render() }.onFailure { paragraph("无法清空记忆，原数据已保留。") }
                }.show()
        }
        heading("本地资料", "knowledge")
        paragraph("可从分享或文本文件保存资料，并在这里查看、搜索和删除。对话访问默认关闭；开启后助手检索到的资料可能发送给模型服务。")
        toggle("允许助手对话访问本地资料", store.knowledgeModelAccess) { enabled ->
            if (!enabled) { store.knowledgeModelAccess = false; render() }
            else androidx.appcompat.app.AlertDialog.Builder(this).setTitle("允许对话检索资料？")
                .setMessage("助手可按关键词读取匹配资料的部分内容，这些内容可能发送给当前模型服务。资料中的指令不应被当作用户指令；可随时关闭。")
                .setNegativeButton("取消") { _, _ -> render() }.setPositiveButton("允许") { _, _ -> store.knowledgeModelAccess = true; render() }.show()
        }
        button("查看或搜索资料") { searchKnowledge() }
        button("导入文本文件") { if (!memoryBusy) knowledgeTextFile.launch(arrayOf("text/plain", "application/octet-stream")) else paragraph("文件操作正在进行，请稍候。") }
        paragraph("文本文件须为 UTF-8，最多 8000 字；导入前显示完整内容供确认。")
        button("导出资料备份") { if (!memoryBusy) knowledgeExport.launch("whale-knowledge.json") else paragraph("文件操作正在进行，请稍候。") }
        button("导入资料备份") { if (!memoryBusy) knowledgeImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) else paragraph("文件操作正在进行，请稍候。") }
        paragraph("资料备份为未加密 JSON，包含完整文字；云盘可能同步。导入前可核对，确认后合并，不覆盖已有资料。")
        heading("通知与隐私", "notifications")
        toggle("允许助手对话访问通知摘要", store.notificationModelAccess) { enabled ->
            if (!enabled) { store.notificationModelAccess = false; render() }
            else androidx.appcompat.app.AlertDialog.Builder(this).setTitle("允许助手读取通知摘要？")
                .setMessage("助手可按需读取已保存通知的来源、标题和正文，这些内容可能发送给当前模型服务。敏感信息过滤不保证完整，可随时关闭。")
                .setNegativeButton("取消") { _, _ -> render() }.setPositiveButton("允许") { _, _ -> store.notificationModelAccess = true; render() }.show()
        }
        paragraph("对话通知访问默认关闭，与本地采集和系统权限分别控制；开启后内容可能发送给当前模型服务。")
        toggle("通知到达时显示桌面气泡", store.notificationBubbles) { store.notificationBubbles = it }
        toggle("候选事项发送系统通知", store.candidateNotifications) { store.candidateNotifications = it }
        paragraph("本地规则识别可能的日程、取件或待回复，只显示通用提示；规则可能误判或遗漏。系统通知最多 30 分钟一次，需要通知权限，并遵守免打扰；不会自动回复或创建事项。")
        toggle("收集通知摘要", store.notificationCapture) { store.notificationCapture = it; render() }
        paragraph("开启后记录通知来源、标题和正文，最多 50 条；只在读取或新增时清理超过 7 天的记录。验证码与密码使用简单规则过滤，不能保证识别所有敏感内容。")
        val notificationAllowed = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        paragraph(if (notificationAllowed) "系统通知读取授权：已授予。系统可能停止服务或限制后台运行。" else "系统通知读取授权：未授予。开启采集后还需在系统设置中授权。")
        button("打开系统通知读取授权") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        button("设置不采集通知的应用") { selectExcludedApps() }
        paragraph("可从已记录过的应用中选择不再采集的来源；已有记录不会自动删除。")
        toggle("桌面休息提醒", store.reminders) { store.reminders = it }
        paragraph("悬浮助手运行且设备已解锁时，约每 90 分钟显示一次休息提示；低电量时可发系统通知（需通知权限）。系统可能停止后台服务。")
        heading("提醒与免打扰", "reminders")
        val reminderHealth = CompanionReminderHealth.read(this)
        paragraph(when (reminderHealth) {
            CompanionReminderHealth.State.READY -> "提醒通知：系统允许；省电或后台限制仍可能延迟送达。"
            CompanionReminderHealth.State.PERMISSION_DENIED -> "提醒通知：通知权限未授予，到期时可能无法送达。"
            CompanionReminderHealth.State.APP_DISABLED -> "提醒通知：应用通知已关闭，到期时可能无法送达。"
            CompanionReminderHealth.State.CHANNEL_DISABLED -> "提醒通知：鲸鱼娘提醒渠道已关闭，到期时可能无法送达。"
            CompanionReminderHealth.State.UNAVAILABLE -> "提醒通知状态暂时无法读取，请检查系统设置。"
        })
        button("打开提醒通知设置") {
            val intent = if (reminderHealth == CompanionReminderHealth.State.CHANNEL_DISABLED)
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CompanionReminderHealth.CHANNEL)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            runCatching { startActivity(intent) }.onFailure { paragraph("无法打开通知设置，请在系统设置中检查。") }
        }

        toggle("启用免打扰", store.quietHoursEnabled) { store.quietHoursEnabled = it; render() }
        paragraph("使用手机当前时区。时段内暂停桌面气泡、休息提示和低电量通知；通知仍可按采集设置保存，手动设置的事项提醒通知仍会送达。结束后不会补发被跳过的气泡。起止时间相同表示不限制。")
        fun timeLabel(value: Int) = "%02d:%02d".format(value / 60, value % 60)
        button("开始时间 · ${timeLabel(store.quietHoursStart)}") { chooseQuietTime(true) }
        button("结束时间 · ${timeLabel(store.quietHoursEnd)}") { chooseQuietTime(false) }
        heading("事项提醒")
        paragraph("手动设置一次性提醒；需要通知权限。系统省电可能导致延迟，重启后会恢复未来提醒，错过的不会自动补发。")
        button("新建提醒") { createReminder() }
        button("查看和删除提醒") { showReminders() }
        button("添加每天或每周提醒") { createRecurringReminder() }
        button("查看重复提醒") { showRecurringReminders() }
        toggle("重复提醒气泡显示事项内容", store.recurringBubbleDetails) { enabled ->
            if (!enabled) { store.recurringBubbleDetails = false; render() }
            else androidx.appcompat.app.AlertDialog.Builder(this).setTitle("在屏幕上显示提醒内容？")
                .setMessage("重复提醒触发时，鲸鱼娘气泡可显示事项摘要；附近的人或屏幕录制可能看到。不会因此自动发送到模型，可以随时关闭。")
                .setNegativeButton("取消") { _, _ -> render() }.setPositiveButton("允许") { _, _ -> store.recurringBubbleDetails = true; render() }.show()
        }
        paragraph("仅影响重复提醒气泡；默认显示通用提示。气泡遵守免打扰和屏幕锁定限制。")
        paragraph("按手机当前时区安排；系统省电可能延迟，重启时错过的次数不会集中补发。")
        heading("常用应用", "apps")
        val shortcuts = runCatching { store.shortcuts().sorted() }.getOrNull()
        if (shortcuts == null) paragraph("无法读取快捷方式，原数据已保留。")
        else if (shortcuts.isEmpty()) paragraph("还没有快捷方式；可从使用情况中手动选择。")
        shortcuts.orEmpty().forEach { pkg ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            button("打开 · $label") { openNotificationSource(pkg) }
        }
        if (!shortcuts.isNullOrEmpty()) button("管理常用应用") { manageShortcuts() }
        heading("手机使用情况")
        paragraph("只在点击时读取系统提供的最近 7 天应用使用时长，数据可能不完整；需要在系统中单独授予使用情况访问权限。可手动选择应用加入快捷方式，不保存使用时长。")
        button("打开使用情况访问授权") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        toggle("提醒我整理常用应用", store.usageSuggestions) { store.usageSuggestions = it }
        paragraph("开启后，悬浮助手运行且解锁时，在本机按最近约 7 天的使用时长建议快捷方式，最多每天提示一次；不记录或发送使用数据，不推断个人属性。需要使用情况访问权限，系统可能限制后台提示。")
        button("查看常用应用建议") { showUsageSuggestions() }
        button("查看最近 7 天使用情况") { showUsage() }
        heading("日历与本地简报", "brief")
        paragraph("简报使用本地规则分类，可能漏判或分类错误，不代表已检查所有信息。")
        toggle("每天提示查看简报", store.dailyBriefEnabled) { store.dailyBriefEnabled = it; render() }
        val briefHour = store.dailyBriefMinute / 60
        val briefMinute = store.dailyBriefMinute % 60
        button("设置简报提示时间：${"%02d:%02d".format(briefHour, briefMinute)}") {
            android.app.TimePickerDialog(this, { _, hour, minute -> store.dailyBriefMinute = hour * 60 + minute; render() }, briefHour, briefMinute, true).show()
        }
        paragraph("默认关闭。悬浮助手运行、手机解锁且不在免打扰时，约每 5 分钟检查；在所选时间后 2 小时内最多显示一次通用气泡。点击后才打开本地简报；系统限制可能导致错过，不会补发。")
        paragraph("日历只在打开简报时临时读取当天可见日历，不保存，也不会自动发送给模型；是否显示取决于系统授权。")
        toggle("允许助手对话访问今日日历", store.calendarModelAccess) { enabled ->
            if (!enabled) { store.calendarModelAccess = false; render() }
            else androidx.appcompat.app.AlertDialog.Builder(this).setTitle("允许助手读取日历？")
                .setMessage("助手在你询问日程时可读取当天可见日历的标题与时间，内容可能发送给当前模型服务。系统日历权限仍需单独授权，可随时关闭此开关。")
                .setNegativeButton("取消") { _, _ -> render() }.setPositiveButton("允许") { _, _ -> store.calendarModelAccess = true; render() }.show()
        }
        paragraph("对话日历访问默认关闭，独立于本地简报；开启后标题和时间可能发送给当前模型服务。")
        button("授权读取日历") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("读取日历用于简报")
                .setMessage("授权后，仅在你打开简报时读取当天可见日历；不会修改日程或自动发送给模型。可随时在系统设置撤销。")
                .setNegativeButton("取消", null).setPositiveButton("继续") { _, _ -> calendarPermission.launch(android.Manifest.permission.READ_CALENDAR) }.show()
        }
        toggle("日程临近时显示桌面气泡", store.calendarHints) { store.calendarHints = it }
        paragraph("仅悬浮助手运行且手机解锁时，大约每 5 分钟检查未来 15 分钟内的非全天日程，气泡不显示标题。需要日历读取授权，遵守免打扰；系统省电或服务停止可能导致漏提醒。")
        button("清除日程气泡去重记录") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("清除日程气泡去重记录？")
                .setMessage("这只清除本机保存的日程标识摘要；仍在提醒时间范围内的日程可能再次出现气泡。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    val ok = getSharedPreferences("companion_calendar_hints_v1", Context.MODE_PRIVATE).edit().remove("shown").commit()
                    android.widget.Toast.makeText(this, if (ok) "去重记录已清除。" else "清除失败，请重试。", android.widget.Toast.LENGTH_SHORT).show()
                }.show()
        }
        button("查看本地简报") { showBrief() }
        button("查看通知候选事项") { showNotificationCandidates() }
        paragraph("本地规则仅提供待核对的候选，不会自动安排提醒或发送回复。")
        button("查看通知习惯摘要") { showNotificationSummary() }
        button("复制通知习惯摘要") { copyNotificationSummary() }
        button("从通知整理记忆") { chooseNotificationForMemory() }
        paragraph("选择一条本地通知作为参考，自行填写并确认要记住的事实；不会自动保存通知原文。")
        button("刷新通知记录") { notificationVisible = CompanionNotificationPaging.PAGE_SIZE; renderRecords() }
        button("按应用清除通知记录") { clearNotificationsForApp() }
        button("清除全部本地通知记录") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("清除全部通知记录？")
                .setMessage("此操作无法撤销，包括可能损坏的记录。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> store.clear(); render() }.show()
        }
        renderRecords()
        button("返回") { finish() }
        scroll.post { if (!isFinishing && !isDestroyed) scroll.scrollTo(0, previousScroll) }
    }

    private fun readinessCard() {
        heading("助手能力状态", "status")
        val status = TextView(this).apply {
            text = "正在读取状态…"; textSize = 14f; setTextColor(ink)
            setLineSpacing(dp(5).toFloat(), 1f); setPadding(dp(16), dp(14), dp(16), dp(14))
            background = surface(if (dark) "#172B40" else "#FFFFFF", if (dark) "#294660" else "#DCEAF5")
        }
        content.addView(status)
        val generation = ++readinessGeneration
        val app = applicationContext
        readinessExecutor.execute {
            if (generation != readinessGeneration) return@execute
            val facts = CompanionReadinessFacts(
                runCatching { Settings.canDrawOverlays(app) }.getOrNull(), io.deepseekharness.mobile.OverlayBallService.isRunning,
                runCatching { androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName) }.getOrNull(),
                runCatching { androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled() }.getOrNull(),
                runCatching { io.deepseekharness.mobile.accessibility.AccessibilityAutomationStore.serviceStatus(app) }.getOrNull(),
                runCatching { app.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) }.getOrNull(),
                runCatching { io.deepseekharness.mobile.runtime.RuntimeHost.controllerOrNull()?.shizukuState() }.getOrNull(),
            )
            val text = CompanionReadiness.describe(facts)
            memoryHandler.post { if (!isFinishing && !isDestroyed && generation == readinessGeneration) status.text = text }
        }
        button("打开悬浮窗权限") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName"))) }
                .onFailure { paragraph("无法打开悬浮窗设置，请手动前往系统设置。") }
        }
        button("打开无障碍设置") { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        button("打开应用通知设置") {
            runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                .onFailure { paragraph("无法打开通知设置，请手动前往系统设置。") }
        }
        button("打开电池优化设置") { openSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        button("打开 Shizuku") { openNotificationSource("moe.shizuku.privileged.api") }
        paragraph("状态是当前取样，不保证后台常驻、提醒准时或任务执行成功。")
    }

    private fun chooseQuietTime(start: Boolean) {
        val current = if (start) store.quietHoursStart else store.quietHoursEnd
        android.app.TimePickerDialog(this, { _, hour, minute ->
            if (start) store.quietHoursStart = hour * 60 + minute else store.quietHoursEnd = hour * 60 + minute
            render()
        }, current / 60, current % 60, true).show()
    }

    private fun showUsageSuggestions() {
        if (usageBusy) { android.widget.Toast.makeText(this, "正在读取使用情况，请稍候。", android.widget.Toast.LENGTH_SHORT).show(); return }
        usageBusy = true
        val app = applicationContext
        val existing = runCatching { store.shortcuts() }.getOrElse { usageBusy = false; paragraph("无法读取快捷方式，原数据已保留。"); return }
        if (existing.size >= CompanionShortcutPolicy.MAX_SHORTCUTS) { usageBusy = false; paragraph("常用应用已满，请先移除一个快捷方式。"); return }
        briefExecutor.execute {
            val snapshot = CompanionUsage.load(app, existing)
            memoryHandler.post {
                usageBusy = false
                if (isFinishing || isDestroyed) return@post
                when (snapshot.state) {
                    CompanionUsage.State.NO_PERMISSION -> paragraph("没有使用情况访问权限，请先到系统设置中授权。")
                    CompanionUsage.State.UNAVAILABLE -> paragraph("无法读取使用情况，请稍后重试。")
                    CompanionUsage.State.OK -> {
                        if (snapshot.candidates.isEmpty()) { paragraph("没有足够的可用记录或新建议；系统统计可能不完整。"); return@post }
                        val labels = snapshot.candidates.map { candidate ->
                            val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(candidate.packageName, 0)).toString() }.getOrDefault(candidate.packageName)
                            "$name · 约 ${candidate.foregroundMs / 60000} 分钟\n${candidate.packageName}"
                        }.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("建议加入常用应用（约 7 天）")
                            .setItems(labels) { _, index ->
                                val candidate = snapshot.candidates[index]
                                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("加入常用应用？").setMessage(labels[index])
                                    .setNegativeButton("取消", null).setPositiveButton("加入") { _, _ ->
                                        val launch = runCatching { packageManager.getLaunchIntentForPackage(candidate.packageName) }.getOrNull()
                                        if (!CompanionSourcePolicy.matchesResolved(candidate.packageName, launch?.component?.packageName)) {
                                            paragraph("应用已不可用或没有启动入口，请刷新建议。")
                                        } else runCatching { store.addShortcut(candidate.packageName) }.onSuccess { render() }
                                            .onFailure { paragraph("无法添加快捷方式，请检查是否已达到 6 个上限。") }
                                    }.show()
                            }.setNegativeButton("关闭", null).show()
                    }
                }
            }
        }
    }

    private fun showUsage() {
        if (usageBusy) { android.widget.Toast.makeText(this, "正在读取使用情况，请稍候。", android.widget.Toast.LENGTH_SHORT).show(); return }
        usageBusy = true
        android.widget.Toast.makeText(this, "正在读取使用情况…", android.widget.Toast.LENGTH_SHORT).show()
        val app = applicationContext
        usageExecutor.execute {
            val snapshot = CompanionUsage.loadSummary(app)
            val labels = snapshot.candidates.map { candidate ->
                val label = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(candidate.packageName, 0)).toString() }.getOrDefault(candidate.packageName)
                "$label · 约 ${candidate.foregroundMs / 60000} 分钟\n${candidate.packageName}"
            }.toTypedArray()
            memoryHandler.post {
                usageBusy = false
                if (isFinishing || isDestroyed) return@post
                when (snapshot.state) {
                    CompanionUsage.State.NO_PERMISSION -> android.widget.Toast.makeText(this, "没有使用情况访问权限，请先到系统设置中授权。", android.widget.Toast.LENGTH_LONG).show()
                    CompanionUsage.State.UNAVAILABLE -> android.widget.Toast.makeText(this, "系统使用情况暂不可用。", android.widget.Toast.LENGTH_LONG).show()
                    CompanionUsage.State.OK -> {
                        if (labels.isEmpty()) { android.widget.Toast.makeText(this, "系统没有返回可用的使用记录。", android.widget.Toast.LENGTH_LONG).show(); return@post }
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("约 7 天使用情况（选择应用）")
                            .setItems(labels) { _, index ->
                                val pkg = snapshot.candidates[index].packageName
                                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("加入常用应用？").setMessage(labels[index] + "\n\n系统统计可能不完整；时长是近似值。")
                                    .setNegativeButton("取消", null).setPositiveButton("加入") { _, _ ->
                                        val launch = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
                                        if (!CompanionSourcePolicy.matchesResolved(pkg, launch?.component?.packageName)) {
                                            android.widget.Toast.makeText(this, "这个应用没有可用的启动入口。", android.widget.Toast.LENGTH_LONG).show()
                                        } else runCatching { store.addShortcut(pkg) }.onSuccess { render() }
                                            .onFailure { android.widget.Toast.makeText(this, "无法添加快捷方式，请检查是否已达到 6 个上限。", android.widget.Toast.LENGTH_LONG).show() }
                                    }.show()
                            }.setNegativeButton("关闭", null).show()
                    }
                }
            }
        }
    }

    private fun manageShortcuts() {
        val shortcuts = runCatching { store.shortcuts().sorted() }.getOrElse { paragraph("无法读取快捷方式。") ; return }
        if (shortcuts.isEmpty()) { paragraph("没有快捷方式。") ; return }
        val labels = shortcuts.map { pkg ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            "$label\n$pkg"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择要移除的快捷方式")
            .setItems(labels) { _, index ->
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("移除快捷方式？").setMessage(labels[index])
                    .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                        runCatching { store.removeShortcut(shortcuts[index]) }.onSuccess { render() }.onFailure { paragraph("无法移除快捷方式。") }
                    }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun copyVoice(text: String) {
        getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("语音输入", text))
    }
    private fun offerVoiceCopy(text: String) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("无法填入对话")
            .setMessage("当前没有可用的对话输入框；可以复制结果后自行粘贴。")
            .setNegativeButton("取消", null).setPositiveButton("复制") { _, _ -> copyVoice(text) }.show()
    }

    private fun startVoiceInput() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说出要交给助手的任务")
        runCatching { voiceInput.launch(intent) }.onFailure { paragraph("系统没有可用的语音识别服务，请使用文字输入。") }
    }

    private fun recordsOrNull(): org.json.JSONArray? = runCatching { store.read() }.getOrElse {
        paragraph("通知记录无法读取，原数据已保留；新通知暂不写入。")
        null
    }

    private fun clearNotificationsForApp() {
        if (notificationDeleteBusy) { paragraph("正在清除通知记录，请稍候。"); return }
        val rows = recordsOrNull() ?: return
        val counts = (0 until rows.length()).map { rows.getJSONObject(it).getString("package") }
            .filter(CompanionSourcePolicy::validPackage).groupingBy { it }.eachCount().toSortedMap()
        if (counts.isEmpty()) { paragraph("暂无可按应用清除的通知记录。"); return }
        val packages = counts.keys.toList()
        val labels = packages.map { pkg ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            "$label ($pkg) · ${counts.getValue(pkg)} 条"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择要清除记录的应用")
            .setItems(labels) { _, index ->
                val pkg = packages[index]
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("清除该应用的通知记录？")
                    .setMessage("将清除执行时该应用已保存的所有本地通知。已确认的长期记忆和提醒不会删除；如果仍开启采集，以后的通知仍可能保存。")
                    .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                        if (!notificationDeleteBusy) {
                            notificationDeleteBusy = true
                            android.widget.Toast.makeText(this, "正在清除…", android.widget.Toast.LENGTH_SHORT).show()
                            briefExecutor.execute {
                                val result = runCatching { store.removeRecordsForPackage(pkg) }
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) {
                                        notificationDeleteBusy = false
                                        android.widget.Toast.makeText(this, result.fold({ "已清除 $it 条通知记录。" }, { "未能确认清除结果，请重新查看。" }), android.widget.Toast.LENGTH_LONG).show()
                                        renderRecords()
                                    }
                                }
                            }
                        }
                    }.show()
            }.setNegativeButton("取消", null).show()
    }

    private fun searchKnowledge() {
        val input = android.widget.EditText(this).apply {
            hint = "搜索文字，留空查看全部"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("搜索本地资料")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("搜索") { _, _ ->
                val query = input.text.toString()
                runMemoryIo("正在读取本地资料…", { CompanionKnowledgeStore(this).search(query) }) { result ->
                    result.onSuccess { rows -> showKnowledgeResults(rows) }
                        .onFailure { paragraph("资料库无法读取，原数据已保留。") }
                }
            }.show()
    }

    private fun showKnowledgeResults(rows: org.json.JSONArray) {
        if (rows.length() == 0) { paragraph("没有匹配的本地资料。") ; return }
        val labels = Array(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.getLong("createdAt")))
            val excerpt = row.getString("text").replace('\n', ' ').take(100)
            val source = if (row.getString("source") == "text_file") "文本文件" else "分享文字"
            "$date · $source\n$excerpt"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("本地资料（${rows.length()} 条）")
            .setItems(labels) { _, index ->
                val row = rows.getJSONObject(index)
                val text = row.getString("text")
                val id = row.getString("id")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("资料内容")
                    .setMessage(text).setNegativeButton("关闭", null)
                    .setNeutralButton("使用资料") { _, _ ->
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择操作")
                            .setItems(arrayOf("放入对话草稿", "复制资料", "编辑本地资料")) { _, choice ->
                                when (choice) {
                                    0 -> prepareKnowledgeForChat(text)
                                    1 -> getSystemService(android.content.ClipboardManager::class.java)
                                        ?.setPrimaryClip(android.content.ClipData.newPlainText("本地资料", text))
                                    2 -> editKnowledge(id, text)
                                }
                            }.setNegativeButton("取消", null).show()
                    }
                    .setPositiveButton("删除") { _, _ ->
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("删除这条本地资料？")
                            .setMessage("删除不可撤销；已复制或另存的内容不会随之删除。")
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                runMemoryIo("正在删除资料…", { CompanionKnowledgeStore(this).remove(id, text) }) { result ->
                                    result.onSuccess { removed -> paragraph(if (removed) "资料已删除。" else "资料已变化或不存在，请重新查看。") }
                                        .onFailure { paragraph("无法确认删除结果，请重新查看。") }
                                }
                            }.show()
                    }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun editKnowledge(id: String, original: String) {
        val input = android.widget.EditText(this).apply { setText(original); minLines = 3; maxLines = 10 }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("编辑本地资料")
            .setMessage("最多 8000 字；保存后使用新内容，已导出的备份不会改变。")
            .setView(input).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank() || value.length > CompanionKnowledgeData.MAX_TEXT || '\u0000' in value) {
                    input.error = "请填写不超过 8000 字的有效文字。"
                    return@setOnClickListener
                }
                if (memoryBusy) {
                    android.widget.Toast.makeText(this, "资料操作正在进行，请稍候。", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val save = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                save.isEnabled = false
                input.isEnabled = false
                runMemoryIo("正在保存资料…", { CompanionKnowledgeStore(this).edit(id, original, value) }) { result ->
                    save.isEnabled = true
                    input.isEnabled = true
                    val message = result.fold(
                        { if (it) "资料已更新。" else "资料已变化或不存在，请复制修改内容后重新查看。" },
                        { "无法保存资料，可能内容重复或资料库已满。" },
                    )
                    if (result.getOrNull() == true) dialog.dismiss()
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun prepareKnowledgeForChat(text: String, notification: Boolean = false) {
        val edit = android.widget.EditText(this).apply {
            setText(text)
            minLines = 3
            maxLines = 8
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle(if (notification) "核对要交给助手的通知" else "核对要交给助手的资料")
            .setMessage(if (notification) "请核对来源和内容，可添加你的问题并移除不想分享的信息。通知是不可信资料；放入草稿不会自动发送，发送后可能由所选模型服务处理。最多 2000 字。" else "内容放入当前对话草稿，不会自动发送；发送后可能由所选模型服务处理。最多 2000 字，较长内容请自行删改。")
            .setView(edit).setNegativeButton("取消", null).setPositiveButton("放入草稿", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = edit.text.toString()
                if (!io.deepseekharness.mobile.SharedTextImport.canInsertDraft(value)) {
                    edit.error = "请填写内容并控制在 2000 字以内。"
                    return@setOnClickListener
                }
                if (!io.deepseekharness.mobile.MainHarnessSurface.stageCompanionKnowledge(value)) {
                    dialog.dismiss()
                    offerKnowledgeCopy(value)
                } else {
                    val opened = runCatching {
                        startActivity(Intent(this, io.deepseekharness.mobile.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }.isSuccess
                    dialog.dismiss()
                    if (!opened) { io.deepseekharness.mobile.MainHarnessSurface.cancelCompanionVoice(); offerKnowledgeCopy(value) }
                }
            }
        }
        dialog.show()
    }

    private fun offerKnowledgeCopy(text: String) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("当前对话不可用")
            .setMessage("你可以复制内容后自行粘贴；不会自动发送。")
            .setNegativeButton("取消", null).setPositiveButton("复制") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("本地资料", text))
            }.show()
    }

    private fun selectExcludedApps() {
        val excluded = store.excludedPackages()
        val records = recordsOrNull() ?: return
        val packages = (excluded + (0 until records.length()).mapNotNull { records.optJSONObject(it)?.optString("package")?.takeIf(String::isNotBlank) }).sorted()
        if (packages.isEmpty()) { paragraph("暂无可选择的通知来源。开启采集并收到通知后可在这里设置。 "); return }
        val checked = BooleanArray(packages.size) { packages[it] in excluded }
        val labels = packages.map { pkg ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            "$label ($pkg)"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择不采集的应用")
            .setMultiChoiceItems(labels, checked) { _, index, value -> checked[index] = value }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> store.setExcludedPackages(packages.filterIndexed { index, _ -> checked[index] }.toSet()) }
            .show()
    }

    private fun createReminder(initialText: String = "", suggestedTime: Long? = null, suggestionNote: String = "通知含明确日期时间，已作为本地时区的选择建议；请核对日期、时区和具体时间。") {
        val input = android.widget.EditText(this).apply { hint = "提醒内容"; setSingleLine(true); setText(initialText.take(200)) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("新建提醒").setView(input)
        val notes = mutableListOf<String>()
        if (initialText.length > 200) notes.add("原摘要超过 200 字，已截取前 200 字；请核对和编辑提醒内容。")
        if (suggestedTime != null) notes.add(suggestionNote)
        if (notes.isNotEmpty()) dialog.setMessage(notes.joinToString("\n"))
        dialog.setNegativeButton("取消", null).setPositiveButton("选择时间") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank()) { paragraph("提醒内容不能为空。"); return@setPositiveButton }
                val selected = Calendar.getInstance().apply { if (suggestedTime != null) timeInMillis = suggestedTime else add(Calendar.MINUTE, 5) }
                android.app.DatePickerDialog(this, { _, year, month, day ->
                    selected.set(Calendar.YEAR, year); selected.set(Calendar.MONTH, month); selected.set(Calendar.DAY_OF_MONTH, day)
                    android.app.TimePickerDialog(this, { _, hour, minute ->
                        selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute); selected.set(Calendar.SECOND, 0); selected.set(Calendar.MILLISECOND, 0)
                        runCatching { CompanionReminder(this).add(value, selected.timeInMillis) }
                            .onSuccess {
                                val health = CompanionReminderHealth.read(this)
                                paragraph(if (health == CompanionReminderHealth.State.READY) "提醒已安排；系统可能延迟投递。"
                                    else "提醒已安排，但通知权限或状态未就绪，请检查提醒通知设置；到期时可能无法送达。")
                            }
                            .onFailure { paragraph("无法安排提醒：${it.message ?: "请检查系统设置"}") }
                    }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show()
                }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show()
            }.show()
    }

    private fun createRecurringReminder() {
        val input = android.widget.EditText(this).apply { hint = "提醒内容"; setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("新建重复提醒").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("选择重复方式") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank() || CompanionStore.sensitive(text)) { paragraph("提醒内容为空或可能含敏感信息。") ; return@setPositiveButton }
                val days = arrayOf("每天", "每周一", "每周二", "每周三", "每周四", "每周五", "每周六", "每周日")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("重复方式").setItems(days) { _, weekday ->
                    val now = Calendar.getInstance()
                    android.app.TimePickerDialog(this, { _, hour, minute ->
                        runCatching { CompanionRecurringReminder(this).add(text, hour, minute, weekday) }
                            .onSuccess {
                                val state = CompanionReminderHealth.read(this)
                                paragraph(if (state == CompanionReminderHealth.State.READY) "重复提醒已安排；系统可能延迟送达。" else "重复提醒已安排，但通知状态未就绪，请检查系统设置。")
                            }.onFailure { paragraph("无法安排重复提醒：${it.message ?: "请检查系统设置"}") }
                    }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                }.setNegativeButton("取消", null).show()
            }.show()
    }

    private fun showRecurringReminders() {
        val reminders = CompanionRecurringReminder(this)
        val rows = runCatching { reminders.list() }.getOrElse { paragraph("无法读取重复提醒，原数据已保留。") ; return }
        if (rows.length() == 0) { paragraph("暂无重复提醒。") ; return }
        val days = arrayOf("每天", "每周一", "每周二", "每周三", "每周四", "每周五", "每周六", "每周日")
        val labels = Array(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val next = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.getLong("nextAt")))
            val state = when {
                row.optString("state") != "scheduled" -> "安排失败"
                row.optBoolean("schedulePending", false) -> "等待恢复安排"
                row.getLong("nextAt") <= System.currentTimeMillis() -> "计划时间已过，状态待核对"
                else -> "已安排"
            }
            val last = when (row.optString("last")) { "submitted" -> "上次已交给系统"; "failed" -> "上次投递失败"; "missed" -> "上次已错过"; "pending" -> "上次状态未确认"; else -> "尚无记录" }
            "${row.getString("text")} · ${days[row.getInt("weekday")]} ${"%02d:%02d".format(row.getInt("hour"), row.getInt("minute"))}\n$state · 下次 $next · $last"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择重复提醒").setItems(labels) { _, index ->
            val id = rows.getJSONObject(index).getString("id")
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("管理重复提醒").setMessage(labels[index] + "\n删除后不会再安排下一次。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("删除重复提醒？").setMessage(labels[index])
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            runCatching { reminders.remove(id) }.onSuccess { paragraph("重复提醒已删除。") }.onFailure { paragraph("删除失败，原记录已保留。") }
                        }.show()
                }
            dialog.setNeutralButton("更多操作") { _, _ ->
                val failed = rows.getJSONObject(index).optString("state") == "failed"
                val options = if (failed) arrayOf("稍后再提醒一次", "编辑重复提醒", "重新安排重复提醒") else arrayOf("稍后再提醒一次", "编辑重复提醒")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择操作").setItems(options) { _, choice ->
                    when (choice) {
                        0 -> chooseSnooze(rows.getJSONObject(index).getString("text"))
                        1 -> editRecurringReminder(rows.getJSONObject(index))
                        2 -> runCatching { reminders.retry(id) }.onSuccess { paragraph("已重新安排下一次提醒。") }.onFailure { paragraph("重新安排失败，请检查系统设置。") }
                    }
                }.setNegativeButton("取消", null).show()
            }
            dialog.show()
        }.setNegativeButton("关闭", null).show()
    }

    private fun editRecurringReminder(row: org.json.JSONObject) {
        val id = row.getString("id")
        val expected = row.toString()
        val input = android.widget.EditText(this).apply { setText(row.getString("text")); setSingleLine(true); filters = arrayOf(android.text.InputFilter.LengthFilter(200)) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("编辑重复提醒").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("选择重复方式") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank() || CompanionStore.sensitive(text)) { android.widget.Toast.makeText(this, "提醒内容为空或可能含敏感信息。", android.widget.Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val days = arrayOf("每天", "每周一", "每周二", "每周三", "每周四", "每周五", "每周六", "每周日")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择重复方式")
                    .setItems(days) { _, weekday ->
                        android.app.TimePickerDialog(this, { _, hour, minute ->
                            runMemoryIo("正在更新重复提醒…", { CompanionRecurringReminder(this).edit(id, expected, text, hour, minute, weekday) }) { result ->
                                val message = result.fold(
                                    { if (it) "重复提醒已更新；系统可能延迟。" else "提醒已变化或不存在，请重新查看。" },
                                    { "未能确认更新结果，请查看提醒列表再操作。" },
                                )
                                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }, row.getInt("hour"), row.getInt("minute"), true).show()
                    }.setNegativeButton("取消", null).show()
            }.show()
    }

    private fun showReminders() {
        val reminders = CompanionReminder(this)
        val rows = runCatching { reminders.list() }.getOrElse { paragraph("无法读取提醒，原数据已保留。"); return }
        if (rows.length() == 0) { paragraph("暂无提醒。"); return }
        val labels = Array(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("at")))
            val state = when (row.optString("state")) { "scheduled" -> "已安排"; "delivered" -> "已交给系统"; "missed" -> "已错过"; else -> "失败" }
            "${row.optString("text")} · $date · $state"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择提醒").setItems(labels) { _, index ->
            val row = rows.getJSONObject(index)
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("管理提醒")
                .setMessage(labels[index] + "\n\n再次提醒会创建一条新提醒，原记录仍保留；如原提醒仍在等待，可能收到两次。")
                .setNegativeButton("关闭", null)
                .setNeutralButton("再次提醒") { _, _ -> chooseSnooze(row.optString("text")) }
                .setPositiveButton("删除") { _, _ ->
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("删除提醒？").setMessage(labels[index])
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            runCatching { reminders.remove(row.getString("id")) }.onFailure { paragraph("删除失败：${it.message}") }
                        }.show()
                }.show()
        }.setNegativeButton("关闭", null).show()
    }

    private fun chooseSnooze(text: String) {
        val options = arrayOf("10 分钟后", "30 分钟后", "1 小时后", "选择其他时间")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("新建一次性提醒，原计划不变")
            .setItems(options) { _, choice ->
                if (choice == 3) { createReminder(text); return@setItems }
                val minutes = CompanionSnoozePolicy.minutes[choice]
                runMemoryIo("正在安排提醒…", {
                    val target = CompanionSnoozePolicy.target(System.currentTimeMillis(), minutes)
                    CompanionReminder(this).add(text, target)
                    target
                }) { result ->
                    result.onSuccess { target ->
                        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(target))
                        val health = CompanionReminderHealth.read(this)
                        paragraph(if (health == CompanionReminderHealth.State.READY) "已新建 $time 的提醒，系统可能延迟。"
                            else "已新建 $time 的提醒，但通知状态未就绪，请检查系统设置。")
                    }.onFailure { paragraph("未能确认提醒是否安排；请先查看提醒列表，避免重复创建。") }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun renderRecords() {
        val previousScroll = scroll.scrollY
        // Rebuild to avoid appending duplicate lists on repeated refresh.
        if (content.findViewWithTag<LinearLayout>("records") != null) content.removeView(content.findViewWithTag("records"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "records" }
        val records = recordsOrNull() ?: return
        if (records.length() == 0) list.addView(TextView(this).apply { text = "暂无记录"; setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8)) })
        val page = CompanionNotificationPaging.indices(records.length(), notificationVisible)
        if (records.length() > 0) list.addView(TextView(this).apply {
            text = "显示 ${page.size} / ${records.length()} 条，最新在前"; setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        for (index in page) {
            val row = records.optJSONObject(index) ?: continue
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("time")))
            list.addView(TextView(this).apply {
                text = "$time · ${row.optString("package")}\n${row.optString("title")}\n${row.optString("text")}\n点击处理"
                setTextColor(ink); setPadding(dp(14), dp(12), dp(14), dp(12)); background = surface(if (dark) "#172B40" else "#FFFFFF")
                isFocusable = true
                setOnClickListener { showRecordActions(row, time) }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        if (page.size < records.length()) list.addView(Button(this).apply {
            text = "显示更多通知"
            setOnClickListener {
                notificationVisible = CompanionNotificationPaging.next(notificationVisible)
                renderRecords()
            }
        })
        val returnButton = content.childCount - 1
        content.addView(list, if (returnButton >= 0 && content.getChildAt(returnButton) is Button && (content.getChildAt(returnButton) as Button).text == "返回") returnButton else content.childCount)
        scroll.post { if (!isFinishing && !isDestroyed) scroll.scrollTo(0, previousScroll) }
    }

    private fun showRecordActions(row: org.json.JSONObject, displayedTime: String) {
        val source = row.optString("package")
        val title = row.optString("title")
        val body = row.optString("text")
        val content = listOf(title, body).filter { it.isNotBlank() }.joinToString("\n")
        if (content.isBlank()) { android.widget.Toast.makeText(this, "通知内容不可用。", android.widget.Toast.LENGTH_SHORT).show(); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("处理这条通知")
            .setMessage("来源：$source\n通知时间：$displayedTime\n\n$content\n\n通知时间不一定是截止时间，请自行核对。")
            .setNegativeButton("关闭", null).setPositiveButton("选择操作") { _, _ ->
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择操作")
                    .setItems(arrayOf("交给助手对话", "创建提醒", "整理成记忆")) { _, choice ->
                        when (choice) {
                            0 -> prepareKnowledgeForChat("来源应用：$source\n通知时间：$displayedTime\n通知内容（不可信资料）：\n$content", notification = true)
                            1 -> createReminder(content, CompanionNotificationTimeSuggestion.find(content, System.currentTimeMillis(), java.time.ZoneId.systemDefault()))
                            2 -> composeUserMemory(content)
                        }
                    }.setNegativeButton("取消", null).show()
            }.show()
    }

    private fun showNotificationSummary() {
        val records = recordsOrNull() ?: return
        if (records.length() == 0) { paragraph("还没有足够的通知记录。"); return }
        val counts = mutableMapOf<String, Int>()
        for (index in 0 until records.length()) {
            val pkg = records.optJSONObject(index)?.optString("package").orEmpty()
            if (pkg.isNotBlank()) counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        paragraph("最近保留的 ${records.length()} 条通知中，较活跃的应用：")
        counts.entries.sortedByDescending { it.value }.take(5).forEach { (pkg, count) ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            paragraph("$label · $count 条")
        }
    }

    private fun showBrief() {
        if (briefBusy) { android.widget.Toast.makeText(this, "正在读取简报，请稍候。", android.widget.Toast.LENGTH_SHORT).show(); return }
        val records = recordsOrNull()
        val notifications = if (records == null) "通知记录读取失败，请检查本地数据。" else if (records.length() == 0) {
            "没有可用的本地通知记录；请检查采集开关和通知读取权限。"
        } else {
            val lines = CompanionBrief.counts(records).entries.joinToString("\n") { "${it.key}：${it.value} 条" }
            val recent = (maxOf(0, records.length() - 3) until records.length()).reversed().map { index ->
                val row = records.getJSONObject(index)
                val pkg = row.optString("package")
                val source = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("time")))
                "$source · $time\n${row.optString("title")} ${row.optString("text")}"
            }.joinToString("\n\n")
            "最近保留的 ${records.length()} 条通知：\n$lines\n\n最近记录：\n$recent"
        }
        briefBusy = true
        android.widget.Toast.makeText(this, "正在读取本地简报…", android.widget.Toast.LENGTH_SHORT).show()
        val app = applicationContext
        briefExecutor.execute {
            val checkedAt = System.currentTimeMillis()
            val zone = java.util.TimeZone.getDefault()
            val calendar = CompanionCalendar.read(app, checkedAt, zone)
            val once = runCatching { CompanionBriefReminders.read(CompanionReminder(app).list(), false, checkedAt, zone.toZoneId()) }
            val repeating = runCatching { CompanionBriefReminders.read(CompanionRecurringReminder(app).list(), true, checkedAt, zone.toZoneId()) }
            memoryHandler.post {
                briefBusy = false
                if (isFinishing || isDestroyed) return@post
                val calendarText = when (calendar.state) {
                    CompanionCalendar.State.NO_PERMISSION -> "日历未授权；可在助手设置中授权。"
                    CompanionCalendar.State.UNAVAILABLE -> "日历读取失败或系统日历不可用。"
                    CompanionCalendar.State.OK -> if (calendar.events.isEmpty()) "当天可见日历没有日程。" else {
                        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).apply { timeZone = zone }
                        calendar.events.joinToString("\n") { event ->
                            val time = if (event.allDay) "全天" else dateFormat.format(Date(event.start))
                            "$time · ${event.title}"
                        } + if (calendar.truncated) "\n仅显示前 ${CompanionCalendar.MAX_EVENTS} 条。" else ""
                    }
                }
                val onceText = briefReminderText(once, zone)
                val repeatingText = briefReminderText(repeating, zone)
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("本地简报")
                    .setMessage("今天的可见日历（${zone.id}，读取开始于 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).apply { timeZone = zone }.format(Date(checkedAt))}）：\n$calendarText\n\n今天的一次性提醒：\n$onceText\n\n今天的重复提醒（仅显示下一次）：\n$repeatingText\n\n$notifications\n\n已交给系统不保证送达；通知分类可能遗漏重要事项。天气尚未接入。")
                    .setNeutralButton("提醒操作") { _, _ ->
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择操作")
                            .setItems(arrayOf("管理一次性提醒", "管理重复提醒", "从日程创建提醒")) { _, choice ->
                                when (choice) { 0 -> showReminders(); 1 -> showRecurringReminders(); 2 -> chooseCalendarReminder() }
                            }.setNegativeButton("取消", null).show()
                    }
                    .setPositiveButton("关闭", null).show()
            }
        }
    }

    private fun chooseCalendarReminder() {
        runMemoryIo("正在重新读取日程…", {
            val now = System.currentTimeMillis()
            val zone = java.util.TimeZone.getDefault()
            val snapshot = CompanionCalendar.readReminderCandidates(applicationContext, now, zone)
            Triple(snapshot, CompanionCalendarReminderPolicy.candidates(snapshot.events, now), zone)
        }) { result ->
            result.onFailure { android.widget.Toast.makeText(this, "无法读取日程，请检查权限和系统日历。", android.widget.Toast.LENGTH_LONG).show() }.onSuccess { (snapshot, candidates, zone) ->
                if (snapshot.state != CompanionCalendar.State.OK) {
                    android.widget.Toast.makeText(this, if (snapshot.state == CompanionCalendar.State.NO_PERMISSION) "日历未授权，请先授权。" else "日历读取失败，请检查系统日历。", android.widget.Toast.LENGTH_LONG).show()
                } else if (candidates.isEmpty()) {
                    android.widget.Toast.makeText(this, "当天没有可选的未来非全天日程；仅显示最早的 20 项。", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).apply { timeZone = zone }
                    val labels = candidates.map { "${formatter.format(Date(it.event.start))} · ${it.event.title}" }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择未来日程（${zone.id}${if (snapshot.truncated) "，仅最早 20 项" else ""}）")
                        .setItems(labels) { _, index ->
                            val item = candidates[index]
                            createReminder(item.event.title, item.suggestedAt,
                                "时间根据日程开始时间提出，可能提前 10 分钟；请核对时区、时间和内容。日程后续变化不会自动更新此提醒。")
                        }.setNegativeButton("取消", null).show()
                }
            }
        }
    }

    private fun briefReminderText(result: Result<CompanionBriefReminders.Snapshot>, zone: java.util.TimeZone): String = result.fold(
        { snapshot ->
            if (snapshot.items.isEmpty()) "没有当天记录。" else {
                val formatter = DateFormat.getTimeInstance(DateFormat.SHORT).apply { timeZone = zone }
                snapshot.items.joinToString("\n") { "${formatter.format(Date(it.at))} · ${it.text} · ${it.state}" } +
                    if (snapshot.truncated) "\n仅显示前 ${CompanionBriefReminders.MAX_ITEMS} 条。" else ""
            }
        },
        { "无法读取提醒记录，请在提醒管理中检查。" },
    )

    private fun showNotificationCandidates() {
        val records = recordsOrNull() ?: return
        val candidates = CompanionBrief.candidates(records)
        if (candidates.isEmpty()) { paragraph("当前本地记录没有匹配的候选；这不代表没有待处理事项。") ; return }
        val labels = candidates.map { item ->
            val source = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(item.packageName, 0)).toString() }.getOrDefault(item.packageName)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.time))
            "${item.reason} · $source · $date\n${item.title.ifBlank { item.text }.take(120)}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("通知候选（时间为通知时间）")
            .setItems(labels) { _, index ->
                val item = candidates[index]
                val content = listOf(item.title, item.text).filter { it.isNotBlank() }.joinToString(" · ")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(item.reason)
                    .setMessage("${labels[index]}\n\n$content\n\n本地规则可能误判；时间是通知时间，不是截止时间。原通知仅在仍然有效且内容未改变时可打开；来源应用入口不能保证跳转到原通知。创建提醒时请核对内容并自行选择时间；提醒不保留与原通知的关联。")
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("打开通知或应用") { _, _ ->
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择打开方式")
                            .setItems(arrayOf("打开原通知（仍有效时）", "打开来源应用首页")) { _, choice ->
                                if (choice == 0) {
                                    if (!CompanionNotificationService.openOriginal(item)) android.widget.Toast.makeText(this, "原通知不可用或内容已变化，请选择打开来源应用。", android.widget.Toast.LENGTH_LONG).show()
                                } else openNotificationSource(item.packageName)
                            }.setNegativeButton("取消", null).show()
                    }
                    .setPositiveButton("创建提醒") { _, _ ->
                        val suggestion = CompanionNotificationTimeSuggestion.find(content, System.currentTimeMillis(), java.time.ZoneId.systemDefault())
                        createReminder(content, suggestion)
                    }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun openNotificationSource(packageName: String) {
        val opened = runCatching {
            require(CompanionSourcePolicy.validPackage(packageName))
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: error("无启动入口")
            require(CompanionSourcePolicy.matchesResolved(packageName, launch.component?.packageName))
            launch.setPackage(packageName)
            startActivity(launch)
        }.isSuccess
        if (!opened) android.widget.Toast.makeText(this, "无法打开应用，它可能已卸载或没有启动入口。", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun showMemorySuggestions() {
        if (!store.notificationMemorySuggestions) { paragraph("请先开启从通知中寻找记忆候选。") ; return }
        val rows = recordsOrNull() ?: return
        val candidates = CompanionMemorySuggestions.fromRecords(rows, store.excludedPackages(), store.dismissedMemorySuggestions())
        if (candidates.isEmpty()) { paragraph("当前没有可用候选；规则可能遗漏，也不代表没有值得记住的信息。") ; return }
        val labels = candidates.map { item ->
            val source = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(item.packageName, 0)).toString() }.getOrDefault(item.packageName)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.time))
            "$source · $date\n${item.excerpt}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("通知记忆候选")
            .setItems(labels) { _, index ->
                val candidate = candidates[index]
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("核对候选来源")
                    .setMessage("${labels[index]}\n\n这只是通知中可能表达偏好的短句，可能由其他人发送，也可能过时或误判。请自行判断是否与你有关。")
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("忽略此条") { _, _ -> store.dismissMemorySuggestion(candidate.id); showMemorySuggestions() }
                    .setPositiveButton("整理成记忆") { _, _ -> composeUserMemory(candidate.excerpt) { store.dismissMemorySuggestion(candidate.id) } }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun chooseNotificationForMemory() {
        val rows = recordsOrNull() ?: return
        if (rows.length() == 0) { paragraph("没有可用的本地通知记录。") ; return }
        val items = (0 until rows.length()).reversed().map { rows.getJSONObject(it) }
        val labels = items.map { row ->
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("time")))
            "$time · ${row.optString("package")}\n${row.optString("title").ifBlank { row.optString("text") }.take(120)}"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择参考通知")
            .setItems(labels) { _, index ->
                val row = items[index]
                val reference = listOf(row.optString("title"), row.optString("text")).filter { it.isNotBlank() }.joinToString("\n")
                composeUserMemory(reference)
            }.setNegativeButton("取消", null).show()
    }

    private fun composeUserMemory(reference: String?, onConfirmed: (() -> Unit)? = null) {
        val edit = android.widget.EditText(this).apply {
            hint = "写下你希望助手记住的事实或偏好"
            minLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(600))
        }
        val explanation = "仅保存你填写的内容；确认后可供助手检索，相关内容可能发送给当前模型服务。请核对事实，避免保存敏感信息。"
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("添加长期记忆")
            .setMessage(if (reference == null) explanation else "参考通知：\n$reference\n\n$explanation")
            .setView(edit).setNegativeButton("取消", null).setPositiveButton("确认记住", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = edit.text.toString().trim()
                if (value.isEmpty() || CompanionStore.sensitive(value)) {
                    edit.error = "请填写内容，并移除可能的敏感信息。"
                    return@setOnClickListener
                }
                if (memoryBusy) {
                    edit.error = "记忆文件操作正在进行，请稍候。"
                    return@setOnClickListener
                }
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                runMemoryIo("正在保存记忆…", { CompanionMemoryStore(this).addForUser(value, if (reference == null) "user_edit" else "user_notification") }) { result ->
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    result.onSuccess { outcome ->
                        if (outcome != UserMemoryResult.EXISTING_PENDING) onConfirmed?.invoke()
                        dialog.dismiss()
                        val message = when (outcome) {
                            UserMemoryResult.ADDED -> "已添加长期记忆。"
                            UserMemoryResult.EXISTING_PENDING -> "相同内容已在待确认列表中，请先核对。"
                            UserMemoryResult.EXISTING_CONFIRMED -> "相同内容已存在。"
                        }
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                        render()
                    }.onFailure { edit.error = it.message ?: "保存失败，原数据已保留。" }
                }
            }
        }
        dialog.show()
    }

    private fun showMemories(query: String = "") {
        val memory = CompanionMemoryStore(this)
        val all = runCatching { memory.listForUser() }.getOrElse { paragraph("无法读取长期记忆，原数据已保留。"); return }
        if (all.length() == 0) { paragraph("还没有长期记忆。"); return }
        val records = org.json.JSONArray()
        for (index in 0 until all.length()) {
            val row = all.getJSONObject(index)
            if (query.isEmpty() || row.getString("text").contains(query, ignoreCase = true)) records.put(row)
        }
        if (records.length() == 0) { paragraph("没有匹配的记忆，请尝试其他关键词。"); return }
        val labels = Array(records.length()) {
            val row = records.getJSONObject(it)
            val source = when (row.optString("source")) { "assistant_tool" -> "助手记忆工具"; "user_edit" -> "用户编辑"; "user_notification" -> "用户根据通知整理"; else -> "来源未知" }
            val time = row.optLong("createdAt", 0)
            val date = if (time > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time)) else "时间未知"
            val status = if (row.optString("status") == "pending") "待确认" else "已确认"
            "[$status] ${row.getString("text")}\n来源：$source · $date"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择要编辑或删除的记忆")
            .setItems(labels) { _, index ->
                val id = records.getJSONObject(index).getString("id")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("管理记忆")
                    .setMessage(labels[index]).setNegativeButton("关闭", null)
                    .setNeutralButton(if (records.getJSONObject(index).optString("status") == "pending") "确认记住" else "编辑") { _, _ ->
                        if (records.getJSONObject(index).optString("status") == "pending") {
                            val original = records.getJSONObject(index).getString("text")
                            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("确认方式")
                                .setItems(arrayOf("直接确认", "编辑后确认")) { _, action ->
                                    if (action == 0) {
                                        runMemoryIo("正在确认记忆…", { memory.approveForUser(id, original) }) { result ->
                                            result.onSuccess { if (it) { render(); paragraph("已确认记忆。") } else paragraph("记忆已变化，请重新查看。") }
                                                .onFailure { paragraph("确认失败，原数据已保留。") }
                                        }
                                    } else editPendingMemory(memory, id, original)
                                }.setNegativeButton("取消", null).show()
                        } else {

                        editConfirmedMemory(memory, records.getJSONObject(index))
                        }
                    }.setPositiveButton("删除") { _, _ ->
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("删除这条记忆？").setMessage(labels[index])
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        val expected = records.getJSONObject(index).toString()
                        runMemoryIo("正在删除记忆…", { memory.deleteForUser(id, expected) }) { result ->
                            val message = result.fold({ if (it) "记忆已删除。" else "记忆已变化或不存在，请重新查看。" }, { "删除失败，原数据已保留。" })
                            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                        }
                            }.show()
                    }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun editConfirmedMemory(memory: CompanionMemoryStore, row: org.json.JSONObject) {
        val id = row.getString("id")
        val expected = row.toString()
        val edit = android.widget.EditText(this).apply { setText(row.getString("text")); minLines = 2; filters = arrayOf(android.text.InputFilter.LengthFilter(600)) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("编辑记忆")
            .setMessage("保存后内容可供助手检索，并可能发送给所选模型服务。")
            .setView(edit).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            val save = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val value = edit.text.toString().trim()
                if (value.isEmpty() || CompanionStore.sensitive(value)) { edit.error = "请填写内容，并移除可能的敏感信息。"; return@setOnClickListener }
                if (memoryBusy) { edit.error = "记忆文件操作正在进行，请稍候。"; return@setOnClickListener }
                save.isEnabled = false; edit.isEnabled = false
                runMemoryIo("正在保存记忆…", { memory.updateForUser(id, expected, value) }) { result ->
                    save.isEnabled = true; edit.isEnabled = true
                    result.onSuccess { updated ->
                        if (updated) { dialog.dismiss(); android.widget.Toast.makeText(this, "记忆已更新。", android.widget.Toast.LENGTH_LONG).show() }
                        else edit.error = "记忆已变化或不存在，请复制修改内容后重新查看。"
                    }.onFailure { edit.error = it.message ?: "保存失败，原数据已保留。" }
                }
            }
        }
        dialog.show()
    }

    private fun editPendingMemory(memory: CompanionMemoryStore, id: String, original: String) {
        val edit = android.widget.EditText(this).apply {
            setText(original); setSelection(length()); minLines = 2
            filters = arrayOf(android.text.InputFilter.LengthFilter(600))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("编辑并确认记忆")
            .setMessage("确认后内容可供助手检索，并可能发送给当前模型服务。")
            .setView(edit).setNegativeButton("取消", null).setPositiveButton("确认记住", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (memoryBusy) { edit.error = "记忆文件操作正在进行，请稍候。"; return@setOnClickListener }
                val text = edit.text.toString()
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                runMemoryIo("正在确认记忆…", { memory.approveEditedForUser(id, original, text) }) { result ->
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    result.onSuccess { saved ->
                        if (saved) { dialog.dismiss(); render(); paragraph("已确认记忆。") }
                        else edit.error = "候选已变化，请关闭并重新查看。"
                    }.onFailure { edit.error = it.message ?: "确认失败，原数据已保留。" }
                }
            }
        }
        dialog.show()
    }

    private fun copyNotificationSummary() {
        val records = recordsOrNull() ?: return
        if (records.length() == 0) { paragraph("没有可复制的通知摘要。"); return }
        val counts = mutableMapOf<String, Int>()
        for (index in 0 until records.length()) {
            val pkg = records.optJSONObject(index)?.optString("package").orEmpty()
            if (pkg.isNotBlank()) counts[pkg] = (counts[pkg] ?: 0) + 1
        }
        val lines = counts.entries.sortedByDescending { it.value }.take(5).map { "${it.key}: ${it.value} 条" }
        val text = "以下是我主动提供的本地通知习惯摘要，请帮我提出建议，不要假定这代表全部通知：\n" + lines.joinToString("\n")
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        if (clipboard == null) { paragraph("无法访问剪贴板。"); return }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("鲸鱼娘通知摘要", text))
        android.widget.Toast.makeText(this, "摘要已复制，可自行粘贴到对话；可能由所选模型服务处理。", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun hero() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#174F8D"), Color.parseColor("#357CB2"))).apply { cornerRadius = dp(22).toFloat() }
        }
        card.addView(TextView(this).apply { text = "WHALE COMPANION"; textSize = 11f; letterSpacing = 0.12f; setTextColor(Color.parseColor("#D4EEFF")) })
        card.addView(TextView(this).apply { text = "鲸鱼娘助手"; textSize = 25f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, dp(6), 0, dp(4)) })
        card.addView(TextView(this).apply { text = "记住重要的事，陪你从容处理每一天。"; textSize = 14f; setTextColor(Color.parseColor("#E3F3FF")) })
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun sectionNavigation(): android.widget.HorizontalScrollView {
        val items = listOf("status" to "状态", "voice" to "语音", "memory" to "记忆", "knowledge" to "资料", "notifications" to "通知", "reminders" to "提醒", "apps" to "常用", "brief" to "日历与简报")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        for ((id, label) in items) {
            val item = Button(this).apply {
                text = label; textSize = 13f; isAllCaps = false; minHeight = dp(44); minimumWidth = 0
                setPadding(dp(16), 0, dp(16), 0); elevation = 0f
                setTextColor(ink); background = surface(if (dark) "#172B40" else "#FFFFFF", if (dark) "#294660" else "#DCEAF5")
                contentDescription = "跳转到$label"
                setOnClickListener {
                    val section = content.findViewWithTag<View>("section:$id") ?: return@setOnClickListener
                    scroll.smoothScrollTo(0, section.top)
                    section.announceForAccessibility("已定位到$label")
                }
            }
            row.addView(item, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            contentDescription = "助手设置分区导航"
            addView(row)
        }
    }
    private fun heading(value: String, section: String? = null) { content.addView(TextView(this).apply {
        text = value; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink); setPadding(0, dp(24), 0, dp(10))
        if (section != null) tag = "section:$section"
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }) }
    private fun paragraph(value: String) { content.addView(TextView(this).apply {
        text = value; textSize = 14f; setTextColor(muted); setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(2), dp(6), dp(2), dp(8))
    }) }
    @Suppress("DEPRECATION")
    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(Switch(this).apply {
            text = label; textSize = 15f; setTextColor(ink); isChecked = checked; minHeight = dp(56)
            setPadding(dp(14), dp(8), dp(14), dp(8)); background = surface(if (dark) "#172B40" else "#FFFFFF")
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(6) })
    }
    private fun button(label: String, onClick: () -> Unit) { content.addView(Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(ink); minHeight = dp(52); setPadding(dp(16), dp(8), dp(16), dp(8)); elevation = 0f
        background = surface(if (dark) "#172B40" else "#FFFFFF", if (dark) "#294660" else "#DCEAF5")
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) }) }
    private fun openSettings(action: String) { runCatching { startActivity(Intent(action)) }.onFailure { paragraph("无法打开系统设置，请手动前往系统设置。") } }
}
