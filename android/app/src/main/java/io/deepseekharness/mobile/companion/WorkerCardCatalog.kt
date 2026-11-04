package io.deepseekharness.mobile.companion

import android.util.AtomicFile
import android.util.LruCache
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Private native mirrors stay valid JSON. The bounded first line is sufficient to index a new mirror. */
internal class WorkerCardCatalog(private val dir: File) {
    private data class Entry(val id: String, val owner: String, val state: String, val group: String,
        val title: String, val order: Long, val dependencies: List<String>)
    private val entries = HashMap<String, Entry>()
    private var sorted: List<Entry>? = null
    private val stamps = HashMap<String,Long>()
    private val activeOwners = HashMap<String,String>()
    private var fileOrder: List<String>? = null
    private var discovered = false
    private var loaded = false
    private val cards = object : LruCache<String, String>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: String) = 2 * (key.length + value.length)
    }

    fun remember(file: File, card: JSONObject) {
        val id = card.getString("id")
        val deps = card.optJSONArray("dependsOn")
        val modified = file.lastModified()
        if (stamps.put(id,modified) != modified) fileOrder = null
        entries[id] = Entry(id, card.optString("owner"), card.optString("state"), card.optString("group"),
            card.optString("title"), modified, (0 until (deps?.length() ?: 0)).map { deps!!.getString(it) })
        activeOwners[id] = ownerOf(card)
        cards.put(id, card.toString())
        sorted = null
    }

    private fun discover() {
        if (discovered) return
        val names = checkNotNull(dir.list()) { "任务目录无法读取" }
        for (name in names) {
            val base = name.removeSuffix(".bak")
            if (!base.endsWith(".json") || base == "commands.json") continue
            val id = base.removeSuffix(".json")
            val file = File(dir,base)
            stamps[id] = if (file.isFile) file.lastModified() else File(dir,name).lastModified()
        }
        discovered = true
    }

    private fun card(id: String): JSONObject {
        cards.get(id)?.let { return JSONObject(it) }
        val file = File(dir,"$id.json")
        val item = try {
            readCard(file).also { check(it.getString("id") == id) }
        } catch (_: Exception) {
            JSONObject().put("id",id).put("text","无法读取的工单").put("title","无法读取的工单")
                .put("state","unreadable").put("recordUnreadable",true).put("missingLedger",true)
                .put("uiError","任务记录暂时无法读取，可刷新重试。")
        }
        remember(file,item)
        if (item.optBoolean("recordUnreadable")) cards.remove(id)
        return item
    }

    private fun load() {
        if (loaded) return
        discover()
        for (id in stamps.keys.toList()) if (id !in entries) card(id)
        loaded = true
    }

    fun groups(): JSONArray {
        load()
        return JSONArray(entries.values.map { it.group }.filter { it.isNotBlank() }.toSortedSet().toList())
    }

    private fun ownerOf(card: JSONObject) =
        if (card.optString("state") in OWNER_ACTIVE) card.optString("owner") else ""

    fun activeFor(owner: String): Boolean {
        if (owner.isEmpty()) return false
        if (activeOwners.containsValue(owner)) return true
        discover()
        for (id in stamps.keys) {
            if (id in activeOwners) continue
            val activeOwner = runCatching { ownerOf(readCard(File(dir,"$id.json"), ownerOnly = true)) }.getOrDefault("")
            activeOwners[id] = activeOwner
            if (activeOwner == owner) return true
        }
        return false
    }

    fun page(guestDir: File, pending: JSONArray, pendingCount: Int, filter: String, group: String, offset: Int, limit: Int, withCounts: Boolean): JSONObject {
        require(filter in setOf("all", "open", "running", "attention", "finished") && offset >= 0 && limit in 0..100)
        if (!withCounts && filter == "all" && group.isEmpty()) {
            // The landing page needs only filenames, modification times and the visible cards.
            discover()
            val order = fileOrder ?: stamps.keys.sortedWith(compareByDescending<String> { stamps[it] }.thenBy { it }).also { fileOrder = it }
            val items = JSONArray()
            val guestPresent = guestDir.isDirectory
            for (index in offset until minOf(order.size.toLong(), offset.toLong() + limit).toInt()) {
                val id = order[index]
                val item = card(id)
                if (guestPresent && !File(guestDir,"$id.json").isFile) markMissing(item)
                items.put(item)
            }
            return JSONObject().put("items",items).put("total",order.size).put("offset",offset).put("submissions",pending)
        }
        load()
        val order = if (limit == 0) entries.values else sorted ?: entries.values.sortedWith(compareByDescending<Entry> { it.order }.thenBy { it.id }).also { sorted = it }
        val guestPresent = guestDir.isDirectory
        val files = if (guestPresent) guestDir.listFiles { f -> f.name.endsWith(".json") && f.isFile }?.mapTo(hashSetOf()) { it.name } else null
        fun missing(id: String) = guestPresent && (files?.contains("$id.json") ?: File(guestDir,"$id.json").isFile) == false
        fun dependencyBlocked(row: Entry) = row.dependencies.any { id ->
            val parent = entries[id]
            parent == null || missing(id) || parent.state in setOf("failed", "blocked", "cancelled")
        }
        var running = pendingCount; var waiting = 0; var asking = 0; var total = 0
        val items = JSONArray()
        for (entry in order) {
            val absent = missing(entry.id)
            val blocked = entry.state == "queued" && dependencyBlocked(entry)
            val work = !absent && entry.state in RUNNING && !blocked
            val attention = !absent && (entry.state in ATTENTION || blocked)
            if (work) running++
            if (attention) waiting++
            if (!absent && entry.state == "waiting") asking++
            if (group.isNotEmpty() && group != entry.group) continue
            val matches = when (filter) {
                "open" -> work || attention
                "running" -> work
                "attention" -> attention
                "finished" -> !work && !attention
                else -> true
            }
            if (!matches) continue
            if (total++ < offset || items.length() >= limit) continue
            val row = card(entry.id)
            if (absent) markMissing(row)
            if (entry.dependencies.isNotEmpty()) {
                val remaining = entry.dependencies.filter { entries[it]?.state != "completed" }
                row.put("blockedDependency", blocked).put("remainingDependencies",remaining.size)
                    .put("dependencyTitles", JSONArray(remaining.mapNotNull { entries[it]?.title }.take(10)))
            }
            items.put(row)
        }
        return JSONObject().put("items",items).put("total",total).put("offset",offset)
            .put("submissions",pending).put("running",running).put("attention",waiting).put("asking",asking)
    }

    private fun readCard(file: File, ownerOnly: Boolean = false): JSONObject {
        AtomicFile(file).openRead().buffered().use { input ->
            input.mark(MAX_HEADER + PREFIX.size + 2)
            if (PREFIX.all { input.read() == (it.toInt() and 255) }) {
                val bytes = java.io.ByteArrayOutputStream()
                while (bytes.size() <= MAX_HEADER) {
                    val byte = input.read()
                    if (byte == '\n'.code) {
                        val card = JSONObject(bytes.toString("UTF-8"))
                        if (card.optString("id") == file.nameWithoutExtension) return card
                        break
                    }
                    if (byte < 0) break
                    bytes.write(byte)
                }
            }
            input.reset()
            // Older mirrors have no first-line projection. Skip entire history values without materializing them.
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                val metadata = JSONObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (if (ownerOnly) key == "owner" || key == "state" else key in cardFields) {
                        metadata.put(key, readValue(reader))
                        if (ownerOnly && metadata.has("owner") && metadata.has("state")) return metadata
                    } else reader.skipValue()
                }
                reader.endObject()
                return if (ownerOnly) metadata else compact(metadata)
            }
        }
    }

    companion object {
        private const val HEADER = "_whaleWorkerCardV1"
        private val PREFIX = "{\"$HEADER\":".toByteArray(StandardCharsets.UTF_8)
        private const val MAX_HEADER = 64 * 1024
        private val RUNNING = setOf("queued","starting","running","cancelling")
        private val ATTENTION = setOf("waiting","failed","interrupted","blocked")
        private val OWNER_ACTIVE = setOf("queued","starting","running","waiting","blocked")
        private val fields = listOf("id","owner","title","sessionId","state","mode","group","source","priority","authorizationRevoked",
            "authoredDraft","dependsOn","writeSeq","updatedAt","createdAt","requester")
        private val previews = listOf("scope","decisionReason","decisionEvidence","progress","summary","question","uiError","waitReason")

        private val cardFields = (fields + previews).toSet()

        private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray(); while (reader.hasNext()) put(readValue(reader)); reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject(); while (reader.hasNext()) put(reader.nextName(),readValue(reader)); reader.endObject()
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull() ?: it }
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> reader.nextString()
        }

        fun full(file: File) = AtomicFile(file).openRead().bufferedReader().use { JSONObject(it.readText()).apply { remove(HEADER) } }
        fun markMissing(row: JSONObject) {
            if (row.optBoolean("recordUnreadable")) return
            row.put("missingLedger", true).put("uiError","运行环境中找不到任务执行记录；已保存的会话仍可查看，无法从此卡继续。")
        }
        fun compact(row: JSONObject): JSONObject {
            val card = JSONObject()
            for (field in fields) if (row.has(field)) card.put(field,row.get(field))
            card.put("text",row.optString("title"))
            for (field in previews) if (row.has(field) && !row.isNull(field)) {
                val value = row.optString(field)
                card.put(field, if (value.length <= 400) value else value.take(399).dropLastWhile { it.isHighSurrogate() } + "…")
            }
            return card
        }
        fun write(file: File, row: JSONObject): JSONObject {
            row.remove(HEADER)
            val card = compact(row)
            val header = card.toString().toByteArray()
            val bytes = row.toString().toByteArray()
            val target = AtomicFile(file)
            val output = target.startWrite()
            try {
                if (header.size <= MAX_HEADER) {
                    // Insert a projection as the first property of the original JSON object.
                    output.write(PREFIX); output.write(header); output.write('\n'.code); output.write(','.code)
                    output.write(bytes,1,bytes.size-1)
                } else output.write(bytes)
                target.finishWrite(output)
            } catch (e: Exception) { target.failWrite(output); throw e }
            return card
        }
    }
}
