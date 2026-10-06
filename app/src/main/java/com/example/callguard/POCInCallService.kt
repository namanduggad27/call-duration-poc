package com.example.callguard

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Minimal InCallService: receives the Telecom call lifecycle and hands Call objects to
 * [ActiveCallStore]. Bound by the system once this app holds ROLE_DIALER.
 */
class POCInCallService : InCallService() {

    private val callbacks = HashMap<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        CallStateLogger.log("SERVICE", "POCInCallService created")
    }

    override fun onDestroy() {
        CallStateLogger.log("SERVICE", "POCInCallService destroyed")
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                ActiveCallStore.onStateChanged(call, state)
                maybeSelectPhoneAccount(call, state)
                refreshNotification()
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback)

        ActiveCallStore.onCallAdded(call)
        maybeSelectPhoneAccount(call, stateOf(call))
        refreshNotification()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        ActiveCallStore.onCallRemoved(call)
        refreshNotification()
    }

    @Suppress("DEPRECATION")
    private fun stateOf(call: Call): Int = call.state

    /** Dual-SIM "always ask" case: pick the first call-capable account so the POC can proceed. */
    private fun maybeSelectPhoneAccount(call: Call, state: Int) {
        if (state != Call.STATE_SELECT_PHONE_ACCOUNT) return
        try {
            val telecom = getSystemService(TelecomManager::class.java)
            val handle = telecom?.callCapablePhoneAccounts?.firstOrNull()
            if (handle != null) {
                CallStateLogger.log("SIM", "Call needs a phone account; selecting first available SIM")
                call.phoneAccountSelected(handle, false)
            } else {
                CallStateLogger.log("SIM", "Call needs a phone account but none is available")
            }
        } catch (e: SecurityException) {
            CallStateLogger.log("SIM", "Cannot list phone accounts: ${e.message}")
        }
    }

    // ---- Notification so an incoming call can be answered / the test screen reopened ----------

    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        val manager = NotificationManagerCompat.from(this)
        val primary = ActiveCallStore.state.value.primary
        if (primary == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!manager.areNotificationsEnabled()) {
            CallStateLogger.log("NOTIFY", "Notifications disabled: open the app manually to answer")
            return
        }
        ensureChannel()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ringing = primary.state == Call.STATE_RINGING
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ringing) "Incoming call (POC)" else "Call in progress (POC)")
            .setContentText("State: ${CallStateNames.state(primary.state)} - tap to open test screen")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
        if (ringing) builder.setFullScreenIntent(openApp, true)

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            CallStateLogger.log("NOTIFY", "Notification blocked: ${e.message}")
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "POC calls", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "poc_calls"
        const val NOTIFICATION_ID = 1001
    }
}
