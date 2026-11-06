package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.OverlayBallService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Persist changes while locked/offline and only consume the versions actually scanned. */
internal object ContextFollowupEvents {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("companion_proactive_chat_v1", Context.MODE_PRIVATE)
    private fun changes(ctx: Context) = JSONObject(prefs(ctx).getString("changes", "{}")!!)
    private fun rows(ctx: Context, key: String): List<JSONObject> {
        val rows = JSONArray(prefs(ctx).getString(key, "[]")!!)
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    private fun delivered(ctx: Context): List<JSONObject> = rows(ctx, "delivered_matters")
    fun staged(ctx: Context): List<JSONObject> = rows(ctx, "context_proposals")
    fun proposed(ctx: Context): JSONObject? = staged(ctx).firstOrNull()

    // Older versions kept only the last unanswered question, without a matter title.
    private fun known(ctx: Context): List<JSONObject> {
        val saved = delivered(ctx)
        val opening = prefs(ctx).getString("opening", null)?.let(::JSONObject)
        val legacy = opening?.optJSONObject("context")?.takeIf { !opening.has("matter") }?.let {
            JSONObject().put("matter", opening.getString("text")).put("note", it.getString("id"))
        }
        return listOfNotNull(legacy) + saved + staged(ctx)
    }

    fun notes(ctx: Context, ids: List<String>) = signal(ctx, ids.map { "note:$it" })
    fun chat(ctx: Context, key: String) = signal(ctx, listOf(key))

    @Synchronized private fun signal(ctx: Context, keys: List<String>) {
        if (keys.isEmpty() || !PetContextFollowup.enabled(ctx)) return
        val prefs = prefs(ctx)
        val version = prefs.getLong("change_version", 0) + 1
        val pending = changes(ctx)
        keys.forEach { pending.put(it, version) }
        // A large import is one catch-up pass, not thousands of model requests.
        val bounded = if (pending.length() <= 256) pending else JSONObject().put("all", version)
        prefs.edit().putString("changes", bounded.toString()).putLong("change_version", version).apply()
        OverlayBallService.wakeForContextChange(ctx)
    }

    @Synchronized fun batch(ctx: Context): Map<String, Long> {
        val rows = changes(ctx)
        return rows.keys().asSequence().associateWith { rows.getLong(it) }
    }

    @Synchronized fun finish(ctx: Context, scanned: Map<String, Long>) {
        val rows = changes(ctx)
        scanned.forEach { (key, version) -> if (rows.optLong(key, -1) == version) rows.remove(key) }
        prefs(ctx).edit().putString("changes", rows.toString()).apply()
    }

    @Synchronized fun cancel(ctx: Context) {
        val prefs = prefs(ctx)
        prefs.edit().remove("changes").remove("context_proposals").remove("matter_events").remove("last_matter_reply").putLong("context_epoch", prefs.getLong("context_epoch", 0) + 1).apply()
    }

    private fun normalized(text: String) = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
    private fun nearlySame(a: String, b: String): Boolean {
        if (normalized(a) == normalized(b)) return true
        val x = CompanionMemorySearch.terms(a)
        val y = CompanionMemorySearch.terms(b)
        return minOf(x.size, y.size) >= 4 && (x intersect y).size * 100 >= (x union y).size * 72
    }

    /** Use relevant titles from earlier or queued hints so simultaneous updates cannot enqueue the same matter twice. */
    fun priorMatters(ctx: Context, evidence: PetContextFollowup.Evidence): List<String> {
        val terms = CompanionMemorySearch.terms(evidence.chat.turns.joinToString(" ") { it.text } + " " + evidence.excerpt)
        return known(ctx).asReversed().filter { it.optString("note") == evidence.id ||
            (CompanionMemorySearch.relatedScore(it.getString("matter"), terms) != null &&
                CompanionKnowledgeStore(ctx).findById(it.optString("note")) != null)
        }.take(8).map { it.getString("matter") }
    }

    fun decide(ctx: Context, evidence: PetContextFollowup.Evidence, model: CompanionJsonModel, available: () -> Boolean): JSONObject? {
        val reference = evidence.reference()
        val epoch = prefs(ctx).getLong("context_epoch", 0)
        fun valid() = prefs(ctx).getLong("context_epoch", 0) == epoch && available() && !CompanionStore(ctx).inQuietHours() && PetContextFollowup.current(ctx, reference)
        if (!valid() || evidence.signature in PetInitiative.consideredContexts(ctx)) return null
        val past = priorMatters(ctx, evidence)
        val ownerContext=OwnerContext(ctx);val owner=ownerContext.read()
        val response = model.complete(PetContextFollowup.prompt(evidence, past, owner), 520)
        val result = runCatching { JSONObject(response) }.getOrNull()
        val question = PetContextFollowup.question(response, evidence)
        val matter = result?.optString("matter").orEmpty().trim()
        val proposal = if (question != null && matter.length in 3..64 && !CompanionStore.sensitive(matter) &&
            matter.none(Char::isISOControl)) {
            JSONObject().put("matter", matter).put("text", question).put("context", reference).put("note", evidence.id)
                .put("concern", CompanionBubbleChatPolicy.memoryDigest(normalized(result!!.getString("chatQuote"))))
                .put("signature", evidence.signature).put("fact", CompanionBubbleChatPolicy.memoryDigest(normalized(result.getString("noteQuote"))))
        } else null
        if (!valid() || !ownerContext.isCurrent(owner)) return null
        synchronized(this) {
            if (!PetContextFollowup.enabled(ctx) || prefs(ctx).getLong("context_epoch", 0) != epoch) return null
            val prefs = prefs(ctx)
            val seen = PetInitiative.consideredContexts(ctx)
            if (evidence.signature in seen) return null
            val edit = prefs.edit().putString("considered_contexts", (seen + evidence.signature).distinct().takeLast(512).joinToString(","))
            val duplicate = proposal != null && known(ctx).any {
                nearlySame(it.getString("matter"), proposal.getString("matter")) ||
                    (it.optString("fact") == proposal.getString("fact") && (it.optString("concern") == proposal.getString("concern") ||
                        (it.optString("note") == evidence.id && nearlySame(it.optString("text"), proposal.getString("text")))))
            }
            val accepted = proposal?.takeUnless { duplicate }
            if (accepted != null) edit.putString("context_proposals", JSONArray(staged(ctx) + accepted).toString())
            edit.putString("status", if (accepted == null) "已判断新线索，本次保持安静" else "发现一项值得跟进的新进展")
                .putLong("status_at", System.currentTimeMillis()).apply()
            return accepted
        }
    }

    @Synchronized fun discard(ctx: Context, proposal: JSONObject) {
        val signature = proposal.getString("signature")
        val remaining = staged(ctx).filterNot { it.getString("signature") == signature }
        prefs(ctx).edit().putString("context_proposals", JSONArray(remaining).toString()).putString("considered_contexts",
            PetInitiative.consideredContexts(ctx).filterNot { it == signature }.joinToString(",")).apply()
        notes(ctx, listOf(proposal.getString("note")))
    }

    private fun matterEvent(opening: JSONObject, kind: String, text: String) = JSONObject()
        .put("id",opening.getString("matterId")).put("eventId",java.util.UUID.randomUUID().toString())
        .put("kind",kind).put("title",opening.getString("matter")).put("text",text.take(2000))
        .put("noteId",opening.getString("note")).put("at",System.currentTimeMillis())

    @Synchronized fun answered(ctx: Context, text: String) {
        if(!PetContextFollowup.enabled(ctx)) return
        val opening=prefs(ctx).getString("opening",null)?.let(::JSONObject) ?: return
        if(!opening.has("context") || !opening.has("matterId")) return
        val event=matterEvent(opening,"reply",text)
        check(prefs(ctx).edit().putString("matter_events",JSONArray((rows(ctx,"matter_events")+event).takeLast(64)).toString())
            .putString("last_matter_reply",JSONObject(opening.toString()).put("reply",text).toString()).commit())
    }
    @Synchronized fun matterForTask(ctx: Context, text: String): String? {
        if(!PetContextFollowup.enabled(ctx)) return null
        val value=prefs(ctx).getString("last_matter_reply",null)?.let(::JSONObject) ?: return null
        if(System.currentTimeMillis()-value.optLong("time") !in 0L..86_400_000L) return null
        return value.optString("matterId").takeIf { it.isNotBlank() && text.contains(value.getString("text")) }
    }
    @Synchronized fun pendingMatters(ctx: Context): JSONArray =
        if(PetContextFollowup.enabled(ctx)) JSONArray(rows(ctx,"matter_events").take(16)) else JSONArray()
    @Synchronized fun acknowledgeMatter(ctx: Context, id: String) {
        check(prefs(ctx).edit().putString("matter_events",JSONArray(rows(ctx,"matter_events").filterNot { it.getString("eventId")==id }).toString()).commit())
    }

    @Synchronized fun shown(ctx: Context, proposal: JSONObject) {
        val remaining = staged(ctx).filterNot { it.getString("signature") == proposal.getString("signature") }
        val matterId = CompanionBubbleChatPolicy.memoryDigest(normalized(proposal.getString("matter")) + "|" + proposal.getString("note"))
        val entry = JSONObject(proposal.toString()).put("matterId",matterId).apply { remove("context"); remove("signature") }
        val opening = JSONObject(proposal.toString()).put("matterId",matterId).put("time", System.currentTimeMillis())
        check(prefs(ctx).edit().putString("delivered_matters", JSONArray((delivered(ctx) + entry).takeLast(256)).toString())
            .putString("opening", opening.toString()).putString("context_proposals", JSONArray(remaining).toString())
            .putString("matter_events",JSONArray((rows(ctx,"matter_events") + matterEvent(opening,"shown",opening.getString("text"))).takeLast(64)).toString())
            .putString("status", "已就新进展主动提问").putLong("status_at", System.currentTimeMillis()).commit())
    }
}
