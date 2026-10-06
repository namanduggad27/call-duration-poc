package com.example.callguard

import java.util.UUID

/**
 * Historical record of a cellular call handled by CallGuard.
 */
data class CallRecord(
    val id: String = UUID.randomUUID().toString(),
    val phoneNumber: String,
    val direction: Int, // Call.Details.DIRECTION_INCOMING, DIRECTION_OUTGOING, or DIRECTION_UNKNOWN
    val timestampMs: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0L,
    val autoDisconnected: Boolean = false,
)
