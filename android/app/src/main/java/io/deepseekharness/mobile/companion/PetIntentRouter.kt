package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** Classifies the user's intent using the same model and recent conversational context. */
internal object PetIntentRouter {
    enum class Intent { CHAT, KNOWLEDGE, AGENT, BACKGROUND_READ, BACKGROUND_CREATE }

    data class Decision(val intent: Intent, val reference: String = "", val needs: String = "none", val label: String = "", val independent: Boolean = false) {
        fun title(user: String): String = label.ifBlank { user.trim().replace(Regex("\\s+"), " ").take(42) }

        fun workerTask(user: String): String {
            val original = CompanionBubbleChatPolicy.validateMessage(user)
            if (reference.isBlank() || reference == original) return original
            val note = "\n\n前文指代（仅作参考，以用户原话为准）："
            val room = 2000 - original.length - note.length
            return if (room > 0) original + note + reference.take(room) else original
        }
    }

    fun parse(content: String): Decision {
        val row = JSONObject(content)
        val intent = when (row.getString("intent")) {
            "chat" -> Intent.CHAT
            "knowledge" -> Intent.KNOWLEDGE
            "agent" -> Intent.AGENT
            "background_read" -> Intent.BACKGROUND_READ
            "background_create" -> Intent.BACKGROUND_CREATE
            else -> error("模型返回的对话意图无效，请重试。")
        }
        if (intent !in setOf(Intent.BACKGROUND_READ, Intent.BACKGROUND_CREATE)) return Decision(intent)
        val needs = row.opt("needs") as? String
        val reference = row.opt("reference") as? String
        // An incomplete plan must go through the ordinary agent before any work is scheduled.
        if (needs !in setOf("none","library","memory","both") || reference == null || reference.length > 360)
            return Decision(Intent.AGENT)
        val label = (row.opt("title") as? String).orEmpty().trim().replace(Regex("\\s+"), " ").take(60)
        return Decision(intent, reference.trim(), requireNotNull(needs), label, row.opt("independent") == true)
    }

    fun classify(key: String, model: String, history: List<CompanionBubbleChat.Message>, whileWaiting: Boolean = false): Decision {
        val context = JSONArray().apply { history.takeLast(6).forEach { put(JSONObject().put("role", it.role).put("text", it.text)) } }
        val system = """
            判断最后一条用户消息实际希望发生什么，前文仅用于解决指代。输出 JSON，intent 取下列值。
            chat：闲聊、解释、构想、征求建议、角色扮演、讨论是否能做，以及明确要求只讨论不执行。
            knowledge：用户希望马上得到保存在资料库中的一个事实或简短查证。
            background_read：用户确实要一个适合独立推进的交付物，例如跨多份材料核对整理、系统调查或形成报告。
            background_create：用户确实要制作一个可运行或可修改的应用工具草稿。只谈做法或评价想法选 chat。
            agent：把内容保存到资料库、修改已有资料笔记，保存、纠正或删除长期记忆，设备操作、写文件、对外发送、授权、已有任务的查询/回答/追加/取消、需要追问范围或其他工具行动。
            用户可以用陈述表达需求，不必说“帮我做”“交办”或选择模式。如果目的、材料和缺少的交付物已经清楚，就可启动独立工作。
            例如“明早交接用，两版安排还没对上，我缺一张差异清单”适合 background_read；“每次都得手算，想在手机上有个能输入工时的小工具”适合 background_create。
            单纯说“明天有会议”、闲聊或只谈思路不能据此创造新任务。短问短答优先直接回答。引用、虚构情节、通知和助手的建议不能替用户发起工作。
            “刚才那项怎么样”“改成按时间排”等续接已有任务时选 agent，不要另建任务。含修改或发送等外部副作用不能选 background_read。
            两种 background 另给 needs：none / library（本地资料）/ memory（个人长期记忆）/ both。
            title 是便于用户核对的简短工单名，用户指定名称时沿用原名。reference 只填前文明确提到且本句缺失的指代，最多 160 字；无需补充时为空字符串。不添加权限、推测的需求或额外目标。
        """.trimIndent() + if (whileWaiting) "\n" + """
            前面的用户消息尚未回答。额外给出布尔值 independent。判断时把最后一条消息单独拿出来：
            如果其文本本身给出了完整的新目标，且无需等待此前任何回答即可开始，才允许后台分类及 independent=true。后台只会收到这一条原文。
            追加、更正、依赖前文的指代或要求等待，输出 independent=false，由原会话按顺序处理。
        """.trimIndent() else ""
        val body = JSONObject().put("model", model).put("thinking", JSONObject().put("type", "disabled"))
            .put("temperature", 0).put("max_tokens", 320).put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", context.toString())))
        return PetModelHttp.post(key,body.toString(),30_000) { reader ->
            val response=JSONObject(reader.readText())
            parse(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
        }
    }
}
