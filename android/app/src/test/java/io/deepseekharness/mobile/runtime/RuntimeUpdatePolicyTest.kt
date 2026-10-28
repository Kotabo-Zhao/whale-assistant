package io.deepseekharness.mobile.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUpdatePolicyTest {
    private val runtimeId = "ubuntu-24.04-arm64-deepseek-harness"

    @Test fun memoryRuntimeIsNewerThanPreviousWhaleRuntime() {
        assertTrue(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.3.6-mobile-583", "a".repeat(64), runtimeId, "0.3.7-whale-memory1", "b".repeat(64)))
        assertTrue(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.3.6", "a".repeat(64), runtimeId, "0.3.7-whale-memory1", "b".repeat(64)))
    }

    @Test fun bundledMarketRuntimeUpgradesTheMemoryVersion() {
        assertTrue(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.3.7-whale-memory1", "a".repeat(64), runtimeId, "0.3.8-whale-market1", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.4.0", "a".repeat(64), runtimeId, "0.3.8-whale-market1", "b".repeat(64)))
    }

    @Test fun reminderRuntimeUpgradesMarketWithoutDowngradingNewerVersions() {
        assertTrue(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.3.8-whale-market1", "a".repeat(64), runtimeId, "0.3.9-whale-reminder1", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.4.0", "a".repeat(64), runtimeId, "0.3.9-whale-reminder1", "b".repeat(64)))
    }

    @Test fun memoryPaginationRuntimeUpgradesKnowledgeVersionOnlyWhenAppropriate() {
        val previousHash = "3a442b514e0ad9e58a920fe24265ca964c6d38d39b1a701b555ce37b68c06965"
        val paginationHash = "6a02a148be9f9e1387b9d8e37dde2d3a882ae26d431791151d65f2e9090906ff"
        val version = "0.3.19-whale-memorypage1"
        assertTrue(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.3.18-whale-knowledge1", previousHash, runtimeId, version, paginationHash))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, version, paginationHash, runtimeId, version, paginationHash))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.4.0", previousHash, runtimeId, version, paginationHash))
        assertFalse(RuntimeUpdatePolicy.isAvailable("other-runtime", "0.3.18", previousHash, runtimeId, version, paginationHash))
    }

    @Test
    fun detectsNewerBundledReleaseOfTheSameRuntime() {
        assertTrue(
            RuntimeUpdatePolicy.isAvailable(
                runtimeId,
                "0.1.7",
                "a".repeat(64),
                runtimeId,
                "0.1.8",
                "b".repeat(64),
            ),
        )
    }

    @Test
    fun oauthFixPreviewUpgradesThePreviousStableRuntime() {
        assertTrue(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.3.0", "a".repeat(64), runtimeId, "0.3.1-mobile-1", "b".repeat(64)))
        assertTrue(RuntimeUpdatePolicy.compareVersions("0.3.1", "0.3.1-mobile-1") > 0)
    }

    @Test
    fun doesNotDowngradeANewerRemoteRelease() {
        assertFalse(
            RuntimeUpdatePolicy.isAvailable(
                runtimeId,
                "0.1.9",
                "a".repeat(64),
                runtimeId,
                "0.1.8",
                "b".repeat(64),
            ),
        )
    }

    @Test
    fun ignoresDifferentRuntimeIdsEqualVersionsAndUnavailableManifests() {
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.1.7", "a".repeat(64), "other", "0.1.8", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "0.1.8", "a".repeat(64), runtimeId, "0.1.8", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(null, "0.1.7", "a".repeat(64), runtimeId, "0.1.8", "b".repeat(64)))
        assertFalse(RuntimeUpdatePolicy.isAvailable(runtimeId, "custom-label", "a".repeat(64), runtimeId, "0.1.8", "b".repeat(64)))
    }

    @Test
    fun comparesPrereleasesBeforeStableReleases() {
        assertTrue(RuntimeUpdatePolicy.compareVersions("0.1.8", "0.1.8-rc.6") > 0)
        assertTrue(
            RuntimeUpdatePolicy.compareVersions(
                "0.1.9-mobile-shizuku.5",
                "0.1.9-mobile-shizuku.4",
            ) > 0,
        )
        assertTrue(RuntimeUpdatePolicy.compareVersions("0.1.9", "0.1.10") < 0)
        assertTrue(RuntimeUpdatePolicy.compareVersions("2026.08.18", "2026.08.17") > 0)
        assertTrue(RuntimeUpdatePolicy.compareVersions("custom", "0.1.8") == 0)
    }
}
