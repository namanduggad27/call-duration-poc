package com.example.callguard

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Test

class DisconnectEvaluationTest {

    @Test
    fun pendingBeforeTimeoutWithoutEnd() {
        assertEquals(DisconnectOutcome.PENDING, evaluateDisconnect(1_000, null, 2_000))
    }

    @Test
    fun noConfirmationAfterTimeoutWithoutEnd() {
        assertEquals(
            DisconnectOutcome.NO_CONFIRMATION,
            evaluateDisconnect(1_000, null, 1_000 + DISCONNECT_TIMEOUT_MS),
        )
    }

    @Test
    fun confirmedWhenEndedWithinTimeout() {
        assertEquals(DisconnectOutcome.TELECOM_CONFIRMED, evaluateDisconnect(1_000, 1_300, 1_300))
    }

    @Test
    fun endedTooLateIsNotConfirmed() {
        assertEquals(
            DisconnectOutcome.NO_CONFIRMATION,
            evaluateDisconnect(1_000, 1_000 + DISCONNECT_TIMEOUT_MS + 1, 9_999),
        )
    }

    private fun record(outcome: DisconnectOutcome, remote: Boolean?) = TestRecord(
        callId = 1,
        direction = Call.Details.DIRECTION_OUTGOING,
        stateAtRequest = Call.STATE_ACTIVE,
        requestedAtElapsedMs = 0,
        outcome = outcome,
        remoteConfirmed = remote,
    )

    @Test
    fun passRequiresTelecomAndHumanConfirmation() {
        assertEquals(Verdict.PASS, verdictOf(record(DisconnectOutcome.TELECOM_CONFIRMED, true)))
    }

    @Test
    fun telecomConfirmedButRemoteStillConnectedIsFail() {
        assertEquals(Verdict.FAIL, verdictOf(record(DisconnectOutcome.TELECOM_CONFIRMED, false)))
    }

    @Test
    fun noTelecomConfirmationIsFail() {
        assertEquals(Verdict.FAIL, verdictOf(record(DisconnectOutcome.NO_CONFIRMATION, null)))
    }

    @Test
    fun unansweredIsPending() {
        assertEquals(Verdict.PENDING, verdictOf(record(DisconnectOutcome.TELECOM_CONFIRMED, null)))
        assertEquals(Verdict.PENDING, verdictOf(record(DisconnectOutcome.PENDING, null)))
    }

    @Test
    fun directionFallsBackToInitialState() {
        assertEquals(
            Call.Details.DIRECTION_INCOMING,
            inferDirection(Call.Details.DIRECTION_UNKNOWN, Call.STATE_RINGING),
        )
        assertEquals(
            Call.Details.DIRECTION_OUTGOING,
            inferDirection(Call.Details.DIRECTION_UNKNOWN, Call.STATE_DIALING),
        )
        assertEquals(
            Call.Details.DIRECTION_OUTGOING,
            inferDirection(Call.Details.DIRECTION_OUTGOING, Call.STATE_RINGING),
        )
    }
}
