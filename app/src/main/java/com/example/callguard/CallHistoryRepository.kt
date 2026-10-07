package com.example.callguard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists CallRecord history locally without requiring dangerous Android call log permissions.
 */
object CallHistoryRepository {
    private const val PREFS_NAME = "callguard_history_prefs"
    private const val KEY_HISTORY = "call_history_json"
    private const val MAX_RECORDS = 200

    private var prefs: SharedPreferences? = null
    private val _historyFlow = MutableStateFlow<List<CallRecord>>(emptyList())
    val historyFlow: StateFlow<List<CallRecord>> = _historyFlow.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            load()
        }
        syncDeviceCallLog(context)
    }

    /**
     * Safely checks and imports any cached call history from Android's CallLog provider.
     * Silently catches SecurityException if permissions or role are not yet granted.
     */
    fun syncDeviceCallLog(context: Context) {
        try {
            val uri = android.provider.CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                android.provider.CallLog.Calls._ID,
                android.provider.CallLog.Calls.NUMBER,
                android.provider.CallLog.Calls.TYPE,
                android.provider.CallLog.Calls.DATE,
                android.provider.CallLog.Calls.DURATION,
            )
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${android.provider.CallLog.Calls.DATE} DESC LIMIT 50",
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION)

                val existing = _historyFlow.value.toMutableList()
                var addedCount = 0

                while (cursor.moveToNext()) {
                    val rawNum = if (numIdx != -1) cursor.getString(numIdx) ?: "Unknown" else "Unknown"
                    val type = if (typeIdx != -1) cursor.getInt(typeIdx) else android.provider.CallLog.Calls.INCOMING_TYPE
                    val date = if (dateIdx != -1) cursor.getLong(dateIdx) else System.currentTimeMillis()
                    val duration = if (durIdx != -1) cursor.getLong(durIdx) else 0L

                    // Check if already in our records
                    val alreadyExists = existing.any {
                        Math.abs(it.timestampMs - date) < 2000L && it.phoneNumber == rawNum
                    }

                    if (!alreadyExists) {
                        val dir = when (type) {
                            android.provider.CallLog.Calls.INCOMING_TYPE,
                            android.provider.CallLog.Calls.MISSED_TYPE -> android.telecom.Call.Details.DIRECTION_INCOMING
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> android.telecom.Call.Details.DIRECTION_OUTGOING
                            else -> android.telecom.Call.Details.DIRECTION_UNKNOWN
                        }
                        existing.add(
                            CallRecord(
                                id = "sys_$date",
                                phoneNumber = rawNum,
                                direction = dir,
                                timestampMs = date,
                                durationSeconds = duration,
                                autoDisconnected = false,
                            ),
                        )
                        addedCount++
                    }
                }

                if (addedCount > 0) {
                    existing.sortByDescending { it.timestampMs }
                    val trimmed = if (existing.size > MAX_RECORDS) existing.take(MAX_RECORDS) else existing
                    _historyFlow.value = trimmed
                    save(trimmed)
                    CallStateLogger.log("HISTORY", "Retrieved $addedCount cached calls from device CallLog")
                }
            }
        } catch (e: Exception) {
            CallStateLogger.log("HISTORY", "Device CallLog sync skipped: ${e.message}")
        }
    }

    fun addRecord(record: CallRecord) {
        val current = _historyFlow.value.toMutableList()
        current.add(0, record) // Most recent first
        val trimmed = if (current.size > MAX_RECORDS) current.take(MAX_RECORDS) else current
        _historyFlow.value = trimmed
        save(trimmed)
        CallStateLogger.log("HISTORY", "Saved call to history: ${record.phoneNumber} (${record.durationSeconds}s)")
    }

    fun clear() {
        _historyFlow.value = emptyList()
        prefs?.edit()?.remove(KEY_HISTORY)?.apply()
        CallStateLogger.log("HISTORY", "Cleared call history")
    }

    private fun load() {
        val raw = prefs?.getString(KEY_HISTORY, null) ?: return
        try {
            val array = JSONArray(raw)
            val list = ArrayList<CallRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    CallRecord(
                        id = obj.optString("id"),
                        phoneNumber = obj.optString("phoneNumber"),
                        direction = obj.optInt("direction"),
                        timestampMs = obj.optLong("timestampMs"),
                        durationSeconds = obj.optLong("durationSeconds"),
                        autoDisconnected = obj.optBoolean("autoDisconnected"),
                    ),
                )
            }
            _historyFlow.value = list
        } catch (_: Exception) {}
    }

    private fun save(records: List<CallRecord>) {
        try {
            val array = JSONArray()
            for (r in records) {
                val obj = JSONObject().apply {
                    put("id", r.id)
                    put("phoneNumber", r.phoneNumber)
                    put("direction", r.direction)
                    put("timestampMs", r.timestampMs)
                    put("durationSeconds", r.durationSeconds)
                    put("autoDisconnected", r.autoDisconnected)
                }
                array.put(obj)
            }
            prefs?.edit()?.putString(KEY_HISTORY, array.toString())?.apply()
        } catch (_: Exception) {}
    }
}
