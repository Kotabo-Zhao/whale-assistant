package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure

/** Cancellation can arrive before the request queued for execution. Never revive an old id. */
internal class ScreenSequenceTickets(private val now:()->Long) {
    private data class Ticket(val at:Long,var state:String)
    private val tickets=LinkedHashMap<String,Ticket>()
    private fun valid(id:String) {if(!Regex("[a-f0-9-]{36}").matches(id)) throw RuntimeFailure("SCREEN_INPUT_INVALID","操作编号无效")}
    private fun room() {
        tickets.entries.removeAll{it.value.state!="running" && now()-it.value.at>600000}
        if(tickets.size>=256) throw RuntimeFailure("SCREEN_SEQUENCE_BUSY","短时间操作过多，请稍后继续")
    }
    @Synchronized fun start(id:String) {
        valid(id)
        if(tickets.containsKey(id)) throw RuntimeFailure("SCREEN_SEQUENCE_CANCELLED","该操作已撤销或已执行，请重新观察")
        room();tickets[id]=Ticket(now(),"running")
    }
    @Synchronized fun cancel(id:String) {
        valid(id)
        val ticket=tickets[id]
        if(ticket!=null) ticket.state="cancelled"
        else {room();tickets[id]=Ticket(now(),"cancelled")}
    }
    @Synchronized fun check(id:String) {
        if(tickets[id]?.state!="running") throw RuntimeFailure("SCREEN_SEQUENCE_CANCELLED","连续操作已取消，未开始后续步骤")
    }
    @Synchronized fun finish(id:String) {tickets[id]?.let{if(it.state=="running") it.state="finished"}}
}
