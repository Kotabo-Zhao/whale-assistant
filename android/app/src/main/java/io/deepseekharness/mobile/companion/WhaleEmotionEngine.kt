package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 角色卡情感与性格养成引擎（Emotion & Personality Evolution Engine）。
 *
 * 将静态角色卡扩展为会随日常互动自然成长的情感生命体：
 * - **6 维性格向量（10–98 渐进演化）**：温暖（warmth）、俏皮（playfulness）、主动（proactivity）、
 *   直率（directness）、共情（empathy）、默契信任（trust）；
 * - **实时情绪态（Mood）与能量值**：随用户语气（致谢、疲惫倾诉、玩笑、专注干活、深夜陪伴）自然流动；
 * - **羁绊等级（Lv.1–Lv.10）与性格里程碑（growthNotes）**：持久化到每张角色卡独立槽位，
 *   通过动态上下文轻量注入（不污染静态 KV 前缀），让助手越聊越懂主人、越有独特个性。
 */
internal object WhaleEmotionEngine {

    enum class Mood(val label: String, val toneHint: String) {
        HAPPY("开心雀跃", "语气温润明快，带一点轻盈的喜悦感"),
        CALM("平静从容", "语气沉稳清晰，娓娓道来"),
        FOCUSED("专注高效", "直奔重点、条理利落，不废话"),
        CARING("温柔关切", "先接住情绪再给温和陪伴，语速放缓"),
        PLAYFUL("俏皮灵动", "可以适当带一点轻松幽默和俏皮互动"),
        CONCERNED("认真留意", "细心稳重，主动帮主人兜底排查"),
    }

    data class EmotionState(
        val personaId: String,
        val mood: Mood = Mood.CALM,
        val energy: Int = 78,
        val warmth: Int = 72,
        val playfulness: Int = 58,
        val proactivity: Int = 66,
        val directness: Int = 64,
        val empathy: Int = 70,
        val trust: Int = 35,
        val interactions: Int = 0,
        val growthNotes: List<String> = emptyList(),
        val updatedAtMs: Long = System.currentTimeMillis(),
    ) {
        val bondLevel: Int
            get() = (1 + (trust / 11)).coerceIn(1, 10)

        fun json(): JSONObject = JSONObject()
            .put("personaId", personaId)
            .put("mood", mood.name)
            .put("moodLabel", mood.label)
            .put("energy", energy)
            .put("bondLevel", bondLevel)
            .put("interactions", interactions)
            .put(
                "traits",
                JSONObject()
                    .put("warmth", warmth)
                    .put("playfulness", playfulness)
                    .put("proactivity", proactivity)
                    .put("directness", directness)
                    .put("empathy", empathy)
                    .put("trust", trust),
            )
            .put("growthNotes", JSONArray(growthNotes))
            .put("updatedAtMs", updatedAtMs)
    }

    private const val PREFS = "whale_emotion_engine_v1"
    private const val MAX_GROWTH_NOTES = 8

    private val praiseCues = listOf("谢谢", "感谢", "厉害", "真棒", "好棒", "太棒", "超棒", "漂亮", "好评", "喜欢", "爱你", "贴心", "舒服", "给力", "不错", "nice", "thanks", "great")
    private val tiredOrSadCues = listOf("好累", "太累", "真累", "累死", "累了", "疲惫", "崩溃", "难受", "好烦", "烦死", "心烦", "烦躁", "焦虑", "压力大", "失眠", "难过", "委屈", "加班", "头疼", "郁闷", "撑不住", "tired", "stressed")
    private val playfulCues = listOf("哈哈", "笑死", "呀哈", "摸摸", "贴贴", "夸夸", "逗我", "好玩", "陪我玩", "开玩笑", "段子", "可爱", "嘿嘿", "233", "hh")
    private val directWorkCues = listOf("直接", "简短", "别废话", "只要结论", "快点", "代码", "报错", "修复", "重构", "分析", "对比", "排错")
    private val frustrationCues = listOf("不行", "太差", "又报错", "笨", "丑", "不对", "失败", "没用", "好乱", "太乱", "乱套", "乱七八糟")

    fun defaultState(personaId: String, personaName: String = ""): EmotionState {
        val n = personaName.lowercase(Locale.US)
        return when {
            "乌萨奇" in personaName || "usagi" in n -> EmotionState(
                personaId = personaId, mood = Mood.PLAYFUL, energy = 90,
                warmth = 76, playfulness = 84, proactivity = 72, directness = 58, empathy = 68, trust = 40,
            )
            "极简" in personaName || "grok" in n -> EmotionState(
                personaId = personaId, mood = Mood.FOCUSED, energy = 80,
                warmth = 52, playfulness = 36, proactivity = 70, directness = 84, empathy = 56, trust = 35,
            )
            else -> EmotionState(
                personaId = personaId, mood = Mood.HAPPY, energy = 82,
                warmth = 78, playfulness = 64, proactivity = 70, directness = 62, empathy = 76, trust = 38,
            )
        }
    }

    /**
     * 纯确定性每轮性格演化（零额外 LLM 延迟与费用，便于纯 JVM 单测）：
     * 每次微调 ±1..3 保持性格稳定惯性，同时随长期互动塑造独特个性。
     */
    fun evolveFromTurn(
        current: EmotionState,
        userMessage: String,
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): EmotionState {
        val raw = userMessage.trim()
        if (raw.isEmpty()) return current
        val lower = raw.lowercase(Locale.US)

        var mood = current.mood
        var energy = current.energy
        var warmth = current.warmth
        var playfulness = current.playfulness
        var proactivity = current.proactivity
        var directness = current.directness
        var empathy = current.empathy
        var trust = (current.trust + 1).coerceAtMost(98)
        val notes = current.growthNotes.toMutableList()

        val hasPraise = praiseCues.any { it in lower }
        val hasTired = tiredOrSadCues.any { it in lower }
        val hasPlayful = playfulCues.any { it in lower }
        val hasDirect = directWorkCues.any { it in lower }
        val hasFrustration = frustrationCues.any { it in lower }
        val isLateNight = hourOfDay >= 23 || hourOfDay <= 5

        when {
            hasTired -> {
                mood = Mood.CARING
                warmth = (warmth + 2).coerceIn(10, 98)
                empathy = (empathy + 3).coerceIn(10, 98)
                playfulness = (playfulness - 1).coerceIn(10, 98)
                trust = (trust + 1).coerceAtMost(98)
            }
            hasFrustration -> {
                mood = Mood.CONCERNED
                directness = (directness + 2).coerceIn(10, 98)
                proactivity = (proactivity + 2).coerceIn(10, 98)
                playfulness = (playfulness - 2).coerceIn(10, 98)
            }
            hasPraise -> {
                mood = Mood.HAPPY
                energy = (energy + 5).coerceIn(25, 100)
                warmth = (warmth + 2).coerceIn(10, 98)
                trust = (trust + 2).coerceAtMost(98)
            }
            hasPlayful -> {
                mood = Mood.PLAYFUL
                energy = (energy + 4).coerceIn(25, 100)
                playfulness = (playfulness + 2).coerceIn(10, 98)
                warmth = (warmth + 1).coerceIn(10, 98)
            }
            hasDirect -> {
                mood = Mood.FOCUSED
                directness = (directness + 2).coerceIn(10, 98)
                proactivity = (proactivity + 1).coerceIn(10, 98)
            }
            else -> {
                mood = if (isLateNight) Mood.CARING else Mood.CALM
            }
        }

        if (isLateNight && !hasDirect) {
            warmth = (warmth + 1).coerceIn(10, 98)
            energy = (energy - 2).coerceIn(30, 100)
        }

        // 自然沉淀性格里程碑
        val nextCount = current.interactions + 1
        if (hasDirect && directness >= 78 && notes.none { "干脆利落" in it }) {
            notes.add("主人偏好干脆利落、直给结论的沟通风格")
        } else if (hasTired && empathy >= 78 && notes.none { "温柔陪伴" in it }) {
            notes.add("主人在疲惫或压力大时更需要温柔接住情绪")
        } else if (hasPlayful && playfulness >= 78 && notes.none { "轻松俏皮" in it }) {
            notes.add("和主人之间形成了轻松俏皮的日常互怼默契")
        }
        while (notes.size > MAX_GROWTH_NOTES) notes.removeAt(0)

        return current.copy(
            mood = mood,
            energy = energy,
            warmth = warmth,
            playfulness = playfulness,
            proactivity = proactivity,
            directness = directness,
            empathy = empathy,
            trust = trust,
            interactions = nextCount,
            growthNotes = notes,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun load(context: Context, personaId: String, personaName: String = ""): EmotionState {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("state_$personaId", null) ?: return defaultState(personaId, personaName)
        return runCatching {
            val o = JSONObject(raw)
            val t = o.optJSONObject("traits") ?: JSONObject()
            val notesArr = o.optJSONArray("growthNotes")
            val notes = mutableListOf<String>()
            if (notesArr != null) {
                for (i in 0 until notesArr.length()) {
                    notesArr.optString(i).takeIf(String::isNotBlank)?.let(notes::add)
                }
            }
            EmotionState(
                personaId = personaId,
                mood = runCatching { Mood.valueOf(o.optString("mood", "CALM")) }.getOrDefault(Mood.CALM),
                energy = o.optInt("energy", 78).coerceIn(10, 100),
                warmth = t.optInt("warmth", 72).coerceIn(10, 98),
                playfulness = t.optInt("playfulness", 58).coerceIn(10, 98),
                proactivity = t.optInt("proactivity", 66).coerceIn(10, 98),
                directness = t.optInt("directness", 64).coerceIn(10, 98),
                empathy = t.optInt("empathy", 70).coerceIn(10, 98),
                trust = t.optInt("trust", 35).coerceIn(10, 98),
                interactions = o.optInt("interactions", 0).coerceAtLeast(0),
                growthNotes = notes,
                updatedAtMs = o.optLong("updatedAtMs", System.currentTimeMillis()),
            )
        }.getOrElse { defaultState(personaId, personaName) }
    }

    fun save(context: Context, state: EmotionState) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("state_${state.personaId}", state.json().toString())
            .apply()
    }

    fun delete(context: Context, personaId: String) {
        if (personaId.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("state_$personaId")
            .apply()
    }

    fun observeTurn(context: Context, personaId: String, personaName: String, userMessage: String): EmotionState {
        val cur = load(context, personaId, personaName)
        val next = evolveFromTurn(cur, userMessage)
        save(context, next)
        return next
    }

    fun addGrowthNote(context: Context, personaId: String, personaName: String, note: String): EmotionState {
        val clean = note.trim().take(120)
        val cur = load(context, personaId, personaName)
        if (clean.isEmpty()) return cur
        val notes = cur.growthNotes.toMutableList()
        if (clean !in notes) notes.add(clean)
        while (notes.size > MAX_GROWTH_NOTES) notes.removeAt(0)
        val next = cur.copy(growthNotes = notes, updatedAtMs = System.currentTimeMillis())
        save(context, next)
        return next
    }

    /** 构造极简 3 行动态情感提示（放在动态区，不破坏静态 KV 前缀缓存）。 */
    fun buildDynamicPrompt(state: EmotionState): String = buildString {
        append("[当前情感与性格养成状态（请自然融入语气，切勿生硬报数值）]\n")
        append("- 心境：").append(state.mood.label).append("（").append(state.mood.toneHint)
            .append("）｜羁绊 Lv.").append(state.bondLevel).append("（已互动 ").append(state.interactions).append(" 轮）\n")
        val dominant = listOf(
            "温暖" to state.warmth,
            "俏皮" to state.playfulness,
            "主动" to state.proactivity,
            "干脆" to state.directness,
            "共情" to state.empathy,
        ).sortedByDescending { it.second }.take(3).joinToString("·") { it.first }
        append("- 当前养成性格主轴：").append(dominant)
        if (state.growthNotes.isNotEmpty()) {
            append("｜相处默契：").append(state.growthNotes.takeLast(2).joinToString("；"))
        }
    }

    /** 每满 12 轮有效互动触发一次后台轻量模型深度复盘，提炼个性化相处默契。 */
    fun shouldRunDeepReflection(state: EmotionState): Boolean =
        state.interactions > 0 && state.interactions % 12 == 0

    fun buildReflectionPrompt(
        personaName: String,
        recentUserMessages: List<String>,
        state: EmotionState,
    ): String = buildString {
        append("你是角色「").append(personaName.ifBlank { "鲸鱼娘" }).append("」的性格养成复盘模块。
")
        append("请根据以下最近几条用户消息，提炼 1 条具体的「主人沟通偏好或相处默契」（18~42 个中文字，直白自然，不要包含数值、编号或引号）。
")
        if (state.growthNotes.isNotEmpty()) {
            append("已有默契（请避免重复）：").append(state.growthNotes.takeLast(4).joinToString("；")).append("
")
        }
        append("近期用户消息：
")
        recentUserMessages.takeLast(8).forEachIndexed { idx, msg ->
            append("${idx + 1}. ").append(msg.trim().take(120)).append("
")
        }
        append("若近期消息过短或无明显新偏好，请只输出 NONE。")
    }

    fun parseReflectionNote(raw: String): String? {
        val clean = raw.trim()
            .lineSequence()
            .map { it.trim().trim('"', '“', '”', '「', '」', '-', '•', '*') }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        if (clean.equals("NONE", ignoreCase = true) || clean.length < 6) return null
        return clean.take(64)
    }
}
