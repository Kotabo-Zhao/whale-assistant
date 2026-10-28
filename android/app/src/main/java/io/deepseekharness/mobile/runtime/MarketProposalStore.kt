package io.deepseekharness.mobile.runtime

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** A proposal never installs anything. One pending item must be reviewed or dismissed before replacement. */
internal data class MarketProposal(val id: String, val packageName: String, val createdAt: Long)
internal object MarketProposalPolicy {
    fun validName(name: String): Boolean = PluginInstallLock.isPackageName(name)
    fun parse(raw: String): MarketProposal {
        val json = JSONObject(raw)
        val id = json.opt("id") as? String ?: error("bad id")
        val name = json.opt("packageName") as? String ?: error("bad name")
        val created = json.opt("createdAt") as? Number ?: error("bad time")
        require(id.matches(Regex("[a-f0-9-]{36}")) && validName(name) && created.toLong() > 0)
        return MarketProposal(id, name, created.toLong())
    }
    fun encode(proposal: MarketProposal): String = JSONObject().put("id", proposal.id)
        .put("packageName", proposal.packageName).put("createdAt", proposal.createdAt).toString()
}
internal class MarketProposalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("market_proposals_v1", Context.MODE_PRIVATE)
    fun get(): MarketProposal? = synchronized(lock) {
        val raw = prefs.getString("pending", null) ?: return@synchronized null
        try { MarketProposalPolicy.parse(raw) } catch (e: Exception) { throw RuntimeFailure("MARKET_PROPOSAL_INVALID", "安装请求损坏，原数据已保留", e) }
    }
    fun propose(name: String): MarketProposal = synchronized(lock) {
        if (!MarketProposalPolicy.validName(name)) throw RuntimeFailure("MARKET_PROPOSAL_INVALID", "插件包名无效")
        val existing = get()
        if (existing != null) {
            if (existing.packageName == name) return@synchronized existing
            throw RuntimeFailure("MARKET_PROPOSAL_BUSY", "已有待处理安装请求，请先处理")
        }
        val proposal = MarketProposal(UUID.randomUUID().toString(), name, System.currentTimeMillis())
        if (!prefs.edit().putString("pending", MarketProposalPolicy.encode(proposal)).commit()) throw RuntimeFailure("MARKET_PROPOSAL_WRITE_FAILED", "无法保存安装请求")
        proposal
    }
    fun dismiss(id: String): Boolean = synchronized(lock) {
        val existing = get() ?: return@synchronized false
        if (existing.id != id) return@synchronized false
        if (!prefs.edit().remove("pending").commit()) throw RuntimeFailure("MARKET_PROPOSAL_WRITE_FAILED", "无法清除安装请求")
        true
    }
    companion object { private val lock = Any() }
}
