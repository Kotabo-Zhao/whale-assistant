package io.deepseekharness.mobile.features

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class NativeFeatureRegistry(private val context: Context) {
    val prefs = context.getSharedPreferences("native-feature-library", Context.MODE_PRIVATE)
    data class Entry(val id: String, val title: String, val revision: String, val pkg: String, val hash: String)
    private val activity = "io.deepseekharness.candidate.PreviewActivity"
    private val digest = Regex("[a-f0-9]{64}")

    fun parse(raw: String): Entry {
        val json = JSONObject(raw)
        require(json.getString("contract") == "whale-native-screen-v1" && json.getString("activity") == activity)
        val revision = json.getString("revision")
        val hash = json.getString("apkSha256")
        val pkg = json.getString("package")
        require(revision.matches(digest) && hash.matches(digest) && pkg == "io.deepseekharness.candidate.f" + revision.take(24))
        val title = json.getString("title").trim()
        require(title.length in 1..50 && title.none { it.isISOControl() })
        val id = json.optString("featureId", revision)
        require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")) || id == revision)
        return Entry(id,title,revision,pkg,hash)
    }

    fun verify(entry: Entry): Intent {
        val pm = context.packageManager
        val info = pm.getPackageInfo(entry.pkg, PackageManager.GET_PERMISSIONS)
        val app = requireNotNull(info.applicationInfo)
        require(info.sharedUserId == null && app.uid != context.applicationInfo.uid && app.splitSourceDirs.isNullOrEmpty())
        require(info.requestedPermissions.isNullOrEmpty())
        val launch = Intent("io.deepseekharness.mobile.action.NATIVE_FEATURE").setPackage(entry.pkg)
        val resolved = requireNotNull(pm.resolveActivity(launch, PackageManager.MATCH_DEFAULT_ONLY)).activityInfo
        require(resolved.packageName == entry.pkg && resolved.name == activity && resolved.exported && resolved.enabled)
        val sha = MessageDigest.getInstance("SHA-256")
        File(app.sourceDir).inputStream().use { stream ->
            val buf = ByteArray(32768)
            while (true) {
                val count = stream.read(buf)
                if (count < 0) break
                sha.update(buf, 0, count)
            }
        }
        require(sha.digest().joinToString("") { "%02x".format(it) } == entry.hash)
        return launch.setComponent(ComponentName(entry.pkg, activity)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun entries(): List<Entry> = prefs.all.entries.filter { it.key.startsWith("rev.") }
        .mapNotNull { runCatching { parse(it.value as String) }.getOrNull() }.sortedWith(
            compareBy<Entry> { it.title }.thenByDescending { prefs.getLong("order." + it.revision,0) }.thenBy { it.revision })

    fun import(raw: String) {
        val entry = parse(raw)
        verify(entry)
        synchronized(lock) {
            val key = "rev." + entry.revision
            val old = prefs.getString(key, null)
            require(old == null || parse(old) == entry)
            require(old != null || entries().size < 64)
            if (old == null) {
                val order = prefs.getLong("lastOrder",0) + 1
                check(prefs.edit().putString(key,raw).putLong("order." + entry.revision,order).putLong("lastOrder",order).commit())
            }
        }
    }
    fun active(entry: Entry) = prefs.getString("active." + entry.id, null) == entry.revision

    fun select(entry: Entry, expected: String?, enable: Boolean) = synchronized(lock) {
        require(prefs.getString("rev." + entry.revision, null)?.let(::parse) == entry)
        require(prefs.getString("active." + entry.id, null) == expected)
        if (enable) verify(entry) else require(expected == entry.revision)
        val selected = if (enable) entry.revision else null
        val edit = prefs.edit().putString("active." + entry.id, selected)
        if (expected != null && expected != selected) edit.putString("previous." + entry.id,expected)
        check(edit.commit())
    }

    fun openFromPet(entry: Entry) = synchronized(lock) {
        check(active(entry))
        context.startActivity(verify(entry))
    }

    private companion object { val lock = Any() }
}
