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
