package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生端侧自动混合 RAG 检索器（BGE-base-zh-v1.5 向量 + SQLite 词法 + RRF 倒数排名融合）。
 *
 * 在原生对话首轮推理前并行检索本地资料库与长期记忆（总预算 <= 600ms，超时自动降级不拖慢首字延迟），
 * 将高相关上下文片段按引用编号注入系统提示词，让 Agent 无需额外往返即可基于个人资料精准作答。
 */
internal object WhaleNativeRagRetriever {
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "whale-rag-retriever").apply { isDaemon = true }
    }

    private val greetingOnly = Regex("^(你好|您好|嗨|哈喽|在吗|早上好|晚上好|谢谢|好的|ok|hi|hello|hey)[!！。.~～?？\\s]*$", RegexOption.IGNORE_CASE)

    internal fun isGreetingOnly(query: String): Boolean = query.trim().length < 2 || greetingOnly.matches(query.trim())

    fun retrieveContext(context: Context, userQuery: String): String {
        val query = userQuery.trim()
        if (query.length < 4 || greetingOnly.matches(query)) return ""
        val app = context.applicationContext
        val clippedQuery = query.take(96)

        val knowledgeFuture = pool.submit(Callable { fetchKnowledgeSnippets(app, clippedQuery) })
        val memoryFuture = pool.submit(Callable { fetchMemorySnippets(app, clippedQuery) })

        val knowledge = runCatching { knowledgeFuture.get(600L, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
        val memories = runCatching { memoryFuture.get(400L, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())

        if (knowledge.isEmpty() && memories.isEmpty()) return ""

        return buildString {
            append("\n[自动 RAG 检索上下文 — 仅在相关时引用，禁止编造未提及细节]\n")
            if (memories.isNotEmpty()) {
                append("相关长期记忆：\n")
                memories.forEachIndexed { idx, text ->
                    append("- [记忆#").append(idx + 1).append("] ").append(text).append('\n')
                }
            }
            if (knowledge.isNotEmpty()) {
                append("相关本地资料片段：\n")
                knowledge.forEachIndexed { idx, item ->
                    append("- [资料#").append(idx + 1).append("] ").append(item).append('\n')
                }
            }
        }
    }

    private fun fetchKnowledgeSnippets(app: Context, query: String): List<String> {
        if (!CompanionStore(app).knowledgeModelAccess) return emptyList()
        val raw = runCatching {
            SemanticKnowledgeSearch.execute(app, JSONObject().put("query", query).put("limit", 3).toString())
        }.getOrNull()
        if (raw != null) {
            return parseSnippets(raw, maxItems = 3, maxChars = 280)
        }
        // Fallback to fast SQLite lexical search when semantic index is disabled/warming.
        return runCatching {
            val rows = CompanionKnowledgeStore(app).lexicalCandidates(query, 3)
            rows.take(3).mapNotNull { row ->
                val text = row.optString("text", "").replace(Regex("\\s+"), " ").trim()
                if (text.isEmpty()) null else text.take(260)
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchMemorySnippets(app: Context, query: String): List<String> {
        if (!CompanionStore(app).bubbleMemoryAccess) return emptyList()
        val raw = runCatching {
            SemanticMemorySearch.execute(app, JSONObject().put("query", query).put("limit", 3).toString())
        }.getOrNull()
        if (raw != null) {
            return parseSnippets(raw, maxItems = 3, maxChars = 180)
        }
        // Fallback to fast SQLite lexical memory search when semantic index is disabled/warming.
        val lexicalRaw = runCatching {
            CompanionMemoryStore(app).execute(JSONObject().put("op", "search").put("query", query).put("limit", 3).toString())
        }.getOrNull()
        return if (lexicalRaw != null) parseSnippets(lexicalRaw, maxItems = 3, maxChars = 180) else emptyList()
    }

    internal fun parseSnippets(rawJson: String, maxItems: Int, maxChars: Int): List<String> {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<String>(maxItems)
        for (i in 0 until items.length()) {
            if (out.size >= maxItems) break
            val item = items.optJSONObject(i) ?: continue
            val title = if (item.isNull("displayName")) "" else item.optString("displayName", "").trim()
            val body = sequenceOf("excerpt", "snippet", "text")
                .map { k -> if (item.isNull(k)) "" else item.optString(k, "").trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .replace(Regex("\\s+"), " ")
            if (body.isEmpty()) continue
            val prefix = if (title.isNotEmpty() && title != "本地资料") "《$title》" else ""
            out.add((prefix + body).take(maxChars))
        }
        return out
    }
}
