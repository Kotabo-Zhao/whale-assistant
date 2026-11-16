package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class CompanionTaskModelTest {
    @Test fun parsesOneOptimisticStateTransition() {
        val update = CompanionTaskModel.parseUpdate("""{"op":"update","id":"00000000-0000-0000-0000-000000000001","expectedState":"candidate","state":"open"}""")
        assertEquals("candidate", update.expectedState)
        assertEquals("open", update.nextState)
        assertNull(update.reminderAtMs)
    }

    @Test fun parsesFutureReminderButRejectsAmbiguousMutation() {
        val update = CompanionTaskModel.parseUpdate("""{"op":"update","id":"00000000-0000-0000-0000-000000000001","expectedState":"open","reminderAtMs":1900000000000}""")
        assertEquals(1_900_000_000_000L, update.reminderAtMs)
        assertThrows(Exception::class.java) {
            CompanionTaskModel.parseUpdate("""{"op":"update","id":"00000000-0000-0000-0000-000000000001","expectedState":"open","state":"done","reminderAtMs":1900000000000}""")
        }
    }

    @Test fun searchSupportsStableIdStateAndSourceFiltersAndReturnsRevision() {
        val notification = UnderstandingQueue.Item("00000000-0000-0000-0000-000000000001", "续费", "open", "notification:one")
        val shared = UnderstandingQueue.Item("00000000-0000-0000-0000-000000000002", "合同", "open", "share:${"a".repeat(64)}", 1, 2, 250L)
        val result = JSONObject(CompanionTaskModel.execute(true, """{"query":"","state":"open","sourceKind":"shared_material","reminderFromMs":200,"reminderToMs":300}""", { listOf(notification, shared) }))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertEquals(true,CompanionTaskModel.includesSharedMaterial(result))
        assertEquals(true,CompanionTaskModel.includesSharedMaterial(JSONObject(CompanionTaskModel.updated(shared))))
        assertEquals(false,CompanionTaskModel.includesSharedMaterial(JSONObject(CompanionTaskModel.updated(notification))))
        assertEquals(shared.id, item.getString("id"))
        assertEquals("shared_material", item.getString("sourceKind"))
        assertEquals(250L, item.getLong("reminderAtMs"))
        assertEquals(64, item.getString("revision").length)
        val update = CompanionTaskModel.parseUpdate(JSONObject().put("op", "update").put("id", shared.id)
            .put("expectedState", "open").put("expectedRevision", item.getString("revision")).put("state", "done").toString())
        assertEquals(item.getString("revision"), update.expectedRevision)
    }

    @Test fun secretaryItemsStayVisibleWhenNotificationAccessIsOff() {
        val notification = UnderstandingQueue.Item("00000000-0000-0000-0000-000000000001", "通知待办", "open", "notification:one")
        val secretary = UnderstandingQueue.Item("00000000-0000-0000-0000-000000000003", "回电话", "open", "secretary:one")
        val scope = CompanionTaskModel.ReadScope(notifications = false, sharedMaterial = true, conversation = true)
        val result = JSONObject(CompanionTaskModel.execute(scope, """{"query":""}""", { listOf(notification, secretary) }) { null })
        assertEquals(1, result.getJSONArray("items").length())
        assertEquals("conversation", result.getJSONArray("items").getJSONObject(0).getString("sourceKind"))
        assertEquals("notification", result.getJSONArray("omittedSources").getString(0))
        assertEquals(true, result.getString("notice").contains("未授权来源已省略"))
        val capability = CompanionTaskModel.taskCapability(scope)
        assertEquals(true, capability.getBoolean("allowed"))
        assertEquals(false, capability.getBoolean("notificationItems"))
        assertEquals(true, capability.getBoolean("conversationItems"))
    }

    @Test fun closedSourcesDoNotPretendTheListIsEmpty() {
        val scope = CompanionTaskModel.ReadScope(notifications = false, sharedMaterial = false, conversation = false)
        val error = assertThrows(RuntimeFailure::class.java) {
            CompanionTaskModel.execute(scope, """{"query":""}""", { emptyList() }) { null }
        }
        assertEquals("TASK_MODEL_DISABLED", error.code)
        val notification = UnderstandingQueue.Item("00000000-0000-0000-0000-000000000001", "通知待办", "open", "notification:one")
        val denied = CompanionTaskModel.ReadScope(notifications = false, sharedMaterial = false, conversation = true)
        assertThrows(RuntimeFailure::class.java) { CompanionTaskModel.assertMutable(notification, denied) }
    }
}
