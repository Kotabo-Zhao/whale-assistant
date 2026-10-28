package io.deepseekharness.mobile.companion

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
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
        memoryExecutor.shutdown()
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

    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        if (text.isBlank()) { paragraph("没有识别到语音，请重试或输入文字。"); return@registerForActivityResult }
        val edit = android.widget.EditText(this).apply { setText(text) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("识别结果")
            .setMessage("请检查结果；复制后可粘贴到助手对话。语音识别服务可能联网。")
            .setView(edit).setNegativeButton("取消", null).setPositiveButton("复制") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("语音任务", edit.text.toString()))
            }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CompanionStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor(if (dark) "#0D1A2A" else "#F1F7FC"))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        content.removeAllViews()
        hero()
        readinessCard()
        paragraph("通知与使用数据只在本机处理，不会自动发送给模型。系统和应用可能限制读取；锁屏或敏感内容不会显示在桌面气泡。")
        button("语音输入任务") { startVoiceInput() }
        paragraph("使用手机提供的语音识别服务；识别结果需自行确认并粘贴到对话，服务可能联网。")
        val memory = CompanionMemoryStore(this)
        heading("长期记忆")
        toggle("允许助手使用长期记忆", memory.enabled) { memory.enabled = it }
        paragraph("助手提出的记忆先等待你确认；已确认的记忆可供助手检索，相关内容可能发送给当前模型服务。记忆保存在应用私有存储，运行环境更新不会清空；卸载应用会删除。")
        val pendingCount = runCatching { memory.listForUser() }.getOrNull()?.let { rows ->
            (0 until rows.length()).count { rows.optJSONObject(it)?.optString("status") == "pending" }
        }
        if (pendingCount == null) paragraph("无法读取长期记忆，原数据已保留。")
        else if (pendingCount > 0) paragraph("有 $pendingCount 条记忆等待确认。")
        button("查看、确认和管理长期记忆") { showMemories() }
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
        toggle("收集通知摘要", store.notificationCapture) { store.notificationCapture = it; render() }
        paragraph("开启后记录通知来源、标题和正文，最多 50 条；只在读取或新增时清理超过 7 天的记录。验证码与密码使用简单规则过滤，不能保证识别所有敏感内容。")
        val notificationAllowed = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        paragraph(if (notificationAllowed) "系统通知读取授权：已授予。系统可能停止服务或限制后台运行。" else "系统通知读取授权：未授予。开启采集后还需在系统设置中授权。")
        button("打开系统通知读取授权") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        button("设置不采集通知的应用") { selectExcludedApps() }
        paragraph("可从已记录过的应用中选择不再采集的来源；已有记录不会自动删除。")
        toggle("桌面休息提醒", store.reminders) { store.reminders = it }
        paragraph("悬浮助手运行且设备已解锁时，约每 90 分钟显示一次休息提示；低电量时可发系统通知（需通知权限）。系统可能停止后台服务。")
        heading("免打扰时段")
        toggle("启用免打扰", store.quietHoursEnabled) { store.quietHoursEnabled = it; render() }
        paragraph("使用手机当前时区。时段内暂停桌面气泡、休息提示和低电量通知；通知仍可按采集设置保存，手动设置的事项提醒通知仍会送达。结束后不会补发被跳过的气泡。起止时间相同表示不限制。")
        fun timeLabel(value: Int) = "%02d:%02d".format(value / 60, value % 60)
        button("开始时间 · ${timeLabel(store.quietHoursStart)}") { chooseQuietTime(true) }
        button("结束时间 · ${timeLabel(store.quietHoursEnd)}") { chooseQuietTime(false) }
        heading("事项提醒")
        paragraph("手动设置一次性提醒；需要通知权限。系统省电可能导致延迟，重启后会恢复未来提醒，错过的不会自动补发。")
        button("新建提醒") { createReminder() }
        button("查看和删除提醒") { showReminders() }
        heading("常用应用")
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
        button("查看最近 7 天使用情况") { showUsage() }
        heading("本地通知记录")
        paragraph("简报使用本地规则分类，可能漏判或分类错误，不代表已检查所有信息。")
        button("查看本地通知简报") { showBrief() }
        button("查看通知候选事项") { showNotificationCandidates() }
        paragraph("本地规则仅提供待核对的候选，不会自动安排提醒或发送回复。")
        button("查看通知习惯摘要") { showNotificationSummary() }
        button("复制通知习惯摘要") { copyNotificationSummary() }
        button("刷新通知记录") { renderRecords() }
        button("清除全部本地通知记录") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("清除全部通知记录？")
                .setMessage("此操作无法撤销，包括可能损坏的记录。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> store.clear(); render() }.show()
        }
        renderRecords()
        button("返回") { finish() }
    }

    private fun readinessCard() {
        heading("助手能力状态")
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

    private fun showUsage() {
        val appOps = getSystemService(AppOpsManager::class.java)
        val allowed = appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        if (!allowed) { paragraph("没有使用情况访问权限，请先到系统设置中授权。 "); return }
        val manager = getSystemService(UsageStatsManager::class.java)
        if (manager == null) { paragraph("无法读取使用情况。"); return }
        val now = System.currentTimeMillis()
        val data = runCatching { manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 7L * 24 * 60 * 60 * 1000, now) }.getOrNull()
        if (data == null) { paragraph("系统未返回使用情况。"); return }
        val totals = data.groupBy { it.packageName }.mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }
            .filterValues { it > 0 }.entries.sortedByDescending { it.value }.take(10)
        if (totals.isEmpty()) { paragraph("系统没有返回可用的使用记录。"); return }
        val entries = totals.toList()
        val labels = entries.map { (pkg, millis) ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            "$label · ${millis / 60000} 分钟\n$pkg"
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("使用情况（选择应用加入快捷方式）")
            .setItems(labels) { _, index ->
                val pkg = entries[index].key
                val launch = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
                if (!CompanionSourcePolicy.matchesResolved(pkg, launch?.component?.packageName)) {
                    paragraph("这个应用没有可用的启动入口。")
                } else {
                    androidx.appcompat.app.AlertDialog.Builder(this).setTitle("加入常用应用？").setMessage(labels[index])
                        .setNegativeButton("取消", null).setPositiveButton("加入") { _, _ ->
                            runCatching { store.addShortcut(pkg) }.onSuccess { render() }
                                .onFailure { paragraph("无法添加快捷方式，请检查是否已达到 6 个上限。") }
                        }.show()
                }
            }.setNegativeButton("关闭", null).show()
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

    private fun createReminder(initialText: String = "") {
        val input = android.widget.EditText(this).apply { hint = "提醒内容"; setSingleLine(true); setText(initialText.take(200)) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("新建提醒").setView(input)
        if (initialText.length > 200) dialog.setMessage("原摘要超过 200 字，已截取前 200 字；请核对和编辑提醒内容。")
        dialog.setNegativeButton("取消", null).setPositiveButton("选择时间") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isBlank()) { paragraph("提醒内容不能为空。"); return@setPositiveButton }
                val selected = Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }
                android.app.DatePickerDialog(this, { _, year, month, day ->
                    selected.set(Calendar.YEAR, year); selected.set(Calendar.MONTH, month); selected.set(Calendar.DAY_OF_MONTH, day)
                    android.app.TimePickerDialog(this, { _, hour, minute ->
                        selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute); selected.set(Calendar.SECOND, 0); selected.set(Calendar.MILLISECOND, 0)
                        runCatching { CompanionReminder(this).add(value, selected.timeInMillis) }
                            .onSuccess { paragraph("提醒已安排；系统可能延迟投递。") }
                            .onFailure { paragraph("无法安排提醒：${it.message ?: "请检查系统设置"}") }
                    }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show()
                }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show()
            }.show()
    }

    private fun showReminders() {
        val reminders = CompanionReminder(this)
        val rows = runCatching { reminders.list() }.getOrElse { paragraph("无法读取提醒，原数据已保留。"); return }
        if (rows.length() == 0) { paragraph("暂无提醒。"); return }
        val labels = Array(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("at")))
            val state = when (row.optString("state")) { "scheduled" -> "已安排"; "delivered" -> "已投递"; "missed" -> "已错过"; else -> "失败" }
            "${row.optString("text")} · $date · $state"
        }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择要删除的提醒").setItems(labels) { _, index ->
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("删除提醒？").setMessage(labels[index])
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    runCatching { reminders.remove(rows.getJSONObject(index).getString("id")) }.onFailure { paragraph("删除失败：${it.message}") }
                }.show()
        }.setNegativeButton("关闭", null).show()
    }

    private fun renderRecords() {
        // Rebuild to avoid appending duplicate lists on repeated refresh.
        if (content.findViewWithTag<LinearLayout>("records") != null) content.removeView(content.findViewWithTag("records"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "records" }
        val records = recordsOrNull() ?: return
        if (records.length() == 0) list.addView(TextView(this).apply { text = "暂无记录"; setTextColor(muted); setPadding(dp(4), dp(8), dp(4), dp(8)) })
        for (index in records.length() - 1 downTo 0) {
            val row = records.optJSONObject(index) ?: continue
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("time")))
            list.addView(TextView(this).apply {
                text = "$time · ${row.optString("package")}\n${row.optString("title")}\n${row.optString("text")}"
                setTextColor(ink); setPadding(dp(14), dp(12), dp(14), dp(12)); background = surface(if (dark) "#172B40" else "#FFFFFF")
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        val returnButton = content.childCount - 1
        content.addView(list, if (returnButton >= 0 && content.getChildAt(returnButton) is Button && (content.getChildAt(returnButton) as Button).text == "返回") returnButton else content.childCount)
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
        val records = recordsOrNull() ?: return
        if (records.length() == 0) {
            paragraph("没有可用的本地通知记录；请检查采集开关和通知读取权限。")
            return
        }
        val lines = CompanionBrief.counts(records).entries.joinToString("\n") { "${it.key}：${it.value} 条" }
        val recent = (maxOf(0, records.length() - 3) until records.length()).reversed().map { index ->
            val row = records.getJSONObject(index)
            val pkg = row.optString("package")
            val source = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("time")))
            "$source · $time\n${row.optString("title")} ${row.optString("text")}"
        }.joinToString("\n\n")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("本地通知简报")
            .setMessage("最近保留的 ${records.length()} 条通知：\n$lines\n\n最近记录：\n$recent\n\n分类由本地规则生成，可能遗漏重要事项。日历和天气尚未接入。")
            .setPositiveButton("关闭", null).show()
    }

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
                    .setPositiveButton("创建提醒") { _, _ -> createReminder(content) }.show()
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

    private fun showMemories() {
        val memory = CompanionMemoryStore(this)
        val records = runCatching { memory.listForUser() }.getOrElse { paragraph("无法读取长期记忆，原数据已保留。"); return }
        if (records.length() == 0) { paragraph("还没有长期记忆。"); return }
        val labels = Array(records.length()) {
            val row = records.getJSONObject(it)
            val source = when (row.optString("source")) { "assistant_tool" -> "助手记忆工具"; "user_edit" -> "用户编辑"; else -> "来源未知" }
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
                            runCatching { memory.approveForUser(id) }.onSuccess { if (it) paragraph("已确认记忆。") else paragraph("记忆已变化，请重新查看。") }
                                .onFailure { paragraph("确认失败，原数据已保留。") }
                        } else {

                        val edit = android.widget.EditText(this).apply { setText(records.getJSONObject(index).getString("text")) }
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("编辑记忆").setView(edit).setNegativeButton("取消", null)
                            .setPositiveButton("保存") { _, _ ->
                                runCatching { memory.updateForUser(id, edit.text.toString()) }
                                    .onSuccess { if (!it) paragraph("记忆已不存在。") }
                                    .onFailure { paragraph("保存失败：${it.message}") }
                            }.show()
                        }
                    }.setPositiveButton("删除") { _, _ ->
                        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("删除这条记忆？").setMessage(labels[index])
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        runCatching { memory.deleteForUser(id) }.onFailure { paragraph("删除失败，原数据已保留。") }
                            }.show()
                    }.show()
            }.setNegativeButton("关闭", null).show()
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
    private fun heading(value: String) { content.addView(TextView(this).apply {
        text = value; textSize = 19f; setTypeface(typeface, Typeface.BOLD); setTextColor(ink); setPadding(0, dp(24), 0, dp(10))
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
