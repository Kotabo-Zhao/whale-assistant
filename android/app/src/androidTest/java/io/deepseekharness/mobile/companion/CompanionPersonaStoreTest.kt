package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CompanionPersonaStoreTest {
    @Test fun characterCardsPersistSwitchAndDelete() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefName = "persona-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefName, mode)
        }
        try {
            val store = CompanionPersonaStore(context)
            assertEquals("鲸鱼娘", store.active().getString("name"))
            val card = store.save(JSONObject().put("name", "验收角色").put("personality", "简短回答").put("systemPrompt", "扮演伙伴").put("worldBook", """{"entries":[]}"""))
            val id = card.getString("id")
            store.activate(id)
            assertEquals("简短回答", CompanionPersonaStore(context).active().getString("personality"))
            assertEquals("扮演伙伴", CompanionPersonaStore(context).active().getString("systemPrompt"))
            assertEquals("""{"entries":[]}""", CompanionPersonaStore(context).active().getString("worldBook"))
            store.delete(id)
            assertEquals("鲸鱼娘", CompanionPersonaStore(context).active().getString("name"))
        } finally { base.deleteSharedPreferences(prefName) }
    }
}
