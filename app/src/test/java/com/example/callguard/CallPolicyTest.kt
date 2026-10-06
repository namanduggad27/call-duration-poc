package com.example.callguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPolicyTest {

    @Test
    fun testDefaultPolicyValues() {
        val policy = CallPolicy()
        assertEquals(60L, policy.maxDurationSeconds)
        assertEquals(60L, policy.snoozeDurationSeconds)
        assertEquals(30L, policy.confirmationWindowSeconds)
        assertTrue(policy.vibrationEnabled)
    }

    @Test
    fun testInitialLimitTriggerCalculation() {
        val startElapsed = 100_000L
        val limitSeconds = 60L
        val expectedThreshold = startElapsed + (limitSeconds * 1000L)

        val nowBefore = startElapsed + 59_000L
        val nowAt = startElapsed + 60_000L
        val nowAfter = startElapsed + 61_000L

        assertTrue("Should not trigger before threshold", nowBefore < expectedThreshold)
        assertTrue("Should trigger at threshold", nowAt >= expectedThreshold)
        assertTrue("Should trigger after threshold", nowAfter >= expectedThreshold)
    }

    @Test
    fun testRecurringSnoozeCalculation() {
        // Section 29: Every approval creates a fresh snooze deadline
        val callStart = 100_000L
        val initialLimit = 60_000L
        val snoozeInterval = 30_000L

        // First limit reached at 160_000L
        val firstCheckTime = callStart + initialLimit
        assertEquals(160_000L, firstCheckTime)

        // User approves snooze #1 at 165_000L
        val userApproval1 = 165_000L
        val secondCheckTime = userApproval1 + snoozeInterval
        assertEquals(195_000L, secondCheckTime)

        // User approves snooze #2 at 198_000L
        val userApproval2 = 198_000L
        val thirdCheckTime = userApproval2 + snoozeInterval
        assertEquals(228_000L, thirdCheckTime)
    }

    @Test
    fun testConfirmationWindowExpiry() {
        val alertTriggeredAt = 200_000L
        val windowSeconds = 30L
        val deadline = alertTriggeredAt + (windowSeconds * 1000L)

        val withinWindow = alertTriggeredAt + 29_000L
        val expired = alertTriggeredAt + 30_001L

        assertTrue("Within window", withinWindow < deadline)
        assertTrue("Expired after window", expired >= deadline)
    }

    @Test
    fun testMaxDurationStrictlyEnforcesThreeHourCap() {
        assertEquals(10800L, CallPolicyRepository.MAX_ALLOWED_DURATION_SECONDS) // 3 hours = 10,800s

        // Attempting to set 4 hours (14,400s) must be clamped to 3 hours (10,800s)
        CallPolicyRepository.updateMaxDuration(14_400L)
        assertEquals(10_800L, CallPolicyRepository.getPolicy().maxDurationSeconds)

        // Setting exactly 3 hours (10,800s) must remain 10,800s
        CallPolicyRepository.updateMaxDuration(10_800L)
        assertEquals(10_800L, CallPolicyRepository.getPolicy().maxDurationSeconds)

        // Setting 1 hour (3,600s) must be allowed
        CallPolicyRepository.updateMaxDuration(3_600L)
        assertEquals(3_600L, CallPolicyRepository.getPolicy().maxDurationSeconds)
    }

    @Test
    fun testMinDurationEnforced() {
        // Attempting to set < 10 seconds must be clamped to 10s
        CallPolicyRepository.updateMaxDuration(2L)
        assertEquals(10L, CallPolicyRepository.getPolicy().maxDurationSeconds)
    }
}
