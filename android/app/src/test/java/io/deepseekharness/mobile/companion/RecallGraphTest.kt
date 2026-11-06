package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecallGraphTest {
    @Test fun followsMemorySourceThenAdjacentChunkWithEvidencePath() {
        val doc = "a".repeat(64)
        val first = KnowledgeChunks.recordId(doc,0); val second = KnowledgeChunks.recordId(doc,1)
        fun row(id: String, index: Int) = JSONObject().put("id",id).put("text","原文第 $index 段")
            .put("documentId",doc).put("chunkIndex",index).put("chunkCount",2)
        val notes = mapOf(first to row(first,0), second to row(second,1))
        val memory = JSONObject().put("id","m").put("text","已确认事实").put("sourceRecordId",first)
            .put("sourceRevision",CompanionMemorySuppression.digest(notes.getValue(first).getString("text")))
        val result = RecallGraph(notes::get,{ memory }).collect(listOf(RecallGraph.Link("memory","m")))
        assertEquals(3,result.getJSONArray("nodes").length())
        assertTrue(result.getJSONArray("edges").toString().contains("memory_source"))
        assertTrue(result.getJSONArray("edges").toString().contains("next_chunk"))
        assertEquals(2,result.getJSONArray("nodes").getJSONObject(2).getInt("depth"))
    }
    @Test fun changedOrDeletedSourcesAreUnresolvedAndNeverReplacedByGuesses() {
        val id = "12345678-1234-1234-1234-123456789012"
        val memory = JSONObject().put("id","m").put("text","旧事实").put("sourceRecordId",id).put("sourceRevision","b".repeat(64))
        val changed = JSONObject().put("id",id).put("text","已经纠正")
        val stale = RecallGraph({ changed },{ memory }).collect(listOf(RecallGraph.Link("memory","m")))
        assertEquals(1,stale.getJSONArray("nodes").length())
        assertEquals("revision_changed",stale.getJSONArray("unresolved").getJSONObject(0).getString("reason"))
        val removed = RecallGraph({ null },{ memory }).collect(listOf(RecallGraph.Link("memory","m")))
        assertEquals("missing",removed.getJSONArray("unresolved").getJSONObject(0).getString("reason"))
    }
    @Test fun boundedCyclesAndIndependentIntent() {
        val doc="c".repeat(64)
        val rows = (0..20).associate { index -> KnowledgeChunks.recordId(doc,index) to JSONObject()
            .put("id",KnowledgeChunks.recordId(doc,index)).put("text","x".repeat(1400)).put("documentId",doc).put("chunkIndex",index).put("chunkCount",21) }
        val result = RecallGraph(rows::get,{ null }).collect(listOf(RecallGraph.Link("knowledge",KnowledgeChunks.recordId(doc,10))),2,3)
        assertEquals(3,result.getJSONArray("nodes").length()); assertTrue(result.getBoolean("bounded"))
        assertEquals(1200,result.getJSONArray("nodes").getJSONObject(0).getString("text").length)
        assertTrue(PetIntentRouter.parse("""{"intent":"chat","independent":true}""").independent)
        assertFalse(PetIntentRouter.parse("""{"intent":"agent","independent":true}""").independent)
    }
}
