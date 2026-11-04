package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedReader
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PetDirectStreamTest {
    private fun event(delta: JSONObject, finish: String? = null) = "data: " + JSONObject().put("choices",
        JSONArray().put(JSONObject().put("delta",delta).put("finish_reason",finish ?: JSONObject.NULL)))

    @Test fun doneCompletesWithoutWaitingForTheServerToClose() {
        val lines = listOf(": heartbeat", "", event(JSONObject().put("content","你好")),
            event(JSONObject().put("content","呀"),"stop"), "data: [DONE]")
        var reads = 0
        val reader = object : BufferedReader(StringReader("")) {
            override fun readLine(): String {
                check(reads < lines.size) { "DONE 后又读取了仍保持连接的服务端" }
                return lines[reads++]
            }
        }
        val updates = mutableListOf<String>()
        val result = PetDirectChat.readStream(reader,updates::add)
        assertEquals("你好呀",result.text)
        assertEquals(listOf("你好","你好呀"),updates)
        assertEquals(lines.size,reads)
    }

    @Test fun fragmentedToolArgumentsSurviveTheTerminalMarker() {
        fun chunk(index: Int, id: String? = null, name: String? = null, args: String) = JSONObject().put("tool_calls",
            JSONArray().put(JSONObject().put("index",index).apply { id?.let { put("id",it) } }
                .put("function",JSONObject().put("arguments",args).apply { name?.let { put("name",it) } })))
        val stream = listOf(event(chunk(1,"second","mobile_agent","{")),
            event(chunk(0,"first","mobile_agent","{\"task\":")),
            event(chunk(1,args="\"task\":\"查电量\"}")),
            event(chunk(0,args="\"查时间\"}"),"tool_calls"),"data: [DONE]").joinToString("\n")
        val calls = PetDirectChat.readStream(BufferedReader(StringReader(stream))).calls
        assertEquals(listOf("first","second"),calls.map { it.id })
        assertEquals(listOf("查时间","查电量"),calls.map { JSONObject(it.arguments.toString()).getString("task") })
    }

    @Test fun unfinishedEofStillFailsAndContentFilteringIsNotHiddenByDone() {
        assertThrows(IllegalStateException::class.java) {
            PetDirectChat.readStream(BufferedReader(StringReader(event(JSONObject().put("content","半句")))))
        }
        assertThrows(IllegalStateException::class.java) {
            PetDirectChat.readStream(BufferedReader(StringReader(event(JSONObject(),"content_filter")+"\ndata: [DONE]")))
        }
        assertEquals("完成",PetDirectChat.readStream(BufferedReader(StringReader(event(JSONObject().put("content","完成"),"stop")))).text)
    }
}
