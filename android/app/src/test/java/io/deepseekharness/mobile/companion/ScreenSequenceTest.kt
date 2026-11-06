package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScreenSequenceTest {
    private fun plan(vararg labels:String)=JSONObject().put("packageName","com.example.clock")
        .put("steps",JSONArray(labels.map{JSONObject().put("text",it)}))
    private fun screen(vararg targets:Pair<String,Int>):JSONObject=JSONObject().put("width",500).put("height",800)
        .put("observationId","fresh").put("image",JSONObject().put("lines",JSONArray(targets.map{(text,x)->
            JSONObject().put("text",text).put("bounds",JSONArray(listOf(x,100,x+40,150)))
        })))
    private open inner class Fake:ScreenSequence.Port {
        var time=0L;var taps=0;var reads=0;var pack="com.example.clock";var revoked=false
        val positions=mutableListOf<Int>()
        var pages=listOf(screen("时钟" to 10),screen("定时器" to 180),screen("秒表" to 350),screen("开始" to 220))
        override fun observe():JSONObject {check();time+=50;reads++;return pages[minOf(taps,pages.lastIndex)]}
        override fun foreground()=pack
        override fun check(){if(revoked) throw RuntimeFailure("DEVICE_CONTROL_PAUSED","paused")}
        override fun now()=time
        override fun waitForFrame(){time+=16}
        override fun tap(observation:JSONObject,x:Int,y:Int):JSONObject {
            check();positions.add(x);taps++;return JSONObject().put("action",JSONObject().put("injected",true)).put("observation",observe())
        }
    }
    @Test fun usesFreshTargetsAndDoesNotWaitWhenReady() {
        val port=Fake()
        val result=ScreenSequence.run(plan("时钟","定时器","秒表"),port)
        assertEquals("completed",result.getString("status"));assertEquals(3,result.getInt("completed"))
        assertEquals(listOf(30,200,370),port.positions);assertEquals(266L,port.time)
        assertTrue(result.getBoolean("visibleTextStable"))
    }
    @Test fun missingTargetWaitsBoundedlyWithoutReplayingPreviousTap() {
        val port=Fake()
        val result=ScreenSequence.run(plan("时钟","不存在","秒表"),port)
        assertEquals("SCREEN_TARGET_MISSING",result.getString("errorCode"))
        assertEquals(1,port.taps);assertEquals(1,result.getInt("completed"));assertTrue(port.time in 1200L..1500L)
    }
    @Test fun finalObservationWaitsForTransitionWithoutReplayingActions() {
        val port=object:Fake(){override fun observe():JSONObject {
            time+=50;reads++
            return if(taps==0) screen("时钟" to 10) else if(reads<4) screen("切换中$reads" to 10) else screen("完成页" to 20)
        }}
        val result=ScreenSequence.run(plan("时钟"),port)
        assertTrue(result.getBoolean("visibleTextStable"));assertEquals(1,port.taps);assertEquals(5,port.reads)
    }
    @Test fun targetAppearingLaterIsReadAgainWithoutCloudRoundtrip() {
        val port=object:Fake(){override fun observe():JSONObject {
            reads++;time+=70
            return if(reads<3) screen("加载中" to 10) else screen("时钟" to 80)
        }}
        assertEquals("completed",ScreenSequence.run(plan("时钟"),port).getString("status"))
        assertEquals(listOf(100),port.positions)
    }
    @Test fun duplicateTextIsAmbiguousAndCannotBeClicked() {
        val port=Fake().apply{pages=listOf(screen("时钟" to 10,"时钟" to 300))}
        assertEquals("SCREEN_TARGET_AMBIGUOUS",ScreenSequence.run(plan("时钟"),port).getString("errorCode"))
        assertEquals(0,port.taps)
    }
    @Test fun usesSeparatedTextBoundsWithoutGuessingIconPrefixes() {
        val observation=screen("又定时器" to 10)
        assertNull(ScreenSequence.target(observation,"定时器"))
        observation.getJSONObject("image").getJSONArray("lines").getJSONObject(0).put("elements",JSONArray(listOf(
            JSONObject().put("text","又").put("bounds",JSONArray(listOf(10,100,20,150))),
            JSONObject().put("text","定时器").put("bounds",JSONArray(listOf(28,100,50,150))))))
        assertEquals(39,ScreenSequence.target(observation,"定时器")!!.x)
        assertNull(ScreenSequence.target(screen("删除定时器" to 10),"定时器"))
    }
    @Test fun nearOcrCanSelectReadRegionButNeverTapWithoutExactReobservation() {
        val original=screen("互联朕网" to 30)
        assertNotNull(ScreenSequence.refinementRegion(original,"互联网"))
        assertNull(ScreenSequence.target(original,"互联网"))
        assertNull(ScreenSequence.refinementRegion(screen("删除定时器" to 10),"定时器"))
        val port=object:Fake(){
            init {pages=listOf(original)}
            override fun refine(observation:JSONObject,text:String)=screen("互联网" to 80)
        }
        assertEquals("completed",ScreenSequence.run(plan("互联网"),port).getString("status"))
        assertEquals(listOf(100),port.positions)
        assertEquals(0,Fake().apply{pages=listOf(original);ScreenSequence.run(plan("互联网"),this)}.taps)
    }
    @Test fun refinementRemainsAvailableAfterTheTransitionFrame() {
        val port=object:Fake(){
            override fun observe():JSONObject {time+=70;reads++;return if(reads<2) screen("加载中" to 10) else screen("互联朕网" to 40)}
            override fun refine(observation:JSONObject,text:String):JSONObject?=
                ScreenSequence.refinementRegion(observation,text)?.let{screen("互联网" to 80)}
        }
        val result=ScreenSequence.run(plan("互联网"),port)
        assertEquals("completed",result.getString("status"));assertEquals(1,result.getInt("refinementReads"))
        assertEquals(listOf(100),port.positions)
    }
    @Test fun switchingApplicationStopsTheRemainingSteps() {
        val port=object:Fake(){override fun tap(observation:JSONObject,x:Int,y:Int):JSONObject {
            return super.tap(observation,x,y).also {pack="com.android.permissioncontroller"}
        }}
        val result=ScreenSequence.run(plan("时钟","定时器"),port)
        assertEquals("SCREEN_APP_CHANGED",result.getString("errorCode"));assertEquals(1,port.taps)
    }
    @Test fun pauseAfterOneTapRemainsADenial() {
        val port=object:Fake(){override fun tap(observation:JSONObject,x:Int,y:Int):JSONObject {
            return super.tap(observation,x,y).also {revoked=true}
        }}
        assertEquals("DEVICE_CONTROL_PAUSED",assertThrows(RuntimeFailure::class.java) {
            ScreenSequence.run(plan("时钟","定时器"),port)
        }.code)
        assertEquals(1,port.taps)
    }
    @Test fun uncertainInjectionIsNeverRetriedAndHasNoFalseReceipt() {
        val port=object:Fake(){override fun tap(observation:JSONObject,x:Int,y:Int):JSONObject {
            taps++;throw RuntimeFailure("SCREEN_INPUT_UNCERTAIN","unknown")
        }}
        val result=ScreenSequence.run(plan("时钟","定时器"),port)
        assertEquals(1,port.taps);assertEquals(0,result.getInt("completed"))
        assertTrue(result.getBoolean("actionUncertain"));assertTrue(result.getBoolean("doNotReplay"))
    }
    @Test fun invalidPlanDoesNotObserveOrAct() {
        for(request in listOf(plan(),plan(*Array(7){"时钟"}),plan(" "),plan("时钟").put("packageName","; bad"),
            plan("时钟").apply{getJSONArray("steps").getJSONObject(0).put("x",10)})) {
            val port=Fake()
            assertThrows(RuntimeFailure::class.java){ScreenSequence.run(request,port)}
            assertEquals(0,port.reads);assertEquals(0,port.taps)
        }
    }
}
