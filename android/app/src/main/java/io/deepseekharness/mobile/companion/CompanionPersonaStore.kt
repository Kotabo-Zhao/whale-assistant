package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Character cards are independent of user memories and survive app updates. */
internal class CompanionPersonaStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("companion_personas_v1", Context.MODE_PRIVATE)

    private fun defaultCard() = JSONObject().put("id", "default").put("name", "鲸鱼娘")
        .put("description", "陪伴用户的手机助手").put("personality", "自然、温暖、认真；不确定时如实说明。")
        .put("scenario", "在手机桌面陪伴用户，按需协助查找资料和处理事务。")
        .put("examples", "").put("greeting", "嗨，想聊什么？")

    private fun read(): JSONObject {
        val raw = prefs.getString("cards", null)
        return if (raw == null) JSONObject().put("activeId", "default").put("cards", JSONArray().put(defaultCard())) else JSONObject(raw)
    }
    fun list(): JSONObject = synchronized(lock) { read() }
    fun active(): JSONObject = synchronized(lock) {
        val data = read(); val cards = data.getJSONArray("cards"); val activeId = data.getString("activeId")
        (0 until cards.length()).map { cards.getJSONObject(it) }.firstOrNull { it.getString("id") == activeId } ?: defaultCard()
    }
    fun save(input: JSONObject): JSONObject = synchronized(lock) {
        val data = read(); val cards = data.getJSONArray("cards")
        val requested = input.optString("id")
        val id = requested.ifBlank { UUID.randomUUID().toString() }
        require(id == "default" || runCatching { UUID.fromString(id) }.isSuccess)
        val card = JSONObject().put("id", id)
        for (field in listOf("name", "description", "personality", "scenario", "examples", "greeting", "systemPrompt", "postHistory", "worldBook")) {
            val value = input.optString(field).trim()
            require(value.length <= when (field) { "name" -> 80; "worldBook" -> 256000; else -> 16000 })
            if (field == "worldBook" && value.isNotBlank()) JSONObject(value)
            if (field == "name") require(value.isNotBlank())
            card.put(field, value)
        }
        val index = (0 until cards.length()).firstOrNull { cards.getJSONObject(it).getString("id") == id }
        if (index == null) cards.put(card) else cards.put(index, card)
        val reason = input.optString("lastRefineReason").trim()
        if (reason.isNotEmpty()) {
            card.put("lastRefineReason", reason.take(200))
            card.put("lastRefinedAt", System.currentTimeMillis())
        }
        persist(data)
        card
    }

    /** 根据对话增量修正当前激活（或指定）角色卡，只合并非空改动，绝不覆盖丢失玩家原有设定。 */
    fun refineActive(patch: JSONObject, targetId: String = ""): JSONObject = synchronized(lock) {
        val data = read()
        val cards = data.getJSONArray("cards")
        val resolvedId = targetId.ifBlank { data.getString("activeId") }
        val index = (0 until cards.length()).firstOrNull { cards.getJSONObject(it).getString("id") == resolvedId }
            ?: error("角色卡不存在")
        val card = JSONObject(cards.getJSONObject(index).toString())
        val textFields = listOf("name", "description", "personality", "scenario", "examples", "greeting", "systemPrompt", "postHistory")
        for (field in textFields) {
            val v = patch.optString(field).trim()
            if (v.isNotEmpty()) {
                require(v.length <= if (field == "name") 80 else 16000)
                card.put(field, v)
            }
        }
        val appendPersonality = patch.optString("appendPersonality").trim()
        if (appendPersonality.isNotEmpty()) {
            val cur = card.optString("personality").trim()
            val merged = if (cur.isEmpty()) appendPersonality else if (cur.contains(appendPersonality)) cur else "$cur\n$appendPersonality"
            card.put("personality", merged.take(16000))
        }
        val appendExample = patch.optString("appendExample").trim()
        if (appendExample.isNotEmpty()) {
            val cur = card.optString("examples").trim()
            val merged = if (cur.isEmpty()) appendExample else "$cur\n$appendExample"
            card.put("examples", merged.take(16000))
        }
        val wbKey = patch.optString("worldBookKey").trim()
        val wbContent = patch.optString("worldBookContent").trim()
        if (wbKey.isNotEmpty() && wbContent.isNotEmpty()) {
            val book = runCatching { JSONObject(card.optString("worldBook")) }.getOrElse { JSONObject() }
            val entries = book.optJSONArray("entries") ?: JSONArray()
            val keysArr = JSONArray()
            wbKey.split(Regex("[,，、\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { keysArr.put(it) }
            entries.put(JSONObject().put("keys", keysArr).put("content", wbContent.take(1000)).put("enabled", true))
            book.put("entries", entries)
            card.put("worldBook", book.toString().take(256000))
        }
        val reason = patch.optString("reason").trim().ifEmpty { "根据对话偏好微调角色卡" }
        card.put("lastRefineReason", reason.take(200))
        card.put("lastRefinedAt", System.currentTimeMillis())
        cards.put(index, card)
        persist(data)
        card
    }
    fun activate(id: String) = synchronized(lock) {
        val data = read(); val cards = data.getJSONArray("cards")
        require((0 until cards.length()).any { cards.getJSONObject(it).getString("id") == id })
        data.put("activeId", id); persist(data)
    }
    fun delete(id: String) = synchronized(lock) {
        val data = read(); val cards = data.getJSONArray("cards")
        require(cards.length() > 1)
        val index = (0 until cards.length()).firstOrNull { cards.getJSONObject(it).getString("id") == id } ?: error("角色卡不存在")
        cards.remove(index)
        if (data.getString("activeId") == id) data.put("activeId", cards.getJSONObject(0).getString("id"))
        persist(data)
    }
    private fun persist(value: JSONObject) { check(prefs.edit().putString("cards", value.toString()).commit()) }
    companion object { private val lock = Any() }
}
