package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
import java.security.MessageDigest

internal object CompanionKnowledgeWrite {
    // Read revisions normalize whitespace and case. Editing needs to detect either changing.
    fun revision(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(CompanionKnowledgeData.utf8(text)).joinToString("") { "%02x".format(it) }

    fun editable(row: JSONObject): Boolean = row.optString("source") in setOf("shared_text", "text_file")
        && row.optString("documentId").isEmpty() && row.optString("originalId").isEmpty()

    private fun invalid(): Nothing = throw RuntimeFailure("KNOWLEDGE_INPUT_INVALID", "请提供有效的文字笔记，单条上限为 8000 字")

    fun execute(context: Context, param: String): JSONObject {
        if (param.length > 50_000) invalid()
        val request = try { JSONObject(param) } catch (_: org.json.JSONException) { invalid() }
        val access = CompanionAgentAccess(context, request)
        access.requireKnowledgeWrite()
        val text = (request.opt("text") as? String)?.takeIf {
            it.isNotBlank() && it.length <= CompanionKnowledgeData.MAX_RECORD_TEXT && '\u0000' !in it
        } ?: invalid()
        val nextRevision = try { revision(text) } catch (_: java.nio.charset.CharacterCodingException) { invalid() }
        val knowledge = CompanionKnowledgeStore(context)
        val id = when (request.optString("op")) {
            "save" -> knowledge.saveText(text, "text_file", null).recordIds.single()
            "update" -> {
                access.requireKnowledge()
                val id = (request.opt("id") as? String)?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: invalid()
                val expected = (request.opt("editRevision") as? String)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: invalid()
                val row = knowledge.findById(id) ?: throw RuntimeFailure("KNOWLEDGE_NOT_FOUND", "资料已删除或不可用")
                if (!editable(row)) throw RuntimeFailure("KNOWLEDGE_READ_ONLY", "仅可修改独立文字笔记")
                val current = row.getString("text")
                if (current != text) {
                    if (revision(current) != expected) throw RuntimeFailure("KNOWLEDGE_CHANGED", "笔记已变化，请重新读取")
                    val updated = try { knowledge.edit(id, current, text) } catch (_: IllegalArgumentException) {
                        throw RuntimeFailure("KNOWLEDGE_DUPLICATE", "已有相同正文的资料")
                    }
                    if (!updated) throw RuntimeFailure("KNOWLEDGE_CHANGED", "笔记已变化，请重新读取")
                }
                id
            }
            else -> invalid()
        }
        return JSONObject().put("saved", true).put("id", id).put("editRevision", nextRevision)
    }
}
