package com.example.callguard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long we wait for Telecom to confirm a requested disconnect before calling it a FAIL. */
const val DISCONNECT_TIMEOUT_MS = 5_000L

enum class DisconnectOutcome { PENDING, TELECOM_CONFIRMED, NO_CONFIRMATION }

enum class Verdict { PENDING, PASS, FAIL }

/**
 * One manual "Disconnect Test Call" attempt.
 * [remoteConfirmed] is the human check: did the OTHER phone really lose the call?
 */
data class TestRecord(
    val callId: Int,
    val direction: Int,
    val stateAtRequest: Int,
    val requestedAtElapsedMs: Long,
    val endedAtElapsedMs: Long? = null,
    val outcome: DisconnectOutcome = DisconnectOutcome.PENDING,
    val remoteConfirmed: Boolean? = null,
)

/** Pure decision logic (unit-tested). */
fun evaluateDisconnect(
    requestedAtMs: Long,
    endedAtMs: Long?,
    nowMs: Long,
    timeoutMs: Long = DISCONNECT_TIMEOUT_MS,
): DisconnectOutcome {
    if (endedAtMs != null) {
        return if (endedAtMs - requestedAtMs <= timeoutMs) {
            DisconnectOutcome.TELECOM_CONFIRMED
        } else {
            DisconnectOutcome.NO_CONFIRMATION
        }
    }
    return if (nowMs - requestedAtMs >= timeoutMs) {
        DisconnectOutcome.NO_CONFIRMATION
    } else {
        DisconnectOutcome.PENDING
    }
}

/** PASS only when Telecom confirmed AND a human confirmed the remote side really dropped. */
fun verdictOf(record: TestRecord): Verdict = when {
    record.outcome == DisconnectOutcome.NO_CONFIRMATION -> Verdict.FAIL
    record.remoteConfirmed == false -> Verdict.FAIL
    record.outcome == DisconnectOutcome.TELECOM_CONFIRMED && record.remoteConfirmed == true -> Verdict.PASS
    else -> Verdict.PENDING
}

/** Falls back to the initial state when Telecom reports an unknown direction. */
fun inferDirection(reported: Int, initialState: Int): Int = when {
    reported == Call.Details.DIRECTION_INCOMING || reported == Call.Details.DIRECTION_OUTGOING -> reported
    initialState == Call.STATE_RINGING -> Call.Details.DIRECTION_INCOMING
    initialState == Call.STATE_DIALING ||
        initialState == Call.STATE_CONNECTING ||
        initialState == Call.STATE_SELECT_PHONE_ACCOUNT -> Call.Details.DIRECTION_OUTGOING
    else -> Call.Details.DIRECTION_UNKNOWN
}

/** UI-facing immutable view of a tracked call (no phone number stored). */
data class CallView(
    val id: Int,
    val state: Int,
    val direction: Int,
    val activeSinceElapsedMs: Long?,
)

data class StoreState(
    val calls: List<CallView> = emptyList(),
    val records: List<TestRecord> = emptyList(),
) {
    /** The call the test screen acts on: ACTIVE first, then ringing, dialing, holding. */
    val primary: CallView?
        get() = PRIORITY.firstNotNullOfOrNull { s -> calls.firstOrNull { it.state == s } } ?: calls.firstOrNull()

    private companion object {
        val PRIORITY = listOf(
            Call.STATE_ACTIVE,
            Call.STATE_RINGING,
            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_HOLDING,
        )
    }
}

/**
 * Process-wide holder of the Call objects handed to us by [POCInCallService].
 * All calls happen on the main thread (Telecom callbacks + UI).
 */
object ActiveCallStore {
    private class Entry(
        val id: Int,
        val call: Call,
        val direction: Int,
        var state: Int,
        var activeSince: Long?,
    )

    private val entries = ArrayList<Entry>()
    private var records: List<TestRecord> = emptyList()
    private var nextId = 1
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    // ---- Called by POCInCallService -------------------------------------------------------

    fun onCallAdded(call: Call) {
        val initial = stateOf(call)
        val reported = call.details?.callDirection ?: Call.Details.DIRECTION_UNKNOWN
        val entry = Entry(
            id = nextId++,
            call = call,
            direction = inferDirection(reported, initial),
            state = initial,
            activeSince = if (initial == Call.STATE_ACTIVE) SystemClock.elapsedRealtime() else null,
        )
        entries.add(entry)
        CallStateLogger.log(
            "CALL",
            "onCallAdded #${entry.id} dir=${CallStateNames.direction(entry.direction)} " +
                "state=${CallStateNames.state(initial)}",
        )
        publish()
    }

    fun onStateChanged(call: Call, newState: Int) {
        val entry = find(call) ?: return
        val old = entry.state
        entry.state = newState
        if (newState == Call.STATE_ACTIVE && entry.activeSince == null) {
            entry.activeSince = SystemClock.elapsedRealtime()
        }
        CallStateLogger.log(
            "CALL",
            "#${entry.id} ${CallStateNames.state(old)} -> ${CallStateNames.state(newState)}",
        )
        if (newState == Call.STATE_DISCONNECTED) markEnded(entry, "state=DISCONNECTED")
        publish()
    }

    fun onCallRemoved(call: Call) {
        val entry = find(call) ?: return
        CallStateLogger.log("CALL", "onCallRemoved #${entry.id}")
        markEnded(entry, "onCallRemoved")
        entries.remove(entry)
        publish()
    }

    // ---- Exposed to the test screen -------------------------------------------------------

    /** The currently relevant Telecom [Call] object (POC requirement: expose the active call). */
    fun primaryCall(): Call? = primaryEntry()?.call

    fun answer() {
        val e = entries.firstOrNull { it.state == Call.STATE_RINGING } ?: return
        CallStateLogger.log("ACTION", "answer() on #${e.id}")
        e.call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject() {
        val e = entries.firstOrNull { it.state == Call.STATE_RINGING } ?: return
        CallStateLogger.log("ACTION", "reject() on #${e.id}")
        e.call.reject(false, null)
    }

    /** The critical POC action: ask Telecom to end the live call and verify it really ends. */
    fun disconnectTest() {
        val e = primaryEntry()
        if (e == null) {
            CallStateLogger.log("DISCONNECT", "No call to disconnect")
            return
        }
        if (e.state == Call.STATE_RINGING) {
            CallStateLogger.log("DISCONNECT", "Call is ringing: use Reject, not Disconnect Test")
            return
        }
        val record = TestRecord(
            callId = e.id,
            direction = e.direction,
            stateAtRequest = e.state,
            requestedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        records = records + record
        CallStateLogger.log(
            "DISCONNECT",
            "REQUEST call.disconnect() on #${e.id} dir=${CallStateNames.direction(e.direction)} " +
                "state=${CallStateNames.state(e.state)}",
        )
        publish()
        try {
            e.call.disconnect()
        } catch (t: Throwable) {
            CallStateLogger.log("DISCONNECT", "disconnect() THREW ${t.javaClass.simpleName}: ${t.message}")
            replaceRecord(record) { it.copy(outcome = DisconnectOutcome.NO_CONFIRMATION) }
            publish()
            return
        }
        handler.postDelayed({ onDisconnectTimeout(record) }, DISCONNECT_TIMEOUT_MS)
    }

    /** Human verification: did the other phone's call really end? Applies to the latest test. */
    fun setRemoteResult(remoteCallEnded: Boolean) {
        val last = records.lastOrNull() ?: return
        replaceRecord(last) { it.copy(remoteConfirmed = remoteCallEnded) }
        CallStateLogger.log(
            "RESULT",
            "Human check for test on #${last.callId}: remote call ended = $remoteCallEnded",
        )
        publish()
    }

    // ---- Internals ------------------------------------------------------------------------

    private fun onDisconnectTimeout(record: TestRecord) {
        val current = records.firstOrNull {
            it.callId == record.callId && it.requestedAtElapsedMs == record.requestedAtElapsedMs
        } ?: return
        if (current.outcome != DisconnectOutcome.PENDING) return
        val now = SystemClock.elapsedRealtime()
        val outcome = evaluateDisconnect(current.requestedAtElapsedMs, null, now)
        CallStateLogger.log(
            "DISCONNECT",
            "TIMEOUT after ${DISCONNECT_TIMEOUT_MS}ms: Telecom did NOT confirm call end -> $outcome",
        )
        replaceRecord(current) { it.copy(outcome = outcome) }
        publish()
    }

    private fun markEnded(entry: Entry, via: String) {
        val now = SystemClock.elapsedRealtime()
        val pending = records.firstOrNull {
            it.callId == entry.id && it.outcome == DisconnectOutcome.PENDING && it.endedAtElapsedMs == null
        } ?: return
        val outcome = evaluateDisconnect(pending.requestedAtElapsedMs, now, now)
        CallStateLogger.log(
            "DISCONNECT",
            "Telecom confirmed end via $via ${now - pending.requestedAtElapsedMs}ms after request -> $outcome",
        )
        replaceRecord(pending) { it.copy(endedAtElapsedMs = now, outcome = outcome) }
    }

    private fun replaceRecord(target: TestRecord, transform: (TestRecord) -> TestRecord) {
        records = records.map { if (it == target) transform(it) else it }
    }

    private fun primaryEntry(): Entry? {
        val order = listOf(
            Call.STATE_ACTIVE,
            Call.STATE_RINGING,
            Call.STATE_DIALING,
            Call.STATE_CONNECTING,
            Call.STATE_HOLDING,
        )
        return order.firstNotNullOfOrNull { s -> entries.firstOrNull { it.state == s } } ?: entries.firstOrNull()
    }

    private fun find(call: Call): Entry? = entries.firstOrNull { it.call === call }

    @Suppress("DEPRECATION")
    private fun stateOf(call: Call): Int = call.state

    private fun publish() {
        _state.value = StoreState(
            calls = entries.map { CallView(it.id, it.state, it.direction, it.activeSince) },
            records = records,
        )
    }
}
