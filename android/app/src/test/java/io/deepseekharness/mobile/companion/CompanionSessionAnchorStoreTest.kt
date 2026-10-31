package io.deepseekharness.mobile.companion

import org.junit.Assert.assertThrows
import org.junit.Test

class CompanionSessionAnchorStoreTest {
    @Test fun rejectsOversizedOrMalformedAnchorArchives() {
        assertThrows(Exception::class.java) { CompanionSessionAnchorStore.validateArchive("[{\"sessionId\":\"bad id\",\"role\":\"user\",\"atMs\":1,\"summary\":\"x\"}]".toByteArray()) }
        assertThrows(Exception::class.java) { CompanionSessionAnchorStore.validateArchive("[]".toByteArray() + ByteArray(20_001)) }
    }
}
