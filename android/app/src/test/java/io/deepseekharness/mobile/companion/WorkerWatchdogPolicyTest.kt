package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkerWatchdogPolicyTest {
    private fun row()=JSONObject().put("id","a").put("revision",1).put("state","running").put("reportState",JSONObject.NULL)
        .put("health",JSONObject().put("runId","run").put("startedAt",1000000).put("sequence",5)
            .put("heartbeatAt",1100000).put("lastProgressAt",1000000).put("repeated",5)
            .put("recent",JSONArray().put(JSONObject().put("sequence",4)).put(JSONObject().put("sequence",5))))
    @Test fun stalledEvidenceIsDistinctFromServiceSilenceAndProviderBackoff() {
        val value=row()
        assertEquals("stalled",WorkerWatchdogPolicy.reason(value,1100000))
        assertEquals("unresponsive",WorkerWatchdogPolicy.reason(value,1300000))
        value.getJSONObject("health").put("retryUntil",1290000)
        assertNull(WorkerWatchdogPolicy.reason(value,1300000))
        value.put("state","waiting")
        assertNull(WorkerWatchdogPolicy.reason(value,1500000))
    }
    @Test fun lateAssessmentCannotAffectChangedRunProgressOrFinalReport() {
        val expected=row();val actual=JSONObject(expected.toString())
        assertTrue(WorkerWatchdogPolicy.same(expected,actual))
        actual.getJSONObject("health").put("sequence",6)
        assertFalse(WorkerWatchdogPolicy.same(expected,actual))
        actual.getJSONObject("health").put("sequence",5).put("runId","new")
        assertFalse(WorkerWatchdogPolicy.same(expected,actual))
        actual.getJSONObject("health").put("runId","run")
        actual.put("reportState","completed")
        assertFalse(WorkerWatchdogPolicy.same(expected,actual))
    }
    @Test fun modelMustCiteTwoDistinctActualObservationsToPause() {
        val value=row()
        assertEquals("hold",WorkerWatchdogPolicy.decision(value,"""{"verdict":"hold","reason":"重复读取而未推进","evidence":[4,5]}""").first)
        for(evidence in listOf("[4,4]","[4,9]","[]")) {
            assertThrows(IllegalArgumentException::class.java) {
                WorkerWatchdogPolicy.decision(value,"""{"verdict":"hold","reason":"重复","evidence":$evidence}""")
            }
        }
    }
}
