package io.deepseekharness.mobile.companion

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/** 权限与数据在此由用户直接控制；无需解锁或无障碍读取其他应用私有数据。 */
class CompanionActivity : AppCompatActivity() {
    private lateinit var store: CompanionStore
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CompanionStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        content.removeAllViews()
        heading("鲸鱼娘助手")
        paragraph("通知与使用数据只在本机处理，不会自动发送给模型。系统和应用可能限制读取；锁屏或敏感内容不会显示在桌面气泡。")
        val memory = CompanionMemoryStore(this)
        heading("长期记忆")
        toggle("允许助手使用长期记忆", memory.enabled) { memory.enabled = it }
        paragraph("助手可保存和检索长期偏好；相关内容可能发送给当前模型服务。记忆保存在应用私有存储，运行环境更新不会清空；卸载应用会删除。")
        button("查看和删除长期记忆") { showMemories() }
        button("清空全部长期记忆") {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("清空长期记忆？")
                .setMessage("这会删除全部长期记忆，无法撤销。")
                .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                    runCatching { memory.clearForUser() }.onSuccess { render() }.onFailure { paragraph("无法清空记忆，原数据已保留。") }
                }.show()
        }
        toggle("收集通知摘要", store.notificationCapture) { store.notificationCapture = it; render() }
        paragraph("开启后记录通知来源、标题和正文，最多 50 条；只在读取或新增时清理超过 7 天的记录。验证码与密码使用简单规则过滤，不能保证识别所有敏感内容。")
        button("打开系统通知读取授权") { openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        toggle("桌面休息提醒", store.reminders) { store.reminders = it }
        paragraph("悬浮助手运行且设备已解锁时，约每 90 分钟显示一次休息提示；低电量时可发系统通知（需通知权限）。系统可能停止后台服务。")
        heading("手机使用情况")
        paragraph("只在点击时读取系统最近 7 天的应用使用时长；需要在系统中单独授予使用情况访问权限。")
        button("打开使用情况访问授权") { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        button("查看最近 7 天使用情况") { showUsage() }
        heading("本地通知记录")
        button("查看通知习惯摘要") { showNotificationSummary() }
        button("复制通知习惯摘要") { copyNotificationSummary() }
        button("刷新通知记录") { renderRecords() }
        button("清除全部本地通知记录") { store.clear(); render() }
        renderRecords()
        button("返回") { finish() }
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
        totals.forEach { (pkg, millis) ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            paragraph("$label · ${millis / 60000} 分钟")
        }
    }

    private fun renderRecords() {
        // Rebuild to avoid appending duplicate lists on repeated refresh.
        if (content.findViewWithTag<LinearLayout>("records") != null) content.removeView(content.findViewWithTag("records"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "records" }
        val records = store.read()
        if (records.length() == 0) list.addView(TextView(this).apply { text = "暂无记录" })
        for (index in records.length() - 1 downTo 0) {
            val row = records.optJSONObject(index) ?: continue
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.optLong("time")))
            list.addView(TextView(this).apply { text = "$time · ${row.optString("package")}\n${row.optString("title")}\n${row.optString("text")}"; setPadding(0, 12, 0, 12) })
        }
        val returnButton = content.childCount - 1
        content.addView(list, if (returnButton >= 0 && content.getChildAt(returnButton) is Button && (content.getChildAt(returnButton) as Button).text == "返回") returnButton else content.childCount)
    }

    private fun showNotificationSummary() {
        val records = store.read()
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

    private fun showMemories() {
        val memory = CompanionMemoryStore(this)
        val records = runCatching { memory.listForUser() }.getOrElse { paragraph("无法读取长期记忆，原数据已保留。"); return }
        if (records.length() == 0) { paragraph("还没有长期记忆。"); return }
        val labels = Array(records.length()) { records.getJSONObject(it).getString("text") }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("选择要删除的记忆")
            .setItems(labels) { _, index ->
                val id = records.getJSONObject(index).getString("id")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("删除这条记忆？")
                    .setMessage(labels[index]).setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        runCatching { memory.deleteForUser(id) }.onFailure { paragraph("删除失败，原数据已保留。") }
                    }.show()
            }.setNegativeButton("关闭", null).show()
    }

    private fun copyNotificationSummary() {
        val records = store.read()
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

    private fun heading(value: String) { content.addView(TextView(this).apply { text = value; textSize = 22f; setPadding(0, 18, 0, 8) }) }
    private fun paragraph(value: String) { content.addView(TextView(this).apply { text = value; textSize = 14f; setPadding(0, 6, 0, 8) }) }
    @Suppress("DEPRECATION")
    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(Switch(this).apply { text = label; isChecked = checked; setPadding(0, 10, 0, 10); setOnCheckedChangeListener { _, value -> onChange(value) } })
    }
    private fun button(label: String, onClick: () -> Unit) { content.addView(Button(this).apply { text = label; gravity = Gravity.CENTER; setOnClickListener { onClick() } }) }
    private fun openSettings(action: String) { runCatching { startActivity(Intent(action)) }.onFailure { paragraph("无法打开系统设置，请手动前往系统设置。") } }
}
