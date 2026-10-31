package io.deepseekharness.mobile.companion

import org.junit.Assert.assertThrows
import org.junit.Test

class CompanionSessionAnchorStoreTest {
    @Test fun rejectsOversizedOrMalformedAnchorArchives() {
        assertThrows(Exception::class.java) { CompanionSessionAnchorStore.validateArchive("[{\"sessionId\":\"../bad\",\"role\":\"user\",\"atMs\":1,\"summary\":\"x\"}]".toByteArray()) }
        assertThrows(Exception::class.java) { CompanionSessionAnchorStore.validateArchive(ByteArray(50_001) { ' '.code.toByte() }) }
    }
}
