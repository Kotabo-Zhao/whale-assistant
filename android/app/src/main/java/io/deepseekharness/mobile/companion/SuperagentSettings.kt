package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.runtime.RuntimeCredentialCipher
import org.json.JSONObject
import org.json.JSONArray

/** Source settings are separate from the encrypted credentials, and are disabled initially. */
internal class SuperagentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("superagent_sources_v1", Context.MODE_PRIVATE)
    private val cipher = RuntimeCredentialCipher()
    private val credentialNames = setOf("githubToken", "feishuAppId", "feishuAppSecret")

    private fun config(): JSONObject = JSONObject(prefs.getString("config", "{}") ?: "{}")
    private fun credentials(): JSONObject {
        val encrypted = prefs.getString("credentials", null) ?: return JSONObject()
        return JSONObject(String(cipher.decrypt(encrypted), Charsets.UTF_8))
    }
    fun status(): JSONObject {
        val secrets = credentials()
        return JSONObject().put("config", config()).put("unapproved", JSONArray(prefs.getString("unapproved", "[]") ?: "[]")).put("diagnostics", JSONObject(prefs.getString("diagnostics", "{}") ?: "{}")).put("credentials", JSONObject().apply {
            credentialNames.forEach { put(it, secrets.optString(it).isNotBlank()) }
        })
    }
    fun save(request: JSONObject): JSONObject {
        val next = request.getJSONObject("config")
        require(next.toString().length <= 32000)
        val secrets = credentials()
        request.optJSONObject("credentials")?.let { update ->
            for (name in credentialNames) if (update.has(name)) {
                val value = update.getString(name)
                require(value.length <= 2000)
                if (value.isBlank()) secrets.remove(name) else secrets.put(name, value)
            }
        }
        val encrypted = cipher.encrypt(secrets.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("config", next.toString()).putString("credentials", encrypted).commit())
        return status()
    }
    fun setUnapproved(request: JSONObject) {
        val actors = request.getJSONArray("actors")
        require(actors.length() <= 200)
        for (index in 0 until actors.length()) {
            val row = actors.getJSONObject(index)
            require(row.getString("source") in setOf("github", "feishu"))
            require(row.getString("actorId").length in 1..200)
        }
        val diagnostics = JSONObject().put("errors", request.optJSONObject("errors") ?: JSONObject())
            .put("problems", request.optJSONArray("problems") ?: JSONArray()).put("pendingReplies", request.optInt("pendingReplies", 0))
        require(diagnostics.toString().length <= 32000)
        // This is a display mirror; the guest is authoritative and re-sends it every pump.
        // Async apply() is fine here, and skipping identical snapshots avoids a sync write
        // every heartbeat (the previous code committed twice on every 5 s refresh).
        val nextActors = actors.toString()
        val nextDiagnostics = diagnostics.toString()
        if (prefs.getString("unapproved", "[]") == nextActors &&
            prefs.getString("diagnostics", "{}") == nextDiagnostics) return
        prefs.edit().putString("unapproved", nextActors).putString("diagnostics", nextDiagnostics).apply()
    }
    /** Only the token-authenticated loopback device bridge uses this result. */
    fun runtime(): JSONObject = JSONObject().put("config", config()).put("credentials", credentials())
}
