package com.example.callguard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-configurable duration control policy.
 * Strict Constraint: Max call duration cannot exceed 3 hours (180 minutes / 10,800 seconds).
 */
data class CallPolicy(
    val maxDurationSeconds: Long = 60L, // Initial limit before presence check (default 60s for testing)
    val snoozeDurationSeconds: Long = 60L, // Extension duration after user approves presence (default 60s)
    val confirmationWindowSeconds: Long = 30L, // Time allowed to respond before automatic disconnect
    val vibrationEnabled: Boolean = true,
)

object CallPolicyRepository {
    const val MAX_ALLOWED_DURATION_SECONDS = 3 * 60 * 60L // 3 hours = 10,800 seconds
    const val MIN_ALLOWED_DURATION_SECONDS = 10L // 10 seconds

    const val MAX_ALLOWED_SNOOZE_SECONDS = 60 * 60L // 1 hour max snooze
    const val MIN_ALLOWED_SNOOZE_SECONDS = 10L

    private const val PREFS_NAME = "callguard_policy_prefs"
    private const val KEY_MAX_DURATION = "max_duration_seconds"
    private const val KEY_SNOOZE_DURATION = "snooze_duration_seconds"
    private const val KEY_CONFIRMATION_WINDOW = "confirmation_window_seconds"
    private const val KEY_VIBRATION = "vibration_enabled"

    private var prefs: SharedPreferences? = null

    private val _policyFlow = MutableStateFlow(CallPolicy())
    val policyFlow: StateFlow<CallPolicy> = _policyFlow.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            load()
        }
    }

    fun getPolicy(): CallPolicy = _policyFlow.value

    fun updateMaxDuration(seconds: Long) {
        val clamped = seconds.coerceIn(MIN_ALLOWED_DURATION_SECONDS, MAX_ALLOWED_DURATION_SECONDS)
        val current = _policyFlow.value
        val updated = current.copy(maxDurationSeconds = clamped)
        save(updated)
    }

    fun updateSnoozeDuration(seconds: Long) {
        val clamped = seconds.coerceIn(MIN_ALLOWED_SNOOZE_SECONDS, MAX_ALLOWED_SNOOZE_SECONDS)
        val current = _policyFlow.value
        val updated = current.copy(snoozeDurationSeconds = clamped)
        save(updated)
    }

    fun updateConfirmationWindow(seconds: Long) {
        val current = _policyFlow.value
        val updated = current.copy(confirmationWindowSeconds = seconds.coerceIn(10L, 120L))
        save(updated)
    }

    fun setVibrationEnabled(enabled: Boolean) {
        val current = _policyFlow.value
        val updated = current.copy(vibrationEnabled = enabled)
        save(updated)
    }

    private fun load() {
        val p = prefs ?: return
        val loaded = CallPolicy(
            maxDurationSeconds = p.getLong(KEY_MAX_DURATION, 60L)
                .coerceIn(MIN_ALLOWED_DURATION_SECONDS, MAX_ALLOWED_DURATION_SECONDS),
            snoozeDurationSeconds = p.getLong(KEY_SNOOZE_DURATION, 60L)
                .coerceIn(MIN_ALLOWED_SNOOZE_SECONDS, MAX_ALLOWED_SNOOZE_SECONDS),
            confirmationWindowSeconds = p.getLong(KEY_CONFIRMATION_WINDOW, 30L),
            vibrationEnabled = p.getBoolean(KEY_VIBRATION, true),
        )
        _policyFlow.value = loaded
    }

    private fun save(policy: CallPolicy) {
        _policyFlow.value = policy
        prefs?.edit()
            ?.putLong(KEY_MAX_DURATION, policy.maxDurationSeconds)
            ?.putLong(KEY_SNOOZE_DURATION, policy.snoozeDurationSeconds)
            ?.putLong(KEY_CONFIRMATION_WINDOW, policy.confirmationWindowSeconds)
            ?.putBoolean(KEY_VIBRATION, policy.vibrationEnabled)
            ?.apply()
    }
}
