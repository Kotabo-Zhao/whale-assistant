package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OwnerProfileTest {
    private fun request(rows: JSONArray, value: String="测试称呼", field: String="preferred_name") = JSONObject()
        .put("action","set").put("field",field).put("value",value).put("operationId",UUID.randomUUID().toString())
        .put("expectedRevision",OwnerProfile.revision((0 until rows.length()).map(rows::getJSONObject),field))
    private fun apply(rows: JSONArray,request: JSONObject,auto: Boolean=false,user: Boolean=false,deleted: MutableSet<String> = mutableSetOf(),now: Long=1000) =
        OwnerProfile.change(rows,request,auto,user,{ it in deleted },{ deleted.add(it.getString("text")) },now)
    private fun field(rows: JSONArray,key: String="preferred_name",now: Long=1001) = OwnerProfile.view((0 until rows.length()).map(rows::getJSONObject),now).getJSONArray("fields").let { fields ->
        (0 until fields.length()).map(fields::getJSONObject).single { it.getString("field")==key }
    }
    @Test fun unconfirmedProfileIsNotExposedAndReplayDoesNotAddRows() {
        val rows=JSONArray();val req=request(rows)
        assertTrue(apply(rows,req).getBoolean("pending"));assertFalse(field(rows).has("value"))
        assertTrue(apply(rows,req).getBoolean("idempotent"));assertEquals(1,rows.length())
    }
    @Test fun correctionWinsButStaleUpdateCannotOverwriteIt() {
        val rows=JSONArray();apply(rows,request(rows),auto=true)
        val correction=request(rows,"新称呼");val stale=request(rows,"并发旧写入")
        apply(rows,correction);assertEquals("新称呼",field(rows).getString("value"))
        assertThrows(Exception::class.java) { apply(rows,stale) }
        assertTrue(rows.getJSONObject(0).getJSONArray("history").length()>0)
    }
    @Test fun expiryAndConflictingImportsNeverBecomeFacts() {
        val rows=JSONArray();apply(rows,request(rows,"近期测试","current_focus").put("validUntilMs",2000),auto=true)
        assertEquals("expired",field(rows,"current_focus",2001).getString("state"));assertFalse(field(rows,"current_focus",2001).has("value"))
        val other=JSONObject(rows.getJSONObject(0).toString()).put("id",UUID.randomUUID().toString()).put("text","另一安排")
        rows.put(other);assertEquals("conflict",field(rows,"current_focus").getString("state"));assertFalse(field(rows,"current_focus").has("value"))
    }
    @Test fun forgottenAndCorrectedValuesDoNotResurrectAutomatically() {
        val rows=JSONArray();val deleted=mutableSetOf<String>();apply(rows,request(rows,"旧称呼"),auto=true,deleted=deleted)
        apply(rows,request(rows,"新称呼"),deleted=deleted)
        val forget=request(rows).put("action","forget");apply(rows,forget,deleted=deleted)
        assertEquals(0,rows.length());assertTrue(deleted.containsAll(listOf("旧称呼","新称呼")))
        assertThrows(Exception::class.java) { apply(rows,request(rows,"旧称呼"),auto=true,deleted=deleted) }
        apply(rows,request(rows,"旧称呼"),user=true,deleted=deleted);assertEquals("旧称呼",field(rows).getString("value"))
    }
    @Test fun missingExpiryInvalidTimezoneAndUnsafeValuesAreRejected() {
        val rows=JSONArray()
        assertThrows(Exception::class.java) { apply(rows,request(rows,"下周出差","current_focus")) }
        assertThrows(Exception::class.java) { apply(rows,request(rows,"猜测时区","timezone")) }
        assertThrows(Exception::class.java) { apply(rows,request(rows,"一\n二")) }
        assertEquals(0,rows.length())
    }
}
