package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject

/** A bounded, fail-closed text navigation plan. Coordinates are resolved afresh, never queued by the model. */
internal object ScreenSequence {
    interface Port {
        fun observe():JSONObject
        fun foreground():String
        fun tap(observation:JSONObject,x:Int,y:Int):JSONObject
        fun check()
        fun now():Long
        fun waitForFrame()
        fun refine(observation:JSONObject,text:String):JSONObject?=null
    }
    data class Target(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val x get()=(left+right)/2
        val y get()=(top+bottom)/2
        fun contains(other:Target)=left<=other.left && top<=other.top && right>=other.right && bottom>=other.bottom
    }
    private fun normalized(value:String)=value.filterNot{it.isWhitespace()}.lowercase(java.util.Locale.ROOT)
    /** Fuzzy similarity is used only to choose pixels to READ, never an action target. */
    fun refinementRegion(observation:JSONObject,text:String):JSONArray? {
        val wanted=normalized(text)
        if(wanted.length !in 3..32) return null
        fun near(a:String,b:String):Boolean {
            if(kotlin.math.abs(a.length-b.length)>1 || a==b) return false
            var i=0;var j=0;var edits=0
            while(i<a.length && j<b.length) {
                if(a[i]==b[j]) {i++;j++;continue}
                if(++edits>1) return false
                if(a.length>=b.length) i++
                if(b.length>=a.length) j++
            }
            return edits+(a.length-i)+(b.length-j)==1
        }
        val lines=observation.optJSONObject("image")?.optJSONArray("lines") ?: return null
        val candidates=(0 until lines.length()).map{lines.getJSONObject(it)}.filter{near(normalized(it.optString("text")),wanted)}
        if(candidates.size!=1) return null
        val b=candidates.single().getJSONArray("bounds")
        val pad=maxOf(12,(b.getInt(3)-b.getInt(1))/2)
        val left=maxOf(0,b.getInt(0)-pad);val top=maxOf(0,b.getInt(1)-pad)
        return JSONArray(listOf(left,top,minOf(observation.getInt("width"),b.getInt(2)+pad)-left,
            minOf(observation.getInt("height"),b.getInt(3)+pad)-top))
    }
    private fun visibleText(observation:JSONObject):String {
        val rows=observation.optJSONObject("tree")?.optJSONArray("nodes")
            ?: observation.optJSONObject("image")?.optJSONArray("lines") ?: return ""
        return (0 until rows.length()).joinToString("|") { i ->
            val row=rows.getJSONObject(i)
            listOf(row.optString("text"),row.optString("contentDescription"),row.optString("selected"),row.optString("visualSelectionHint")).joinToString(":")
        }
    }
    fun target(observation:JSONObject,text:String):Target? {
        val wanted=normalized(text)
        val tree=observation.optJSONObject("tree")?.optJSONArray("nodes")
        val image=observation.optJSONObject("image")?.optJSONArray("lines")
        val spans=JSONArray()
        if(image!=null) for(i in 0 until image.length()) {
            val line=image.getJSONObject(i);spans.put(line)
            val elements=line.optJSONArray("elements") ?: continue
            for(start in 0 until elements.length()) {
                var label="";var left=Int.MAX_VALUE;var top=Int.MAX_VALUE;var right=0;var bottom=0
                for(end in start until minOf(elements.length(),start+8)) {
                    val element=elements.getJSONObject(end);label+=normalized(element.optString("text"))
                    val b=element.getJSONArray("bounds");left=minOf(left,b.getInt(0));top=minOf(top,b.getInt(1));right=maxOf(right,b.getInt(2));bottom=maxOf(bottom,b.getInt(3))
                    if(label==wanted) spans.put(JSONObject().put("text",text).put("bounds",JSONArray(listOf(left,top,right,bottom))))
                    if(label.length>=wanted.length) break
                }
            }
        }
        // Prefer authorized native nodes; an OCR duplicate must not become a second target.
        fun matches(rows:JSONArray?):List<Target> = if(rows==null) emptyList() else (0 until rows.length()).mapNotNull {i->
            val row=rows.getJSONObject(i)
            if(row.optBoolean("password") || row.has("enabled") && !row.optBoolean("enabled") ||
                listOf(row.optString("text"),row.optString("contentDescription")).none {normalized(it)==wanted}) return@mapNotNull null
            val b=row.optJSONArray("bounds") ?: return@mapNotNull null
            if(b.length()!=4) return@mapNotNull null
            Target(b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)).takeIf {
                it.left>=0 && it.top>=0 && it.right>it.left && it.bottom>it.top &&
                    it.right<=observation.getInt("width") && it.bottom<=observation.getInt("height")
            }
        }
        val candidates=matches(tree).ifEmpty{matches(spans)}
        val distinct=ArrayList<Target>()
        for(candidate in candidates) {
            val same=distinct.indexOfFirst{it.contains(candidate) || candidate.contains(it)}
            if(same<0) distinct.add(candidate)
            else if((candidate.right-candidate.left)*(candidate.bottom-candidate.top)<
                (distinct[same].right-distinct[same].left)*(distinct[same].bottom-distinct[same].top)) distinct[same]=candidate
        }
        if(distinct.size>1) throw RuntimeFailure("SCREEN_TARGET_AMBIGUOUS","文字对应多个目标，请重新观察并单步处理")
        return distinct.singleOrNull()
    }
    fun run(request:JSONObject,port:Port):JSONObject {
        val pack=request.optString("packageName")
        val steps=request.optJSONArray("steps") ?: throw RuntimeFailure("SCREEN_INPUT_INVALID","缺少步骤")
        if(!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pack) || pack.length>160 || steps.length() !in 1..6)
            throw RuntimeFailure("SCREEN_INPUT_INVALID","连续操作须指定应用和一至六步")
        val labels=(0 until steps.length()).map {i->
            val row=steps.optJSONObject(i) ?: throw RuntimeFailure("SCREEN_INPUT_INVALID","步骤格式无效")
            val text=row.opt("text") as? String ?: throw RuntimeFailure("SCREEN_INPUT_INVALID","缺少目标文字")
            if(row.length()!=1 || text.isBlank() || text.length>80) throw RuntimeFailure("SCREEN_INPUT_INVALID","只支持简短、精确的目标文字")
            text
        }
        val started=port.now()
        val receipts=JSONArray()
        var observation:JSONObject?=null
        var injecting=false
        var index=0
        var refinementReads=0
        fun checkPackage() {
            port.check()
            if(port.now()-started>12000) throw RuntimeFailure("SCREEN_SEQUENCE_TIMEOUT","连续操作已到时限")
            if(port.foreground()!=pack) throw RuntimeFailure("SCREEN_APP_CHANGED","前台应用变化，已停止后续操作")
            port.check()
        }
        try {
            checkPackage()
            observation=port.observe()
            for((i,label) in labels.withIndex()) {
                index=i;injecting=false
                val readyBy=port.now()+1200
                var refined=false
                var target=target(observation!!,label)
                fun refineIfPossible() {
                    if(target!=null || refined) return
                    checkPackage()
                    port.refine(observation!!,label)?.let{
                        refined=true;refinementReads++;observation=it;target=target(it,label)
                    }
                }
                refineIfPossible()
                while(target==null && port.now()<readyBy) {
                    checkPackage();port.waitForFrame();observation=port.observe();target=target(observation!!,label)
                    refineIfPossible()
                }
                if(target==null) throw RuntimeFailure("SCREEN_TARGET_MISSING","当前页面未出现目标，已停止后续操作")
                val point=target!!
                checkPackage()
                injecting=true
                val actionStarted=port.now()
                val result=port.tap(observation!!,point.x,point.y)
                receipts.put(JSONObject().put("step",i+1).put("action",result.getJSONObject("action"))
                    .put("elapsedMs",port.now()-actionStarted))
                injecting=false
                observation=result.optJSONObject("observation")
                if(observation==null) throw RuntimeFailure(result.optString("observationError","SCREEN_OBSERVATION_FAILED"),"动作后观察不可用")
            }
            // The last action can return a transitional page. Compare fresh text locally,
            // without another model call; this is observation stability, not business success.
            val settleBy=port.now()+1200
            var stable=false
            while(!stable && port.now()<settleBy) {
                checkPackage();port.waitForFrame()
                val next=port.observe()
                stable=visibleText(observation!!).let{it.isNotEmpty() && it==visibleText(next)}
                observation=next
            }
            checkPackage()
            return JSONObject().put("status","completed").put("completed",receipts.length()).put("receipts",receipts)
                .put("observation",observation).put("elapsedMs",port.now()-started)
                .put("visibleTextStable",stable)
                .put("refinementReads",refinementReads)
                .put("verification","动作逐步注入；最终业务状态请根据观察核实")
        } catch(error:Exception) {
            port.check() // Pause/stop must remain a denial, including when a read is interrupted.
            observation?.remove("observationId")
            return JSONObject().put("status","stopped").put("completed",receipts.length()).put("receipts",receipts)
                .put("failedStep",index+1).put("actionUncertain",injecting).put("doNotReplay",true)
                .put("errorCode",if(error is RuntimeFailure) error.code else "SCREEN_SEQUENCE_FAILED")
                .put("refinementReads",refinementReads)
                .put("observation",observation).put("elapsedMs",port.now()-started)
        }
    }
}
