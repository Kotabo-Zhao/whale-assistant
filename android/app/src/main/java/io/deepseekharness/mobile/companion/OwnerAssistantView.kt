package io.deepseekharness.mobile.companion

import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One inline editor for the App and the pet; all storage and alarm IO stays off the UI thread. */
internal class OwnerAssistantView(context: Context, private val changed: () -> Unit = {}, private val close: (() -> Unit)? = null) : LinearLayout(context) {
    private val app = context.applicationContext
    private val ui = PetUi(context)
    private val status = ui.text("正在读取…",13f,ui.muted)
    private val body = LinearLayout(context).apply { orientation=VERTICAL }
    private var busy = false
    private var generation = 0
    private var mode = "profile"
    private var offset = 0
    private var activeOnly = true
    private val date = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    init {
        orientation=VERTICAL; setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(12))
        background=ui.shape(ui.surface,16,ui.line)
        // The pet already has its own header; leave its limited viewport for editable content.
        if(close==null) addView(ui.header("懂你，也记得交办", "主人档案 · 秘书事项"))
        addView(LinearLayout(context).apply {
            addView(action("主人档案") { mode="profile";refresh() }, LayoutParams(0,-2,1f))
            addView(action("秘书事项") { mode="tasks";offset=0;refresh() }, LayoutParams(0,-2,1f))
            if(close!=null) addView(action("收起") { close.invoke() }, LayoutParams(-2,-2))
        })
        status.setPadding(0,ui.dp(8),0,ui.dp(8));addView(status);addView(body)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();refresh() }
    override fun onDetachedFromWindow() { generation++;busy=false;super.onDetachedFromWindow() }
    private fun action(label: String, click: () -> Unit): TextView = ui.text(label,14f,ui.accent,true).apply {
        minHeight=ui.dp(44);gravity=Gravity.CENTER;isFocusable=true
        setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));background=ui.action(ui.soft,12)
        setOnClickListener { if(!busy) click() }
    }
    private fun note(text: String) = ui.text(text,13f,ui.muted).apply { setPadding(0,ui.dp(6),0,ui.dp(6)) }
    private fun card(title: String, detail: String, click: () -> Unit) = LinearLayout(context).apply {
        orientation=VERTICAL;setPadding(ui.dp(12),ui.dp(10),ui.dp(12),ui.dp(10));background=ui.action(ui.canvas,12,ui.line)
        isFocusable=true;contentDescription="$title，$detail，点击编辑"
        addView(ui.text(title,15f,ui.ink,true));addView(note(detail));setOnClickListener { if(!busy) click() }
        layoutParams=LayoutParams(-1,-2).apply { bottomMargin=ui.dp(8) }
    }
    private fun edit(label: String, value: String, max: Int, multiline: Boolean=false): EditText {
        body.addView(note(label))
        return ui.editor(EditText(context)).apply {
            hint=label;contentDescription=label;setText(value);filters=arrayOf(InputFilter.LengthFilter(max));maxLines=if(multiline) 4 else 1
            inputType=android.text.InputType.TYPE_CLASS_TEXT or if(multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            body.addView(this,LayoutParams(-1,-2))
        }
    }
    private fun timeText(ms: Long) = if(ms>0) date.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())) else ""
    private fun timeValue(input: EditText): Long? {
        val value=input.text.toString().trim();if(value.isEmpty())return null
        val local=runCatching { LocalDateTime.parse(value,date) }.getOrElse { error("时间请填写为 2026-11-08 09:30 这样的格式") }
        val zone=ZoneId.systemDefault();val offsets=zone.rules.getValidOffsets(local)
        require(offsets.size==1) { "该时间存在夏令时歧义，请选择其他时间" }
        return local.toInstant(offsets.single()).toEpochMilli()
    }
    private fun io(label: String, work: () -> JSONObject, done: (JSONObject) -> Unit) {
        if(busy)return
        busy=true;status.text=label;val own=++generation
        executor.execute {
            val result=runCatching(work)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if(!isAttachedToWindow || generation!=own)return@post
                busy=false
                result.fold({ done(it) },{ status.text=it.message?.take(180) ?: "读取失败，原数据已保留" })
                changed()
            }
        }
    }
    private fun refresh() {
        if(mode=="profile") io("正在读取主人档案…",{ CompanionMemoryStore(app).profile(forUser=true) }) { renderProfile(it) }
        else io("正在读取秘书事项…",{ UnifiedUnderstandingStore.open(app).use { it.queue.secretary.list(offset=offset,activeOnly=activeOnly) } }) { renderTasks(it) }
    }
    private fun renderProfile(profile: JSONObject) {
        body.removeAllViews()
        status.text=if(close!=null) "App 与桌宠共用 · 点击字段编辑" else
            "已确认的档案会在每轮对话自动加载。App 与桌宠共用；档案和秘书事项遵守长期记忆访问开关。"
        val fields=profile.getJSONArray("fields")
        for(i in 0 until fields.length()) {
            val field=fields.getJSONObject(i)
            val state=when(field.getString("state")) { "confirmed"->"已确认";"pending"->"待确认";"expired"->"已过期";"conflict"->"有冲突，请重新填写";else->"尚未填写" }
            val until=field.optLong("validUntilMs")
            body.addView(card(field.getString("label"),field.optString("value").ifBlank { state }+
                (if(field.has("value")) " · $state" else "")+(if(until>0) "\n有效至 ${timeText(until)}" else "")) { editProfile(field) })
        }
    }
    private fun editProfile(field: JSONObject) {
        body.removeAllViews();status.text="${field.getString("label")} · 保存即确认；忘记会移除该字段及其历史。"
        val value=edit("${field.getString("label")}，最多 240 字",field.optString("value"),240)
        val until=edit("有效至（可留空，近期安排必填）",timeText(field.optLong("validUntilMs")),16)
        body.addView(note("本机时区：${ZoneId.systemDefault().id} · 时间格式：2026-11-08 09:30"))
        val operation=UUID.randomUUID().toString()
        body.addView(action("保存并确认") {
            runCatching {
                val request=JSONObject().put("action","set").put("field",field.getString("field")).put("value",value.text.toString())
                    .put("expectedRevision",field.getString("revision")).put("operationId",operation)
                timeValue(until)?.let { request.put("validUntilMs",it) }
                io("正在保存…",{ CompanionMemoryStore(app).changeProfile(request,forUser=true) }) { refresh() }
            }.onFailure { status.text=it.message }
        })
        if(field.getString("state")!="empty") body.addView(action("忘记这项档案") {
            val request=JSONObject().put("action","forget").put("field",field.getString("field")).put("expectedRevision",field.getString("revision"))
            io("正在忘记…",{ CompanionMemoryStore(app).changeProfile(request,forUser=true) }) { refresh() }
        })
        body.addView(action("返回") { refresh() });changed()
    }
    private fun renderTasks(snapshot: JSONObject) {
        body.removeAllViews();status.text=if(close!=null) "交办、跟进与完成 · App 与桌宠共用" else
            "交办 → 下一步 → 等待 → 完成。截止时间与提醒分别保存；改期或关闭会停用旧提醒。"
        body.addView(action("添加事项") { editTask(null) })
        body.addView(action(if(activeOnly) "查看全部事项" else "只看待处理") { activeOnly=!activeOnly;offset=0;refresh() })
        val items=snapshot.getJSONArray("items")
        if(items.length()==0)body.addView(note("这里暂时没有事项。你也可以在对话中交办。"))
        for(i in 0 until items.length()) {
            val item=items.getJSONObject(i)
            val state=when(item.getString("state")){"done"->"已完成";"cancelled"->"已取消";else->"待处理"}
            val detail=listOf(state,item.optString("nextAction"),item.optString("waitingFor").takeIf { it.isNotEmpty() }?.let { "等待：$it" }.orEmpty(),
                item.optLong("dueAtMs").takeIf { it>0 }?.let { "截止：${timeText(it)}" }.orEmpty(),
                when(item.optString("reminderStatus")) {
                    "scheduled" -> "提醒：${timeText(item.optLong("reminderAtMs"))}"
                    "elapsed" -> "提醒时间已到，送达情况见系统提醒"
                    "needs_review" -> "提醒等待重新确认"
                    else -> "未设提醒"
                }).filter { it.isNotEmpty() }.joinToString("\n")
            body.addView(card(item.getString("title"),detail) { editTask(item) })
        }
        if(offset>0)body.addView(action("上一页") { offset=(offset-20).coerceAtLeast(0);refresh() })
        if(!snapshot.isNull("nextOffset"))body.addView(action("下一页") { offset=snapshot.getInt("nextOffset");refresh() })
    }
    private fun editTask(item: JSONObject?) {
        body.removeAllViews();status.text=if(item==null) "记下一件值得跟进的事" else "修改事项 · 当前：${item.getString("title")}"
        val title=edit("事项名称",item?.optString("title").orEmpty(),140)
        val next=edit("下一步",item?.optString("nextAction").orEmpty(),240)
        val waiting=edit("等待谁或什么结果",item?.optString("waitingFor").orEmpty(),100)
        val details=edit("补充说明",item?.optString("note").orEmpty(),400,true)
        val due=edit("截止时间（可留空）",timeText(item?.optLong("dueAtMs") ?: 0L),16)
        val reminder=edit("提醒时间（可留空）",timeText(if(item?.optString("reminderStatus")=="scheduled") item.optLong("reminderAtMs") else 0L),16)
        body.addView(note("本机时区：${ZoneId.systemDefault().id} · 格式：2026-11-08 09:30\n改期时请填写新的提醒；清空即停用。"))
        var previous="";var operation=UUID.randomUUID().toString()
        fun save(state: String?=null,delete: Boolean=false) {
            runCatching {
                val request=JSONObject().put("action",if(delete) "delete" else if(item==null) "create" else "update")
                item?.let { request.put("id",it.getString("id")).put("expectedRevision",it.getString("revision")) }
                if(!delete) {
                    request.put("title",title.text.toString()).put("nextAction",next.text.toString()).put("waitingFor",waiting.text.toString()).put("note",details.text.toString())
                    request.put("state",state ?: item?.optString("state") ?: "open")
                    val deadline=timeValue(due);val at=timeValue(reminder)
                    if(deadline!=null)request.put("dueAtMs",deadline) else request.put("clearDue",true)
                    // Do not silently re-arm an old reminder when the deadline changes.
                    val oldAt=item?.optLong("reminderAtMs") ?: 0L
                    val changedDue=item!=null && (deadline ?: 0L)!=item.optLong("dueAtMs")
                    if(request.getString("state")=="open" && at!=null && !(changedDue && at==oldAt)) request.put("reminderAtMs",at)
                    else request.put("clearReminder",true)
                }
                val fingerprint=request.toString()
                if(previous.isNotEmpty() && previous!=fingerprint)operation=UUID.randomUUID().toString()
                previous=fingerprint;request.put("operationId",operation)
                io("正在保存事项…",{ SecretaryActions.execute(app,request,"session-owner-settings") }) { result ->
                    refresh()
                    if(result.optString("reminderDelivery")=="pending_repair")status.text="事项已保存，提醒尚未成功安排，请到提醒设置检查。"
                }
            }.onFailure { status.text=it.message }
        }
        body.addView(action("保存事项") { save() })
        if(item!=null) {
            if(item.getString("state")=="open") {
                body.addView(action("标记完成") { save("done") });body.addView(action("取消事项") { save("cancelled") })
            } else body.addView(action("重新打开（不自动恢复提醒）") { save("open") })
            body.addView(action("删除事项") { save(delete=true) })
        }
        body.addView(action("返回") { refresh() });changed()
    }
    companion object {
        private val executor=java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r,"owner-assistant-io").apply { isDaemon=true } }
    }
}
