package com.example.callguard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Handles vibration alert when call duration threshold is reached.
 */
object CallVibrator {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        if (vibrator == null) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }

    fun startAlertVibration() {
        val v = vibrator ?: return
        if (!CallPolicyRepository.getPolicy().vibrationEnabled) return
        try {
            val pattern = longArrayOf(0, 400, 200, 400, 200, 400, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 1)
            }
        } catch (_: Throwable) {}
    }

    fun stop() {
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {}
    }
}
