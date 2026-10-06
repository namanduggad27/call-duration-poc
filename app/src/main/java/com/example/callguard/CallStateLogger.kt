package com.example.callguard

import android.telecom.Call
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure helpers that turn Telecom integer constants into readable names. */
object CallStateNames {
    fun state(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        else -> "UNKNOWN($state)"
    }

    fun direction(direction: Int): String = when (direction) {
        Call.Details.DIRECTION_INCOMING -> "INCOMING"
        Call.Details.DIRECTION_OUTGOING -> "OUTGOING"
        else -> "UNKNOWN"
    }
}

/**
 * Logs call state transitions, disconnect attempts and results.
 * Keeps an in-memory ring buffer so the POC screen can show / export it.
 * Phone numbers are intentionally never logged.
 */
object CallStateLogger {
    private const val TAG = "CallGuardPOC"
    private const val MAX_LINES = 300

    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${formatter.format(Date())} [$tag] $message"
        Log.i(TAG, line)
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }

    fun asText(): String = _lines.value.joinToString("\n")

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }
}
