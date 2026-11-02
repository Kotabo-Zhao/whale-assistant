package io.deepseekharness.mobile.companion

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class UnderstandingModelContractTest {
    @Test fun acceptsEvidenceBoundClaimsAndEmptyResult() {
        UnderstandingModelContract.validate("""{"version":1,"claims":[{"text":"快递到站","evidence_ids":["event-1"]}]}""", "event-1")
        UnderstandingModelContract.validate("""{"version":1,"claims":[]}""", "event-1")
    }
    @Test fun rejectsInventedMissingOrMultipleEvidence() {
        for (ids in listOf("[]", "[\"other\"]", "[\"event-1\",\"other\"]")) {
            assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":1,"claims":[{"text":"快递到站","evidence_ids":$ids}]}""", "event-1") }
        }
    }
    @Test fun rejectsBadVersionAndOversizedText() {
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":99,"claims":[]}""", "x") }
        val text = "甲".repeat(601)
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":1,"claims":[{"text":"$text","evidence_ids":["x"]}]}""", "x") }
    }
    @Test fun itemMustCiteTheActualEvent() {
        UnderstandingModelContract.validate("""{"version":1,"claims":[],"items":[{"title":"领取快递","evidence_ids":["e"]}]}""", "e")
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":1,"claims":[],"items":[{"title":"领取快递","evidence_ids":["other"]}]}""", "e") }
    }
    @Test fun sharedMaterialRebindsEphemeralModelEvidenceToStableSource() {
        val raw = """{"version":1,"claims":[{"text":"会议在周三","evidence_ids":["request-1"]}],"items":[{"title":"准备会议资料","evidence_ids":["request-1"]}]}"""
        val bound = SharedMemoryJobs.bindResultToEvent(raw, "request-1", "share:" + "a".repeat(64))
        val root = JSONObject(bound)
        assertTrue(root.getJSONArray("claims").getJSONObject(0).getJSONArray("evidence_ids").getString(0).startsWith("share:"))
        assertTrue(root.getJSONArray("items").getJSONObject(0).getJSONArray("evidence_ids").getString(0).startsWith("share:"))
    }

    @Test fun rejectsInventedTaskQuoteAndMissingOwnership() {
        val raw = """{"version":2,"claims":[],"items":[{"title":"提交材料","owner":"user","status":"required","action":"提交材料","reason":"明确要求本人","evidence_quote":"请你提交材料","evidence_ids":["e"]}]}"""
        UnderstandingModelContract.validate(raw, "e", "通知：请你提交材料")
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate(raw, "e", "小王已经提交材料") }
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate(raw.replace("user", ""), "e") }
    }

    @Test fun onlyOwnRequiredActionsBecomeCandidates() {
        val items = org.json.JSONArray()
        for (owner in listOf("user", "other", "unknown")) for (status in listOf("required", "completed", "informational"))
            items.put(JSONObject().put("owner", owner).put("status", status))
        val accepted = UnderstandingModelContract.actionableItems(JSONObject().put("version", 2).put("items", items))
        org.junit.Assert.assertEquals(1, accepted.size)
        org.junit.Assert.assertEquals("user", accepted.single().getString("owner"))
        org.junit.Assert.assertEquals("required", accepted.single().getString("status"))
    }
}
