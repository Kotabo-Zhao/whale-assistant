package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException

class UnderstandingFailureTest {
    @Test fun mapsSharedModelFailuresWithoutLeakingRemoteDetails() {
        assertEquals("credentials", UnderstandingFailure.code(UnderstandingRequestException("credentials", "secret detail")))
        assertEquals("timeout", UnderstandingFailure.code(SocketTimeoutException("private detail")))
        assertEquals("network", UnderstandingFailure.code(java.io.IOException("private detail")))
        assertEquals("invalid_model_output", UnderstandingFailure.code(IllegalArgumentException("private detail")))
        assertEquals("processing_failed", UnderstandingFailure.code(IllegalStateException("private detail")))
    }

    @Test fun preservesLongBackoffForConfigurationFailures() {
        assertEquals(6 * 60 * 60_000L, UnderstandingFailure.retryDelayMs("credentials"))
        assertEquals(6 * 60 * 60_000L, UnderstandingFailure.retryDelayMs("configuration"))
        assertEquals(30 * 60_000L, UnderstandingFailure.retryDelayMs("network"))
        assertEquals("模型服务限流", UnderstandingFailure.summary("rate_limited"))
    }
}
