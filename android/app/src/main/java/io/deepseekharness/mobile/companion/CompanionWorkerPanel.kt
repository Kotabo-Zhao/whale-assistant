package io.deepseekharness.mobile.companion

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/** The pet manages the same durable tasks as the app's background-work panel. */
internal class CompanionWorkerPanel(private val context: Context, private val openSession: (String) -> Unit, private val onCounts: (Int, Int, Int) -> Unit = { _, _, _ -> }, private val onDraftOpened: () -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val store = WorkerDashboardStore(context)
    private val runtime = WorkerRuntimeWake(context)
    private var submissions = emptyList<JSONObject>()
    private var badge: TextView? = null
    private var dialog: AlertDialog? = null
    private var editDialog: AlertDialog? = null
    private var inboxUi: PetUi? = null
    private var cards: LinearLayout? = null
    private var rows = emptyList<JSONObject>()
    private var offset = 0
    private var total = 0
    private var lastPage = ""
    private var selectedGroup = ""
    private var groupReport: JSONObject? = null
    private var lastCounts: Triple<Int,Int,Int>? = null
    private var detailDialog: AlertDialog? = null
    private var detailRequest = 0
    private var expandedId: String? = null
    private var filter = "open"
    private var disposed = false
    private var scheduled = false
    private var loading = false
    private var loadAgain = false
    private val refresh = Runnable { scheduled = false; load() }
    private val listener: () -> Unit = { handler.post { schedule() } }
    private val active = setOf("queued", "starting", "running", "waiting", "blocked", "interrupted", "cancelling")

    init { WorkerDashboardStore.subscribe(listener); schedule() }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun attach(view: TextView) { badge = view; schedule() }
    fun refreshStatus() { schedule() }
    fun detach() { badge = null; detailRequest++; detailDialog?.dismiss(); dialog?.dismiss(); editDialog?.dismiss() }
    fun destroy() { disposed = true; WorkerDashboardStore.unsubscribe(listener); handler.removeCallbacks(refresh); detach(); executor.execute { runtime.close() }; executor.shutdown() }

    private fun schedule() {
        if (disposed || scheduled) return
        scheduled = true; handler.postDelayed(refresh, 300)
    }
    private fun load() {
        if (disposed) return
        if (loading) { loadAgain = true; return }
        loading = true
        val opened = dialog
        val selectedFilter = filter
        val start = offset
        val group = selectedGroup
        executor.execute {
            val result = runCatching {
                if (opened == null) store.page("open",limit = 0)
                else store.page(selectedFilter, group = group, offset = start, limit = 20)
            }
            handler.post {
                loading = false
                if (!disposed) result.onSuccess { page ->
                    val counts = Triple(page.getInt("running"), page.getInt("attention"), page.getInt("asking"))
                    if (counts != lastCounts) {
                        lastCounts = counts
                        onCounts(counts.first, counts.second, counts.third)
                    }
                    badge?.apply {
                        val label = when {
                            counts.second > 0 -> "${counts.second}"
                            counts.first > 0 -> "${counts.first}"
                            else -> ""
                        }
                        if (text != label) text = label
                        val description = "后台任务：${counts.first}项运行中，${counts.second}项待处理"
                        if (contentDescription != description) contentDescription = description
                    }
                    if (opened != null && opened === dialog && selectedFilter == filter && start == offset && group == selectedGroup) {
                        total = page.getInt("total")
                        if (start >= total && start > 0) { offset = ((total - 1).coerceAtLeast(0) / 20) * 20; schedule() }
                        else {
                            val signature = page.toString()
                            if (signature != lastPage) {
                                lastPage = signature
                                fun rows(key: String) = page.getJSONArray(key).let { items -> (0 until items.length()).map(items::getJSONObject) }
                                rows = rows("items"); submissions = if (group.isEmpty()) rows("submissions") else emptyList()
                                groupReport = page.optJSONObject("groupReport")
                                render()
                            }
                        }
                    }
                }.onFailure { badge?.text = "" }
                if (loadAgain) { loadAgain = false; schedule() }
            }
        }
    }
    fun open() {
        if (disposed || dialog != null) return
        val ui = PetUi(context)
        inboxUi = ui
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val manual = ui.text(ui.string(io.deepseekharness.mobile.R.string.pet_manual_assign), 13f, ui.accent).apply {
            minHeight = dp(44); gravity = android.view.Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0); background = ui.action(ui.surface, 10); setOnClickListener { compose() }
        }
        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(6)) }
        fun styleTab(tab: Button) {
            tab.isSelected = tab.tag == filter
            tab.setTextColor(if (tab.isSelected) ui.accent else ui.muted)
            tab.background = ui.action(if (tab.isSelected) ui.soft else ui.surface, 10)
        }
        fun tab(text: String, value: String) = Button(context).apply {
            this.text = text; tag = value; isAllCaps = false; textSize = 14f; minHeight = dp(44); styleTab(this)
            setOnClickListener {
                if (filter != value) {
                    filter = value; offset = 0; lastPage = ""; expandedId = null
                    for (i in 0 until tabs.childCount) styleTab(tabs.getChildAt(i) as Button)
                    schedule()
                }
            }
        }
        tabs.addView(tab("当前任务", "open"), LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tab("待我处理", "attention"), LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tab("全部任务", "all"), LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(tabs)
        cards = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val list = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = minOf(dp(390),context.resources.displayMetrics.heightPixels / 2)
                val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap else minOf(cap,MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(height,MeasureSpec.AT_MOST))
            }
        }
        list.addView(cards)
        body.addView(list,LinearLayout.LayoutParams(-1,-2))
        dialog = ui.dialogBuilder("后台任务", null, io.deepseekharness.mobile.R.drawable.ic_whale_nav_tasks, manual).setView(body).setNegativeButton("关闭", null).create().apply {
            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setOnDismissListener { dialog = null; inboxUi = null; expandedId = null; selectedGroup = ""; groupReport = null; cards = null; rows = emptyList(); submissions = emptyList(); lastPage = ""; offset = 0; total = 0 }
            show(); ui.finishDialog(this, 390)
        }
        schedule()
    }
    private fun render() {
        val container = cards ?: return
        val ui = inboxUi ?: return
        container.removeAllViews()
        val shown = rows
        if (selectedGroup.isNotEmpty()) renderGroup(container,ui)
        submissions.forEach { pending ->
            val held = pending.optBoolean("held")
            val state = if (held) "待授权" else "等待接取"
            container.addView(TextView(context).apply {
                text = "$state · ${pending.optString("task").take(90)}\n${pending.optString("notice")}"
                setTextColor(ui.muted); setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            if (held) {
                val actions = LinearLayout(context)
                for ((label, action) in listOf("权限设置" to "settings", "继续原任务" to "resume", "移除" to "remove")) {
                    actions.addView(Button(context).apply {
                        text = label; textSize = 13f
                        setOnClickListener {
                            isEnabled = false
                            executor.execute {
                                val result = runCatching { store.heldAction(pending.getString("commandId"),action,runtime).optString("message") }
                                handler.post {
                                    if (!disposed) {
                                        isEnabled = true
                                        if (action == "settings" && result.isSuccess) dialog?.dismiss()
                                        Toast.makeText(context,result.getOrDefault("未能处理，请重试"),Toast.LENGTH_SHORT).show()
                                        schedule()
                                    }
                                }
                            }
                        }
                    })
                }
                container.addView(actions)
            }
        }
        if (shown.isEmpty() && submissions.isEmpty()) {
            val message = when (filter) {
                "attention" -> "目前没有需要你处理的任务"
                "all" -> "暂无后台任务"
                else -> "没有进行中或待处理的任务"
            }
            container.addView(ui.text(message,14f,ui.muted).apply { setPadding(dp(8),dp(20),dp(8),dp(20)) })
            return
        }
        for (row in shown) {
            val id = row.optString("id"); val state = row.optString("state"); val missing = row.optBoolean("missingLedger")
            val title = row.optString("title").ifBlank { "后台任务" }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = ui.shape(ui.canvas, 14, ui.line)
                contentDescription = "任务管理：$title"
            }
            val states = mapOf("unreadable" to "记录待检查", "queued" to "排队中", "starting" to "启动中", "running" to "执行中", "waiting" to "等你回答", "blocked" to "遇到阻碍", "interrupted" to "中断待确认", "failed" to "未完成", "completed" to "已完成", "cancelled" to "已取消", "cancelling" to "正在取消")
            card.addView(TextView(context).apply { text = title; textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(ui.ink); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            card.addView(TextView(context).apply { text = if (row.optBoolean("recordUnreadable")) "原生任务记录无法读取" else if (missing) "历史任务记录缺失，无法继续" else if (row.optBoolean("blockedDependency")) "前置任务待处理" else states[state] ?: "状态待确认"; textSize = 12f; setTextColor(ui.ink)
                setPadding(dp(8),dp(5),dp(8),dp(5))
                background = ui.shape(when (state) {
                    "waiting", "blocked", "interrupted", "failed" -> ui.honey
                    "running", "starting" -> ui.soft
                    else -> ui.canvas
                }, 8)
            }, LinearLayout.LayoutParams(-2,-2).apply { topMargin = dp(6); bottomMargin = dp(4) })
            if (row.optBoolean("authorizationRevoked")) card.addView(TextView(context).apply { text = "任务授权已撤回"; textSize = 12f; setTextColor(ui.alert) })
            if (expandedId == id) {
                row.optString("group").takeIf { it.isNotBlank() }?.let { group ->
                    card.addView(TextView(context).apply { text = "所属目标：$group"; textSize = 12f; setTextColor(ui.muted) })
                }
                val source = when (row.optString("source", "phone")) { "github" -> "GitHub"; "feishu" -> "飞书"; else -> "手机" }
                val permission = when (row.optString("mode")) { "create" -> "创作本工单的工具"; "device" -> "设备操作"; else -> "只读" }
                card.addView(TextView(context).apply { text = "来源：$source · 权限：$permission"; textSize = 12f; setTextColor(ui.muted) })
                if (state == "queued") card.addView(TextView(context).apply { text = "优先级：${row.optInt("priority", 0)}"; textSize = 12f; setTextColor(ui.muted) })
                if (source != "手机") row.optString("requester").takeIf { it.isNotBlank() }?.let { requester ->
                    card.addView(TextView(context).apply { text = "发起人：$requester"; textSize = 12f; setTextColor(ui.muted) })
                }
                row.optString("decisionReason").takeIf { it.isNotBlank() }?.let { reason ->
                    card.addView(TextView(context).apply { text = "接收原因：${reason.take(120)}"; textSize = 12f; setTextColor(ui.muted) })
                }
                row.optString("decisionEvidence").takeIf { it.isNotBlank() }?.let { evidence ->
                    card.addView(TextView(context).apply { text = "判断依据：${evidence.take(120)}"; textSize = 12f; setTextColor(ui.muted) })
                }
                row.optString("scope").takeIf { it.isNotBlank() }?.let { scope ->
                    card.addView(TextView(context).apply {
                        text = "授权范围：${scope.take(100)}${if (scope.length > 100) "…" else ""}"
                        textSize = 12f; setTextColor(ui.muted)
                        if (scope.length > 100) setOnClickListener { showDetail(id, title, 0) }
                    })
                }
            }
            row.optString("goalState").takeIf { it.isNotBlank() }?.let { goal ->
                card.addView(ui.text(when(goal) {
                    "verified" -> "目标已核验"
                    "verification" -> "子任务已返回 · 目标待核验"
                    "budget" -> "目标预算待处理"
                    else -> "目标正在推进"
                },12f,if(goal=="verified") ui.accent else ui.muted))
            }
            val dependencies = row.optJSONArray("dependsOn")
            if (state == "queued" && dependencies != null && row.optInt("remainingDependencies") > 0 && row.optString("waitReason").isBlank()) {
                val titles = row.optJSONArray("dependencyTitles") ?: org.json.JSONArray()
                val blocked = row.optBoolean("blockedDependency")
                card.addView(TextView(context).apply {
                    text = if (blocked) "前置任务需要处理：" + (0 until titles.length()).joinToString("、") { titles.optString(it) }.ifBlank { "任务记录缺失" }
                    else "等待 ${row.optInt("remainingDependencies")} 个前置任务完成"
                    textSize = 12f; setTextColor(if (blocked) ui.alert else ui.muted)
                })
            }
            val detail = when (state) {
                "waiting" -> row.optString("question").ifBlank { row.optString("summary") }
                "queued", "starting", "running" -> row.optString("waitReason").ifBlank { row.optString("progress").ifBlank { row.optString("summary") } }
                else -> row.optString("summary").ifBlank { row.optString("question") }
            }
            if (detail.isNotBlank()) card.addView(TextView(context).apply {
                text = CompanionRichText.render(detail, ui)
                textSize = 13f; setPadding(0, dp(6), 0, dp(4)); setTextColor(ui.ink)
                setOnClickListener { showDetail(id, title, 0) }
            })
            row.optString("uiError").takeIf { it.isNotBlank() }?.let { error -> card.addView(TextView(context).apply { text = error; setTextColor(ui.alert) }) }
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fun action(label: String, primary: Boolean = false, click: () -> Unit) {
                actions.addView(Button(context).apply {
                    text = label; isAllCaps = false; textSize = 13f; minWidth = dp(52); minHeight = dp(44)
                    setTextColor(if (primary) ui.onPrimary else ui.accent)
                    background = ui.action(if (primary) ui.primary else ui.canvas,10)
                    setOnClickListener { click() }
                })
            }
            if (!missing) when (state) {
                "waiting" -> action("回答",true) { input(id,title,"reply","回答任务问题",row.optString("question")) }
                "blocked","interrupted","failed" -> if(row.optBoolean("recoveryRequired") || row.optJSONObject("goalRecovery")!=null) {
                    action("先核对恢复副本") {expandedId=id;schedule()}
                } else if (row.optBoolean("hasPermissionSettings") && row.optString("mode") == "read") {
                    action("继续原任务",true) {
                        executor.execute {
                            val result = runCatching { store.resumeAuthorizedRead(id,row.getLong("writeSeq")).getString("message") }
                            handler.post { if (!disposed) {
                                Toast.makeText(context,result.getOrDefault("工单已变化，请刷新查看"),Toast.LENGTH_SHORT).show()
                                schedule()
                            } }
                        }
                    }
                    if (expandedId == id) action("补充说明") { input(id,title,"resume","确认现场后的继续说明",row.optString("summary")) }
                } else action("继续",true) { input(id,title,"resume","确认现场后的继续说明",row.optString("summary")) }
            }
            if (row.optBoolean("hasPermissionSettings")) action("权限设置") {
                executor.execute {
                    runCatching { store.openBlockedSettings(id) }.onSuccess {
                        handler.post { if (!disposed) { dialog?.dismiss(); onDraftOpened() } }
                    }.onFailure {
                        handler.post { if (!disposed) Toast.makeText(context,"任务状态已变化，请刷新查看",Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            if (!row.optBoolean("recordUnreadable")) action("任务详情") { showDetail(id, title, 0) }
            val taskGroup = row.optString("group")
            if (selectedGroup.isEmpty() && taskGroup.isNotBlank()) action("目标汇总") { selectGroup(taskGroup) }
            row.optJSONObject("authoredDraft")?.let { draft ->
                action("预览工具草稿") {
                    executor.execute {
                        val result = runCatching { store.previewDraft(id, draft.getString("revision")) }
                        handler.post {
                            if (!disposed) result.onSuccess { dialog?.dismiss(); onDraftOpened() }
                                .onFailure { Toast.makeText(context, "草稿已变化，请刷新", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
            }
            row.optJSONObject("dependencyTarget")?.let { parent ->
                val parentId = parent.getString("id")
                val parentTitle = parent.getString("title")
                action("查看前置任务") { showDetail(parentId,parentTitle,0) }
                if (expandedId == id) when (parent.getString("state")) {
                    "waiting" -> action("回答前置任务") { input(parentId,parentTitle,"reply","回答前置任务的问题") }
                    "failed","interrupted","blocked" -> action("继续前置任务") { input(parentId,parentTitle,"resume","确认现场后的继续说明") }
                }
            }
            if (!missing) {
                if (expandedId == id && state in setOf("queued","starting","running","completed") && !row.optBoolean("authorizationRevoked"))
                    action("追加") { input(id, title, "add", "追加任务信息") }
                if (expandedId == id && state == "queued") action("优先级") { input(id, title, "priority", "输入 0 到 10，数字越大越优先") }
                if (expandedId == id && ((state in active && state != "cancelling") || state == "failed" || (state == "completed" && !row.optBoolean("authorizationRevoked") && row.optString("domainTicketId").isNotBlank()))) action("撤销") { command(id, "cancel", "") }
            }
            if (expandedId == id) row.optString("sessionId").takeIf { it.isNotBlank() }?.let { session -> action("会话") { dialog?.dismiss(); openSession(session) } }
            action(if (expandedId == id) "收起" else "管理") { expandedId = if (expandedId == id) null else id; render() }
            card.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (offset > 0 || total > offset + shown.size) {
            val nav = LinearLayout(context)
            fun move(label: String, to: Int) = Button(context).apply {
                text = label
                setOnClickListener { offset = to; schedule() }
            }
            if (offset > 0) nav.addView(move("上一页", (offset-20).coerceAtLeast(0)))
            if (offset + shown.size < total) nav.addView(move("下一页", offset+20))
            container.addView(nav)
        }
    }

    private fun selectGroup(group: String) {
        selectedGroup = group; groupReport = null; offset = 0; expandedId = null; lastPage = ""
        schedule()
    }
    private fun renderGroup(container: LinearLayout, ui: PetUi) {
        container.addView(ui.text("$selectedGroup · 目标汇总",16f,ui.ink,true))
        container.addView(ui.text("返回全部目标",14f,ui.accent).apply {
            minHeight = dp(44); gravity = android.view.Gravity.CENTER_VERTICAL; setOnClickListener { selectGroup("") }
        })
        val report = groupReport ?: return
        if (report.length() > 0) container.addView(ui.text("在主对话分析",14f,ui.accent,true).apply {
            minHeight = dp(44); gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { prepareAnalysis(selectedGroup) }
        })
        for ((key,label) in listOf("done" to "已完成","attention" to "需处理","working" to "执行与排队","cancelled" to "已取消")) {
            val bucket = report.optJSONObject(key) ?: continue
            val items = bucket.getJSONArray("items")
            container.addView(ui.text("$label · ${bucket.getInt("count")}",14f,ui.ink,true))
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val title = item.getString("title")
                container.addView(ui.text(title + "\n" + item.optString("note").ifBlank { "暂无摘要" },13f,ui.muted).apply {
                    setPadding(dp(8),dp(4),dp(8),dp(4)); minHeight = dp(44)
                    setOnClickListener { showDetail(item.getString("id"),title,0) }
                })
            }
            if (bucket.getInt("count") > items.length()) container.addView(ui.text("显示最近 ${items.length()} 项",12f,ui.muted))
        }
    }
    private fun prepareAnalysis(group: String) {
        val current = dialog ?: return
        executor.execute {
            val drafted = runCatching { store.analysisDraft(group) }
            handler.post {
                if (disposed || dialog !== current || group != selectedGroup) return@post
                drafted.onSuccess { text ->
                    val staged = io.deepseekharness.mobile.MainHarnessSurface.stageCompanionKnowledge(text)
                    val intent = android.content.Intent(context,io.deepseekharness.mobile.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (!staged) intent.setAction(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT,text)
                    runCatching { context.startActivity(intent) }.onSuccess { current.dismiss(); onDraftOpened() }.onFailure {
                        if (staged) io.deepseekharness.mobile.MainHarnessSurface.cancelCompanionDraft()
                        Toast.makeText(context,"暂时无法打开主对话",Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { Toast.makeText(context,"目标已变化，请刷新后重试",Toast.LENGTH_SHORT).show() }
            }
        }
    }
    private fun showDetail(id: String, title: String, start: Int) {
        val request = ++detailRequest
        executor.execute {
            val result = runCatching { store.detail(id, start) }
            handler.post {
                if (disposed || dialog == null || request != detailRequest) return@post
                result.onFailure { Toast.makeText(context,"任务详情暂时不可用",Toast.LENGTH_SHORT).show() }.onSuccess { row ->
                    val events = row.getJSONArray("events")
                    val historyCount = row.getInt("historyCount")
                    detailDialog?.dismiss()
                    val heading = row.optJSONObject("authoredDraft")?.optString("title")?.takeIf(String::isNotBlank)
                        ?: title.takeIf { it.length <= 38 } ?: "任务详情"
                    val ui = PetUi(context)
                    val builder = ui.dialogBuilder(heading,null,io.deepseekharness.mobile.R.drawable.ic_whale_nav_tasks).setView(WorkerDetailView(context,row)).setNegativeButton("关闭",null)
                    if (start + events.length() < historyCount) builder.setNeutralButton("更早记录") { _,_ -> showDetail(id,title,start+12) }
                    if (start > 0) builder.setPositiveButton("较新记录") { _,_ -> showDetail(id,title,(start-12).coerceAtLeast(0)) }
                    detailDialog = builder.create().apply { window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); show(); ui.finishDialog(this,390) }
                }
            }
        }
    }
    private fun input(id: String, title: String, action: String, hint: String, note: String = "") {
        if (editDialog != null) return
        val ui = PetUi(context)
        val field = ui.editor(EditText(context)).apply { this.hint = hint; maxLines = 5 }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(8))
            if (note.isNotBlank()) addView(ui.text(note,14f).apply {
                maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0,0,0,dp(8))
            })
            addView(field,LinearLayout.LayoutParams(-1,-2))
        }
        editDialog = ui.dialogBuilder(title,null,io.deepseekharness.mobile.R.drawable.ic_whale_nav_tasks).setView(body).setNegativeButton("取消", null).setPositiveButton("提交", null).create().apply {
            window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setOnDismissListener { editDialog = null }
            show(); ui.finishDialog(this,390)
            getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (value.isBlank()) field.error = "请输入内容" else { command(id, action, value); dismiss() }
            }
        }
    }
    fun submit(task: String, mode: String, id: String = java.util.UUID.randomUUID().toString(), title: String? = null, needs: String = "none") =
        WorkerRuntimeWake.submit(context, task, mode, runtime, id, title, needs)

    fun compose() {
        if (disposed || editDialog != null) return
        val ui = PetUi(context)
        val field = ui.editor(EditText(context)).apply {
            hint = "交给后台做什么？"; minLines = 2; maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
        }
        val choices = android.widget.RadioGroup(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((index, label) in listOf("读取整理", "创作工具", "操作设备").withIndex()) {
                addView(android.widget.RadioButton(context).apply {
                    id = android.view.View.generateViewId(); text = label; tag = listOf("read", "create", "device")[index]
                    buttonDrawable = null; gravity = android.view.Gravity.CENTER; textSize = 14f; minHeight = dp(46)
                    fun style(selected: Boolean) {
                        setTextColor(if (selected) ui.accent else ui.muted)
                        background = ui.action(if (selected) ui.soft else ui.surface, 10, if (selected) ui.accent else ui.line)
                    }
                    isChecked = index == 0; style(isChecked)
                    setOnCheckedChangeListener { _, checked -> style(checked) }
                }, android.widget.RadioGroup.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(5) })
            }
        }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(6))
            addView(field)
            addView(ui.text(ui.string(io.deepseekharness.mobile.R.string.pet_ui_mode_label), 12f, ui.muted).apply { setPadding(0, dp(10), 0, dp(5)) })
            addView(choices)
        }
        editDialog = ui.dialogBuilder("交办任务", ui.string(io.deepseekharness.mobile.R.string.pet_ui_task_hint), io.deepseekharness.mobile.R.drawable.ic_pet_assign)
            .setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton("取消", null).setPositiveButton("交办", null).create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                setOnDismissListener { editDialog = null }; show(); ui.finishDialog(this)
                val submit = getButton(AlertDialog.BUTTON_POSITIVE)
                submit.setOnClickListener {
                    val task = field.text.toString().trim()
                    if (task.isEmpty()) { field.error = "请输入任务"; return@setOnClickListener }
                    submit.isEnabled = false
                    val mode = choices.findViewById<android.widget.RadioButton>(choices.checkedRadioButtonId)?.tag as? String ?: "read"
                    executor.execute {
                        val result = runCatching { this@CompanionWorkerPanel.submit(task, mode) }
                        handler.post {
                            if (!disposed && isShowing) {
                                if (result.isSuccess) dismiss() else { field.error = "任务未保存，请重试"; submit.isEnabled = true }
                            }
                        }
                    }
                }
            }
    }
    private fun command(id: String, action: String, text: String) {
        executor.execute {
            val result = runCatching { store.command(JSONObject().put("id", id).put("action", action).put("task", text)) }
            handler.post {
                if (!disposed) {
                    Toast.makeText(context, if (result.isSuccess) "操作已提交，等待后台处理" else "操作未提交，请重试", Toast.LENGTH_SHORT).show()
                    schedule()
                }
            }
        }
    }
}
