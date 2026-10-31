package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CompanionAgentAccessInstrumentedTest {
    @Test fun persistedBubbleScopeAndRevocationAreEnforced() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "agent-access-${UUID.randomUUID()}-"
        val opened = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                opened.add(prefix + name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        try {
            val store = CompanionStore(context)
            val session = UUID.randomUUID().toString()
            val request = JSONObject().put("callerSession", session)
            val access = CompanionAgentAccess(context, request)
            access.requireMemory() // An ordinary main conversation is not a pet conversation.
            assertEquals("KNOWLEDGE_MODEL_DISABLED", assertThrows(RuntimeFailure::class.java) { access.requireKnowledge() }.code)
            store.knowledgeModelAccess = true
            access.requireKnowledge()
            store.knowledgeModelAccess = false
            assertEquals("KNOWLEDGE_MODEL_DISABLED", assertThrows(RuntimeFailure::class.java) { access.requireKnowledge() }.code)
            BubbleSessionRegistry(context).record(session)
            assertEquals("MEMORY_BUBBLE_DISABLED", assertThrows(RuntimeFailure::class.java) { access.requireMemory() }.code)
            assertTrue(BubbleSessionRegistry(context).contains(session))
            val blocked = JSONObject(CompanionAgentAccess(context, request).capabilities())
            assertFalse(blocked.getJSONObject("memory").getBoolean("allowed"))
            assertEquals("bubble", blocked.getString("scope"))
            store.bubbleMemoryAccess = true
            access.requireMemory()
            store.bubbleMemoryAccess = false
            assertEquals("MEMORY_BUBBLE_DISABLED", assertThrows(RuntimeFailure::class.java) { access.requireMemory() }.code)
            store.bubbleMemoryAccess = true
            CompanionMemoryStore(context).enabled = false
            assertEquals("MEMORY_DISABLED", assertThrows(RuntimeFailure::class.java) { access.requireMemory() }.code)
            // Capabilities remain readable without exposing memory contents when access is off.
            assertFalse(JSONObject(access.capabilities()).getJSONObject("memory").getBoolean("allowed"))
            assertEquals("MEMORY_SESSION_UNAVAILABLE", assertThrows(RuntimeFailure::class.java) {
                CompanionAgentAccess(context, JSONObject())
            }.code)
            assertEquals("MEMORY_SESSION_UNAVAILABLE", assertThrows(RuntimeFailure::class.java) {
                CompanionAgentAccess(context, JSONObject().put("callerSession", "../bad"))
            }.code)
        } finally {
            opened.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
