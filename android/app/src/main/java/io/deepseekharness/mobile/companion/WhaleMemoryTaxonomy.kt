package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 结构化记忆 V2 分类、标签、重要度与混合重排引擎（纯确定性、零额外 LLM 延迟）。
 *
 * 为每条扁平记忆透明增强 6 大语义域、实体标签、重要度（1–5，置顶=5）、过期时间与冲突替代标记，
 * 向后完全兼容既有 SQLite `body` JSON，并支持：
 * - 混合重排（字面 + 分类/标签 + 重要度 + 时间新鲜度衰减）；
 * - 6 域全景速览（[buildDigest]），让 Agent 1 轮摸清用户画像全貌；
 * - 自动过滤已过期（`expiresAtMs`）与被替代（`supersededBy`）的过时条目。
 */
internal object WhaleMemoryTaxonomy {

    enum class Category(val key: String, val label: String) {
        PROFILE("profile", "个人画像"),
        PREFERENCE("preference", "偏好习惯"),
        PROJECT("project", "项目目标"),
        PEOPLE("people", "重要人际"),
        ROUTINE("routine", "日程作息"),
        CONSTRAINT("constraint", "规则约束"),
    }

    private val categoryRules: List<Pair<Category, List<String>>> = listOf(
        Category.CONSTRAINT to listOf("不要", "禁止", "绝不", "别用", "忌口", "过敏", "免打扰", "底线", "必须", "只用"),
        Category.PROJECT to listOf("项目", "开发", "面试", "考试", "研究", "方案", "需求", "重构", "上线", "论文", "频道", "计划", "目标", "架构", "代码"),
        Category.PEOPLE to listOf("同事", "老板", "导师", "朋友", "家人", "父母", "伴侣", "老婆", "老公", "女儿", "儿子", "客户", "团队"),
        Category.ROUTINE to listOf("每周", "每天", "例会", "早起", "熬夜", "睡眠", "通勤", "作息", "健身", "习惯在", "上午", "晚上", "周末"),
        Category.PREFERENCE to listOf("喜欢", "偏好", "习惯", "爱吃", "爱喝", "风格", "语气", "简洁", "直接", "主题", "咖啡", "美式", "称呼"),
        Category.PROFILE to listOf("我是", "工作", "职业", "工程师", "住在", "来自", "毕业", "学校", "公司", "背景", "擅长", "语言"),
    )

    fun parseCategory(raw: String?): Category? {
        val clean = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return Category.values().firstOrNull { it.key == clean || it.label == raw?.trim() }
    }

    /** 零延迟自动分类（硬约束优先，其余按命中词数择优），使复合句也能准确归类。 */
    fun classify(text: String): Category {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return Category.PROFILE
        val constraintCues = categoryRules.first().second
        if (constraintCues.any { lower.contains(it) }) return Category.CONSTRAINT
        var bestCat: Category? = null
        var bestHits = 0
        for ((cat, cues) in categoryRules.drop(1)) {
            val hits = cues.count { lower.contains(it) }
            if (hits > bestHits) {
                bestHits = hits
                bestCat = cat
            }
        }
        return bestCat ?: Category.PREFERENCE
    }

    /** 轻量提取 2–4 个高辨识度实体/关键词标签（不含泛词）。 */
    fun extractTags(text: String, limit: Int = 4): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        // 1. 提取英文/数字专有词（如 5G / Android / DeepSeek / Makalu）
        Regex("[A-Za-z][A-Za-z0-9_+.-]{1,18}").findAll(clean).forEach { m ->
            val tok = m.value.trim('.')
            if (tok.length >= 2 && tok.lowercase(Locale.ROOT) !in setOf("the", "and", "for", "with", "from", "this", "that")) {
                out.add(tok)
            }
        }
        // 2. 提取书名号/引号中的核心实体
        Regex("[《「“\"]([^》」”\"]{2,14})[》」”\"]").findAll(clean).forEach { m ->
            out.add(m.groupValues[1].trim())
        }
        // 3. 命中域关键词作为补充标签
        val lower = clean.lowercase(Locale.ROOT)
        for ((_, cues) in categoryRules) {
            for (cue in cues) {
                if (cue.length >= 2 && lower.contains(cue)) {
                    out.add(cue)
                    if (out.size >= limit) break
                }
            }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    /** 用向后兼容的方式补齐一条记忆 JSON 的结构化元数据字段。 */
    fun enrichRow(row: JSONObject): JSONObject {
        val text = row.optString("text", "").trim()
        val existingCat = parseCategory(row.optString("category", ""))
        val cat = existingCat ?: classify(text)
        row.put("category", cat.key)
        row.put("categoryLabel", cat.label)
        val pinned = row.optBoolean("pinned", false)
        val salience = if (row.has("salience")) {
            row.optInt("salience", if (pinned) 5 else 3).coerceIn(1, 5)
        } else {
            if (pinned) 5 else 3
        }
        row.put("pinned", pinned)
        row.put("salience", salience)
        val existingTags = row.optJSONArray("tags")
        if (existingTags == null || existingTags.length() == 0) {
            row.put("tags", JSONArray(extractTags(text)))
        }
        return row
    }

    fun isExpiredOrSuperseded(row: JSONObject, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (row.optString("supersededBy", "").isNotBlank()) return true
        val exp = row.optLong("expiresAtMs", 0L)
        return exp > 0L && exp < nowMs
    }

    /**
     * 混合重排检索：综合字面子串/二元组重合 + 分类/标签过滤 + 重要度（置顶/salience）+ 时间衰减。
     */
    fun hybridRank(
        rows: List<JSONObject>,
        query: String = "",
        category: String? = null,
        tag: String? = null,
        includeExpired: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 12,
    ): List<JSONObject> {
        val catFilter = parseCategory(category)?.key
        val tagFilter = tag?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val qNorm = query.trim().lowercase(Locale.ROOT)
        val qBigrams = if (qNorm.length >= 2) (0 until qNorm.length - 1).map { qNorm.substring(it, it + 2) }.toSet() else emptySet()

        return rows.mapNotNull { raw ->
            val row = enrichRow(JSONObject(raw.toString()))
            if (!includeExpired && isExpiredOrSuperseded(row, nowMs)) return@mapNotNull null
            if (catFilter != null && row.optString("category") != catFilter) return@mapNotNull null

            val text = row.optString("text", "")
            val textLower = text.lowercase(Locale.ROOT)
            val tagsArr = row.optJSONArray("tags")
            val tagsLower = buildList {
                if (tagsArr != null) {
                    for (i in 0 until tagsArr.length()) {
                        tagsArr.optString(i).takeIf(String::isNotBlank)?.let { add(it.lowercase(Locale.ROOT)) }
                    }
                }
            }
            if (tagFilter.isNotEmpty() && tagsLower.none { it.contains(tagFilter) } && !textLower.contains(tagFilter)) {
                return@mapNotNull null
            }

            var lexicalScore = 0.0
            if (qNorm.isNotEmpty()) {
                if (textLower.contains(qNorm)) {
                    lexicalScore = 1.0
                } else if (tagsLower.any { it.contains(qNorm) || qNorm.contains(it) }) {
                    lexicalScore = 0.78
                } else if (qBigrams.isNotEmpty()) {
                    val hits = qBigrams.count { textLower.contains(it) }
                    lexicalScore = (hits.toDouble() / qBigrams.size.toDouble()) * 0.72
                }
                if (lexicalScore < 0.16) return@mapNotNull null
            } else {
                lexicalScore = 0.5
            }

            val pinnedBonus = if (row.optBoolean("pinned", false)) 0.25 else 0.0
            val salienceScore = (row.optInt("salience", 3).coerceIn(1, 5) / 5.0) * 0.22
            val updatedMs = row.optLong("updatedAt", row.optLong("createdAt", nowMs))
            val ageDays = ((nowMs - updatedMs).coerceAtLeast(0L) / 86_400_000.0)
            val recencyScore = (1.0 / (1.0 + ageDays / 30.0)) * 0.12
            val totalScore = lexicalScore * 0.66 + salienceScore + recencyScore + pinnedBonus
            row to totalScore
        }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** 6 域全景速览：每个领域返回 Top-K 高权重记忆，让 Agent 1 轮看清用户画像。 */
    fun buildDigest(
        rows: List<JSONObject>,
        perCategory: Int = 3,
        nowMs: Long = System.currentTimeMillis(),
    ): JSONObject {
        val grouped = JSONObject()
        var activeTotal = 0
        for (cat in Category.values()) {
            val top = hybridRank(
                rows = rows,
                query = "",
                category = cat.key,
                includeExpired = false,
                nowMs = nowMs,
                limit = perCategory,
            )
            if (top.isNotEmpty()) {
                val arr = JSONArray()
                top.forEach { item ->
                    activeTotal++
                    arr.put(
                        JSONObject()
                            .put("id", item.optString("id"))
                            .put("text", item.optString("text"))
                            .put("pinned", item.optBoolean("pinned", false))
                            .put("salience", item.optInt("salience", 3))
                            .put("tags", item.optJSONArray("tags") ?: JSONArray()),
                    )
                }
                grouped.put(cat.key, JSONObject().put("label", cat.label).put("items", arr))
            }
        }
        return JSONObject()
            .put("activeSummaryCount", activeTotal)
            .put("categories", grouped)
    }
}
