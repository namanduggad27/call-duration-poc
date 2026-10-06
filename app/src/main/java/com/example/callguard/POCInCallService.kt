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
 * InCallService: receives Telecom call lifecycle, launches CallActivity,
 * and maintains notification actions for answering, declining, snoozing, and hanging up.
 */
class POCInCallService : InCallService() {

    private val callbacks = HashMap<Call, Call.Callback>()

    override fun onCreate() {
        super.onCreate()
        CallPolicyRepository.init(this)
        CallVibrator.init(this)
        CallStateLogger.log("SERVICE", "POCInCallService created")
    }

    override fun onDestroy() {
        CallStateLogger.log("SERVICE", "POCInCallService destroyed")
        CallVibrator.stop()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ANSWER -> {
                ActiveCallStore.answer()
            }
            ACTION_REJECT -> {
                ActiveCallStore.reject()
            }
            ACTION_SNOOZE -> {
                ActiveCallStore.snooze()
            }
            ACTION_DISCONNECT -> {
                ActiveCallStore.disconnectTest()
            }
        }
        return START_NOT_STICKY
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
        launchCallUi()
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        ActiveCallStore.onCallRemoved(call)
        refreshNotification()
    }

    private fun launchCallUi() {
        try {
            val intent = Intent(this, CallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            CallStateLogger.log("SERVICE", "Could not start CallActivity directly: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun stateOf(call: Call): Int = call.state

    /** Dual-SIM "always ask" case: pick the first call-capable account so the call can proceed. */
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

    // ---- In-Call & Alert Notifications ----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        val manager = NotificationManagerCompat.from(this)
        val storeState = ActiveCallStore.state.value
        val primary = storeState.primary
        if (primary == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!manager.areNotificationsEnabled()) {
            CallStateLogger.log("NOTIFY", "Notifications disabled")
            return
        }
        ensureChannel()

        val callIntent = Intent(this, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openCallUi = PendingIntent.getActivity(
            this,
            0,
            callIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val ringing = primary.state == Call.STATE_RINGING
        val isWarning = storeState.session.state == SessionState.WAITING_FOR_CONFIRMATION

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(openCallUi)

        if (ringing) {
            builder.setContentTitle("Incoming call")
                .setContentText(primary.phoneNumber ?: "Unknown caller")
                .setFullScreenIntent(openCallUi, true)
                .addAction(
                    0,
                    "Answer",
                    serviceActionPendingIntent(ACTION_ANSWER, 101),
                )
                .addAction(
                    0,
                    "Decline",
                    serviceActionPendingIntent(ACTION_REJECT, 102),
                )
        } else if (isWarning) {
            val remain = storeState.session.confirmationRemainingSeconds
            builder.setContentTitle("⚠️ Are you still there? Call limit reached")
                .setContentText("Auto-disconnect in ${remain}s - tap I'm here to snooze")
                .setFullScreenIntent(openCallUi, true)
                .addAction(
                    0,
                    "I'm Here (Snooze)",
                    serviceActionPendingIntent(ACTION_SNOOZE, 103),
                )
                .addAction(
                    0,
                    "End Call",
                    serviceActionPendingIntent(ACTION_DISCONNECT, 104),
                )
        } else {
            val phone = primary.phoneNumber ?: "Active call"
            val text = when (storeState.session.state) {
                SessionState.SNOOZED -> "Snoozed (check in ${storeState.session.secondsUntilNextCheck}s)"
                else -> "State: ${CallStateNames.state(primary.state)}"
            }
            builder.setContentTitle(phone)
                .setContentText(text)
                .addAction(
                    0,
                    "End Call",
                    serviceActionPendingIntent(ACTION_DISCONNECT, 105),
                )
        }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            CallStateLogger.log("NOTIFY", "Notification blocked: ${e.message}")
        }
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, POCInCallService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CallGuard Calls & Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming calls and call duration alerts"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "poc_calls"
        const val NOTIFICATION_ID = 1001

        const val ACTION_ANSWER = "com.example.callguard.ACTION_ANSWER"
        const val ACTION_REJECT = "com.example.callguard.ACTION_REJECT"
        const val ACTION_SNOOZE = "com.example.callguard.ACTION_SNOOZE"
        const val ACTION_DISCONNECT = "com.example.callguard.ACTION_DISCONNECT"
    }
}
