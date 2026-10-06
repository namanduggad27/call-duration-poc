package com.example.callguard

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Test

class CallStateNamesTest {
    @Test
    fun mapsKnownStates() {
        assertEquals("ACTIVE", CallStateNames.state(Call.STATE_ACTIVE))
        assertEquals("RINGING", CallStateNames.state(Call.STATE_RINGING))
        assertEquals("DIALING", CallStateNames.state(Call.STATE_DIALING))
        assertEquals("DISCONNECTED", CallStateNames.state(Call.STATE_DISCONNECTED))
        assertEquals("HOLDING", CallStateNames.state(Call.STATE_HOLDING))
    }

    @Test
    fun unknownStateIsLabelled() {
        assertEquals("UNKNOWN(999)", CallStateNames.state(999))
    }

    @Test
    fun mapsDirections() {
        assertEquals("INCOMING", CallStateNames.direction(Call.Details.DIRECTION_INCOMING))
        assertEquals("OUTGOING", CallStateNames.direction(Call.Details.DIRECTION_OUTGOING))
        assertEquals("UNKNOWN", CallStateNames.direction(Call.Details.DIRECTION_UNKNOWN))
    }
}
