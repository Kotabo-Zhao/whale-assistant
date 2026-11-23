package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.OverlayBallService
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 事件触发型主动守护引擎（Event-Driven Routines）。
 *
 * 两级过滤漏斗：
 * 1. 第一级纯本地包名 + 关键词/正则匹配（0 Token、零网络、零唤醒大模型），99% 无关通知毫秒级放行；
 * 2. 命中后严格校验免打扰时段、单规则冷却与内容去重，再执行确定性动作（写入秘书待办 / 存入资料库 / 桌宠轻提醒）。
 */
internal object WhaleRoutineEngine {
    private const val PREFS = "whale_routines_v1"
    private const val KEY_RULES = "rules_json"
    private const val KEY_DEDUP = "dedup_hashes_json"
    private const val MAX_RULES = 24
    private const val MAX_DEDUP = 60

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "whale-routine-engine").apply { isDaemon = true }
    }

    data class RoutineRule(
        val id: String,
        val name: String,
        val enabled: Boolean,
        val packageFilter: String,
        val keywordRegex: String,
        val actionKind: String, // add_todo | save_knowledge | pet_notify
        val titleTemplate: String,
        val cooldownMinutes: Int,
        val lastTriggeredAt: Long,
        val triggerCount: Int,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("enabled", enabled)
            .put("packageFilter", packageFilter)
            .put("keywordRegex", keywordRegex)
            .put("actionKind", actionKind)
            .put("titleTemplate", titleTemplate)
            .put("cooldownMinutes", cooldownMinutes)
            .put("lastTriggeredAt", lastTriggeredAt)
            .put("triggerCount", triggerCount)

        companion object {
            fun fromJson(o: JSONObject): RoutineRule? {
                val id = o.optString("id").trim()
                val name = o.optString("name").trim()
                val regex = o.optString("keywordRegex").trim()
                if (id.isEmpty() || name.isEmpty() || regex.isEmpty()) return null
                return RoutineRule(
                    id = id,
                    name = name,
                    enabled = o.optBoolean("enabled", true),
                    packageFilter = o.optString("packageFilter", "").trim(),
                    keywordRegex = regex,
                    actionKind = o.optString("actionKind", "add_todo").trim().ifEmpty { "add_todo" },
                    titleTemplate = o.optString("titleTemplate", "").trim(),
                    cooldownMinutes = o.optInt("cooldownMinutes", 10).coerceIn(1, 1440),
                    lastTriggeredAt = o.optLong("lastTriggeredAt", 0L),
                    triggerCount = o.optInt("triggerCount", 0),
                )
            }
        }
    }

    private fun defaultRules(): List<RoutineRule> = listOf(
        RoutineRule(
            id = "preset-pickup-code",
            name = "快递取件码自动转待办",
            enabled = true,
            packageFilter = "",
            keywordRegex = "取件码|菜鸟驿站|丰巢|快递柜|兔喜|妈妈驿站|凭码取件",
            actionKind = "add_todo",
            titleTemplate = "取快递：{code}（{title}）",
            cooldownMinutes = 2,
            lastTriggeredAt = 0L,
            triggerCount = 0,
        ),
        RoutineRule(
            id = "preset-travel-change",
            name = "航班与高铁变动自动归档",
            enabled = true,
            packageFilter = "",
            keywordRegex = "登机口|航班延误|航班取消|检票口|12306|列车晚点",
            actionKind = "add_todo",
            titleTemplate = "出行变动：{title}",
            cooldownMinutes = 5,
            lastTriggeredAt = 0L,
            triggerCount = 0,
        ),
        RoutineRule(
            id = "preset-bill-archive",
            name = "账单与缴费提醒存资料库",
            enabled = false,
            packageFilter = "",
            keywordRegex = "水费|电费|燃气费|宽带到期|信用卡账单|还款日",
            actionKind = "save_knowledge",
            titleTemplate = "账单提醒：{title}",
            cooldownMinutes = 30,
            lastTriggeredAt = 0L,
            triggerCount = 0,
        ),
    )

    @Synchronized
    fun list(context: Context): List<RoutineRule> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RULES, null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultRules()
            saveAll(context, defaults)
            return defaults
        }
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return defaultRules()
        val out = ArrayList<RoutineRule>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let(RoutineRule::fromJson)?.let(out::add)
        }
        return out
    }

    @Synchronized
    private fun saveAll(context: Context, rules: List<RoutineRule>) {
        val arr = JSONArray()
        rules.take(MAX_RULES).forEach { arr.put(it.json()) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, arr.toString()).apply()
    }

    @Synchronized
    fun create(
        context: Context,
        name: String,
        keywordRegex: String,
        actionKind: String = "add_todo",
        packageFilter: String = "",
        titleTemplate: String = "",
        cooldownMinutes: Int = 10,
    ): RoutineRule {
        val cleanName = name.trim().take(40)
        val cleanRegex = keywordRegex.trim().take(240)
        require(cleanName.isNotEmpty() && cleanRegex.isNotEmpty()) { "规则名称和触发关键词不能为空" }
        runCatching { Regex(cleanRegex, RegexOption.IGNORE_CASE) }.getOrElse {
            throw IllegalArgumentException("触发正则语法无效：${it.message}")
        }
        val validAction = if (actionKind in setOf("add_todo", "save_knowledge", "pet_notify")) actionKind else "add_todo"
        val current = list(context).toMutableList()
        val rule = RoutineRule(
            id = "rt-${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            enabled = true,
            packageFilter = packageFilter.trim().take(80),
            keywordRegex = cleanRegex,
            actionKind = validAction,
            titleTemplate = titleTemplate.trim().take(80).ifEmpty { "$cleanName：{title}" },
            cooldownMinutes = cooldownMinutes.coerceIn(1, 1440),
            lastTriggeredAt = 0L,
            triggerCount = 0,
        )
        current.add(0, rule)
        saveAll(context, current)
        return rule
    }

    @Synchronized
    fun toggle(context: Context, idOrName: String, enabled: Boolean): RoutineRule? {
        val key = idOrName.trim().lowercase()
        val current = list(context).toMutableList()
        val idx = current.indexOfFirst { it.id.lowercase() == key || it.name.lowercase().contains(key) }
        if (idx < 0) return null
        val updated = current[idx].copy(enabled = enabled)
        current[idx] = updated
        saveAll(context, current)
        return updated
    }

    @Synchronized
    fun delete(context: Context, idOrName: String): Boolean {
        val key = idOrName.trim().lowercase()
        val current = list(context).toMutableList()
        val removed = current.removeAll { it.id.lowercase() == key || it.name.lowercase() == key }
        if (removed) saveAll(context, current)
        return removed
    }

    private fun extractPickupCode(text: String): String {
        val m = Regex("""\b([A-Za-z0-9]{1,3}-[A-Za-z0-9]{1,3}-[A-Za-z0-9]{2,5}|\d{4,8})\b""").find(text)
        return m?.value ?: "见正文"
    }

    @Synchronized
    private fun markDedupIfNew(context: Context, signature: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString(KEY_DEDUP, "[]")) }.getOrElse { JSONArray() }
        val set = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotEmpty()) set.add(s)
        }
        if (signature in set) return false
        set.add(signature)
        val trimmed = set.toList().takeLast(MAX_DEDUP)
        val out = JSONArray()
        trimmed.forEach { out.put(it) }
        prefs.edit().putString(KEY_DEDUP, out.toString()).apply()
        return true
    }

    /** 由通知监听服务异步调用：全本地 0 Token 匹配并执行守护动作。 */
    fun onNotificationAsync(context: Context, pkg: String, title: String, text: String) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val store = CompanionStore(app)
                if (store.inQuietHours()) return@runCatching
                val combined = "$title $text".trim()
                if (combined.length < 4) return@runCatching
                val now = System.currentTimeMillis()
                val rules = list(app).toMutableList()
                var dirty = false
                for (idx in rules.indices) {
                    val rule = rules[idx]
                    if (!rule.enabled) continue
                    if (rule.packageFilter.isNotEmpty() && !pkg.contains(rule.packageFilter, ignoreCase = true)) continue
                    if (now - rule.lastTriggeredAt < rule.cooldownMinutes * 60_000L) continue
                    val matched = runCatching { Regex(rule.keywordRegex, RegexOption.IGNORE_CASE).containsMatchIn(combined) }.getOrDefault(false)
                    if (!matched) continue
                    val sig = "${rule.id}:${combined.take(96).hashCode()}"
                    if (!markDedupIfNew(app, sig)) continue

                    val code = extractPickupCode(combined)
                    val renderedTitle = rule.titleTemplate
                        .replace("{code}", code)
                        .replace("{title}", title.ifBlank { combined.take(20) }.take(28))
                        .take(80)
                    val noteBody = "[$pkg] $title\n$text".take(420)

                    when (rule.actionKind) {
                        "add_todo" -> {
                            val req = JSONObject()
                                .put("action", "create")
                                .put("operationId", UUID.randomUUID().toString())
                                .put("title", renderedTitle)
                                .put("note", noteBody)
                            UnifiedUnderstandingStore.open(app).use { it.queue.secretary.execute(req, owner = "whale-routine") }
                            if (store.notificationBubbles) OverlayBallService.companionCandidateHint()
                        }
                        "save_knowledge" -> {
                            CompanionKnowledgeStore(app).add("$renderedTitle\n$noteBody", source = "whale_routine")
                            if (store.notificationBubbles) OverlayBallService.companionNotificationHint()
                        }
                        "pet_notify" -> {
                            if (store.notificationBubbles) OverlayBallService.companionCandidateHint()
                        }
                    }
                    rules[idx] = rule.copy(lastTriggeredAt = now, triggerCount = rule.triggerCount + 1)
                    dirty = true
                }
                if (dirty) saveAll(app, rules)
            }
        }
    }
}
