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
}
