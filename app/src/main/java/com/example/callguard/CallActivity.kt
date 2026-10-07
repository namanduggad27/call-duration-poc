package com.example.callguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Incoming and Ongoing Call Screen:
 * - Shows contact name (synced from Google/Device contacts) and phone number.
 * - In-Call Features: Speakerphone toggle, DTMF Keypad, Call Audio Recording.
 * - Shows incoming call UI with Answer / Decline buttons.
 * - Shows active call UI with duration timer, live call limit status, and End Call button.
 * - Shows urgent Presence Confirmation alert when threshold is reached with Snooze & Auto-disconnect.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var tvCallStatusChip: TextView
    private lateinit var tvDirection: TextView
    private lateinit var ivAvatar: ImageView
    private lateinit var tvCallerName: TextView
    private lateinit var tvCallerNumber: TextView
    private lateinit var tvCallDuration: TextView
    private lateinit var tvRecordingBadge: TextView
    private lateinit var ivBackgroundPhoto: ImageView
    private lateinit var viewPhotoScrim: View

    private lateinit var cardPresenceAlert: LinearLayout
    private lateinit var tvPresenceCountdown: TextView
    private lateinit var btnSnooze: Button
    private lateinit var btnEndFromAlert: Button

    private lateinit var cardNextCheck: LinearLayout
    private lateinit var tvNextCheckPolicy: TextView
    private lateinit var tvNextCheckCountdown: TextView

    private lateinit var layoutIncomingActions: LinearLayout
    private lateinit var btnIncomingAnswer: ImageButton
    private lateinit var btnIncomingReject: ImageButton

    // In-call action buttons
    private lateinit var layoutOngoingActions: LinearLayout
    private lateinit var btnSpeaker: ImageButton
    private lateinit var tvSpeakerLabel: TextView
    private lateinit var btnInCallKeypad: ImageButton
    private lateinit var tvKeypadLabel: TextView
    private lateinit var btnRecord: ImageButton
    private lateinit var tvRecordLabel: TextView
    private lateinit var btnOngoingEndCall: ImageButton

    // In-call dialpad overlay
    private lateinit var layoutInCallDialpad: LinearLayout
    private lateinit var btnHideInCallDialpad: Button
    private lateinit var tvDtmfDisplay: TextView

    // POC diagnostics section
    private lateinit var tvTogglePocSection: TextView
    private lateinit var layoutPocSection: LinearLayout
    private lateinit var btnPocDisconnect: Button
    private lateinit var tvPocResult: TextView
    private lateinit var btnPocRemoteYes: Button
    private lateinit var btnPocRemoteNo: Button

    private var pocSectionExpanded = false
    private val dtmfSequence = StringBuilder()

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val number = ActiveCallStore.state.value.primary?.phoneNumber
                CallRecorder.startRecording(this, number)
                Toast.makeText(this, "Call recording started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Microphone permission required to record calls", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_call)

        bindViews()
        setupClicks()
        setupDtmfDialpad()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ActiveCallStore.state.collect { render(it) }
                }
                launch {
                    ActiveCallStore.isSpeakerOn.collect { renderSpeakerState(it) }
                }
                launch {
                    CallRecorder.isRecording.collect { renderRecordingState(it) }
                }
                launch {
                    while (true) {
                        renderTimers(ActiveCallStore.state.value)
                        delay(1000L)
                    }
                }
            }
        }
    }

    private fun bindViews() {
        tvCallStatusChip = findViewById(R.id.tvCallStatusChip)
        tvDirection = findViewById(R.id.tvDirection)
        ivAvatar = findViewById(R.id.ivAvatar)
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallerNumber = findViewById(R.id.tvCallerNumber)
        tvCallDuration = findViewById(R.id.tvCallDuration)
        tvRecordingBadge = findViewById(R.id.tvRecordingBadge)
        ivBackgroundPhoto = findViewById(R.id.ivBackgroundPhoto)
        viewPhotoScrim = findViewById(R.id.viewPhotoScrim)

        cardPresenceAlert = findViewById(R.id.cardPresenceAlert)
        tvPresenceCountdown = findViewById(R.id.tvPresenceCountdown)
        btnSnooze = findViewById(R.id.btnSnooze)
        btnEndFromAlert = findViewById(R.id.btnEndFromAlert)

        cardNextCheck = findViewById(R.id.cardNextCheck)
        tvNextCheckPolicy = findViewById(R.id.tvNextCheckPolicy)
        tvNextCheckCountdown = findViewById(R.id.tvNextCheckCountdown)

        layoutIncomingActions = findViewById(R.id.layoutIncomingActions)
        btnIncomingAnswer = findViewById(R.id.btnIncomingAnswer)
        btnIncomingReject = findViewById(R.id.btnIncomingReject)

        layoutOngoingActions = findViewById(R.id.layoutOngoingActions)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        tvSpeakerLabel = findViewById(R.id.tvSpeakerLabel)
        btnInCallKeypad = findViewById(R.id.btnInCallKeypad)
        tvKeypadLabel = findViewById(R.id.tvKeypadLabel)
        btnRecord = findViewById(R.id.btnRecord)
        tvRecordLabel = findViewById(R.id.tvRecordLabel)
        btnOngoingEndCall = findViewById(R.id.btnOngoingEndCall)

        layoutInCallDialpad = findViewById(R.id.layoutInCallDialpad)
        btnHideInCallDialpad = findViewById(R.id.btnHideInCallDialpad)
        tvDtmfDisplay = findViewById(R.id.tvDtmfDisplay)

        tvTogglePocSection = findViewById(R.id.tvTogglePocSection)
        layoutPocSection = findViewById(R.id.layoutPocSection)
        btnPocDisconnect = findViewById(R.id.btnPocDisconnect)
        tvPocResult = findViewById(R.id.tvPocResult)
        btnPocRemoteYes = findViewById(R.id.btnPocRemoteYes)
        btnPocRemoteNo = findViewById(R.id.btnPocRemoteNo)
    }

    private fun setupClicks() {
        btnIncomingAnswer.setOnClickListener {
            ActiveCallStore.answer()
        }
        btnIncomingReject.setOnClickListener {
            ActiveCallStore.reject()
        }
        btnOngoingEndCall.setOnClickListener {
            CallRecorder.stopRecording()
            ActiveCallStore.disconnectTest()
        }
        btnSnooze.setOnClickListener {
            ActiveCallStore.snooze()
        }
        btnEndFromAlert.setOnClickListener {
            CallRecorder.stopRecording()
            ActiveCallStore.disconnectTest()
        }

        // Speaker Toggle
        btnSpeaker.setOnClickListener {
            ActiveCallStore.toggleSpeaker()
        }

        // In-Call Keypad Toggle
        btnInCallKeypad.setOnClickListener {
            val showing = layoutInCallDialpad.visibility == View.VISIBLE
            layoutInCallDialpad.visibility = if (showing) View.GONE else View.VISIBLE
        }
        btnHideInCallDialpad.setOnClickListener {
            layoutInCallDialpad.visibility = View.GONE
        }

        // Call Recording Toggle
        btnRecord.setOnClickListener {
            toggleCallRecording()
        }

        tvTogglePocSection.setOnClickListener {
            pocSectionExpanded = !pocSectionExpanded
            layoutPocSection.visibility = if (pocSectionExpanded) View.VISIBLE else View.GONE
            tvTogglePocSection.text = if (pocSectionExpanded) "POC Verification Controls ▲" else "POC Verification Controls ▼"
        }

        btnPocDisconnect.setOnClickListener {
            CallRecorder.stopRecording()
            ActiveCallStore.disconnectTest()
        }
        btnPocRemoteYes.setOnClickListener {
            ActiveCallStore.setRemoteResult(true)
        }
        btnPocRemoteNo.setOnClickListener {
            ActiveCallStore.setRemoteResult(false)
        }
    }

    private fun setupDtmfDialpad() {
        fun sendDtmf(c: Char) {
            ActiveCallStore.playDtmf(c)
            dtmfSequence.append(c)
            tvDtmfDisplay.text = dtmfSequence.toString()
        }

        findViewById<Button>(R.id.btnDtmf1).setOnClickListener { sendDtmf('1') }
        findViewById<Button>(R.id.btnDtmf2).setOnClickListener { sendDtmf('2') }
        findViewById<Button>(R.id.btnDtmf3).setOnClickListener { sendDtmf('3') }
        findViewById<Button>(R.id.btnDtmf4).setOnClickListener { sendDtmf('4') }
        findViewById<Button>(R.id.btnDtmf5).setOnClickListener { sendDtmf('5') }
        findViewById<Button>(R.id.btnDtmf6).setOnClickListener { sendDtmf('6') }
        findViewById<Button>(R.id.btnDtmf7).setOnClickListener { sendDtmf('7') }
        findViewById<Button>(R.id.btnDtmf8).setOnClickListener { sendDtmf('8') }
        findViewById<Button>(R.id.btnDtmf9).setOnClickListener { sendDtmf('9') }
        findViewById<Button>(R.id.btnDtmfStar).setOnClickListener { sendDtmf('*') }
        findViewById<Button>(R.id.btnDtmf0).setOnClickListener { sendDtmf('0') }
        findViewById<Button>(R.id.btnDtmfHash).setOnClickListener { sendDtmf('#') }
    }

    private fun toggleCallRecording() {
        if (CallRecorder.isRecording.value) {
            val file = CallRecorder.stopRecording()
            Toast.makeText(this, "Recording saved: ${file?.name ?: ""}", Toast.LENGTH_SHORT).show()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                val number = ActiveCallStore.state.value.primary?.phoneNumber
                val ok = CallRecorder.startRecording(this, number)
                if (ok) {
                    Toast.makeText(this, "Call recording started", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Unable to start audio recording", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderSpeakerState(isSpeaker: Boolean) {
        if (isSpeaker) {
            btnSpeaker.setBackgroundResource(R.drawable.bg_in_call_action_active)
            tvSpeakerLabel.setTextColor(Color.parseColor("#58A6FF"))
        } else {
            btnSpeaker.setBackgroundResource(R.drawable.bg_in_call_action)
            tvSpeakerLabel.setTextColor(Color.parseColor("#8B949E"))
        }
    }

    private fun renderRecordingState(isRecording: Boolean) {
        if (isRecording) {
            btnRecord.setBackgroundResource(R.drawable.bg_in_call_action_record_active)
            tvRecordLabel.setTextColor(Color.parseColor("#F85149"))
            tvRecordLabel.text = "Recording"
            tvRecordingBadge.visibility = View.VISIBLE
        } else {
            btnRecord.setBackgroundResource(R.drawable.bg_in_call_action)
            tvRecordLabel.setTextColor(Color.parseColor("#8B949E"))
            tvRecordLabel.text = "Record"
            tvRecordingBadge.visibility = View.GONE
        }
    }

    private fun render(storeState: StoreState) {
        val primary = storeState.primary
        if (primary == null) {
            tvCallStatusChip.text = "CALL ENDED"
            tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
            tvDirection.text = "Call dropped or ended"
            ivBackgroundPhoto.visibility = View.GONE
            viewPhotoScrim.visibility = View.GONE
            resetAvatarToDefault()
            layoutIncomingActions.visibility = View.GONE
            layoutOngoingActions.visibility = View.GONE
            layoutInCallDialpad.visibility = View.GONE
            cardPresenceAlert.visibility = View.GONE
            cardNextCheck.visibility = View.GONE
            lifecycleScope.launch {
                delay(1500L)
                if (ActiveCallStore.state.value.primary == null) {
                    finish()
                }
            }
            return
        }

        val phone = primary.phoneNumber ?: "Unknown"
        val contact = ContactHelper.getContact(this, phone)

        if (contact != null) {
            tvCallerName.text = contact.name
            tvCallerNumber.text = phone
            tvCallerNumber.visibility = View.VISIBLE
            if (!contact.photoUri.isNullOrBlank()) {
                try {
                    val photoUri = Uri.parse(contact.photoUri)
                    ivBackgroundPhoto.setImageURI(photoUri)
                    ivBackgroundPhoto.visibility = View.VISIBLE
                    viewPhotoScrim.visibility = View.VISIBLE

                    ivAvatar.setImageURI(photoUri)
                    ivAvatar.clipToOutline = true
                    ivAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                    ivAvatar.setPadding(0, 0, 0, 0)
                } catch (_: Exception) {
                    ivBackgroundPhoto.visibility = View.GONE
                    viewPhotoScrim.visibility = View.GONE
                    resetAvatarToDefault()
                }
            } else {
                ivBackgroundPhoto.visibility = View.GONE
                viewPhotoScrim.visibility = View.GONE
                resetAvatarToDefault()
            }
        } else {
            tvCallerName.text = phone
            tvCallerNumber.visibility = View.GONE
            ivBackgroundPhoto.visibility = View.GONE
            viewPhotoScrim.visibility = View.GONE
            resetAvatarToDefault()
        }

        tvDirection.text = "${CallStateNames.direction(primary.direction)} • Cellular"

        val ringing = primary.state == Call.STATE_RINGING
        if (ringing) {
            tvCallStatusChip.text = "INCOMING CALL"
            tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
            layoutIncomingActions.visibility = View.VISIBLE
            layoutOngoingActions.visibility = View.GONE
            layoutInCallDialpad.visibility = View.GONE
            cardPresenceAlert.visibility = View.GONE
            cardNextCheck.visibility = View.GONE
        } else {
            layoutIncomingActions.visibility = View.GONE
            layoutOngoingActions.visibility = View.VISIBLE

            val session = storeState.session
            when (session.state) {
                SessionState.WAITING_FOR_CONFIRMATION -> {
                    tvCallStatusChip.text = "⚠️ CALL LIMIT REACHED"
                    tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip_selected)
                    cardPresenceAlert.visibility = View.VISIBLE
                    cardNextCheck.visibility = View.GONE

                    val policy = CallPolicyRepository.getPolicy()
                    val snoozeText = if (policy.snoozeDurationSeconds >= 60) {
                        "${policy.snoozeDurationSeconds / 60}m"
                    } else {
                        "${policy.snoozeDurationSeconds}s"
                    }
                    btnSnooze.text = "I'm Here (Snooze +$snoozeText)"
                    tvPresenceCountdown.text = "Auto-disconnect in ${session.confirmationRemainingSeconds}s"
                }
                SessionState.SNOOZED -> {
                    tvCallStatusChip.text = "SNOOZED (CHECK #${session.snoozeCount})"
                    tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
                    cardPresenceAlert.visibility = View.GONE
                    cardNextCheck.visibility = View.VISIBLE
                }
                else -> {
                    tvCallStatusChip.text = CallStateNames.state(primary.state)
                    tvCallStatusChip.setBackgroundResource(R.drawable.bg_chip)
                    cardPresenceAlert.visibility = View.GONE
                    cardNextCheck.visibility = if (primary.state == Call.STATE_ACTIVE) View.VISIBLE else View.GONE
                }
            }
        }

        renderPocResults(storeState)
    }

    private fun renderTimers(storeState: StoreState) {
        val primary = storeState.primary ?: return
        val since = primary.activeSinceElapsedMs
        if (since != null) {
            val elapsedSec = ((SystemClock.elapsedRealtime() - since) / 1000L).coerceAtLeast(0)
            tvCallDuration.text = "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60)
        } else {
            tvCallDuration.text = "00:00"
        }

        if (CallRecorder.isRecording.value) {
            val recSec = CallRecorder.getRecordingDurationSeconds()
            tvRecordingBadge.text = "🔴 REC %02d:%02d".format(recSec / 60, recSec % 60)
        }

        val policy = CallPolicyRepository.getPolicy()
        val limitStr = formatSeconds(policy.maxDurationSeconds)
        val snoozeStr = formatSeconds(policy.snoozeDurationSeconds)
        tvNextCheckPolicy.text = "Limit: $limitStr | Snooze: $snoozeStr"

        val remainSec = storeState.session.secondsUntilNextCheck
        tvNextCheckCountdown.text = "Next presence check in: %02d:%02d".format(remainSec / 60, remainSec % 60)
    }

    private fun renderPocResults(storeState: StoreState) {
        val records = storeState.records
        if (records.isEmpty()) {
            tvPocResult.text = "No disconnect test run yet."
            btnPocRemoteYes.isEnabled = false
            btnPocRemoteNo.isEnabled = false
        } else {
            val last = records.last()
            val telecom = when (last.outcome) {
                DisconnectOutcome.PENDING -> "waiting..."
                DisconnectOutcome.TELECOM_CONFIRMED ->
                    "confirmed in ${(last.endedAtElapsedMs ?: last.requestedAtElapsedMs) - last.requestedAtElapsedMs} ms"
                DisconnectOutcome.NO_CONFIRMATION -> "NOT confirmed"
            }
            val remote = when (last.remoteConfirmed) {
                null -> "awaiting check"
                true -> "yes, ended"
                false -> "NO, still on"
            }
            tvPocResult.text = "Latest Test: Telecom $telecom | Remote: $remote => ${verdictOf(last)}"
            btnPocRemoteYes.isEnabled = true
            btnPocRemoteNo.isEnabled = true
        }
    }

    private fun formatSeconds(seconds: Long): String {
        return if (seconds >= 60) {
            if (seconds % 60 == 0L) "${seconds / 60}m" else "${seconds / 60}m ${seconds % 60}s"
        } else {
            "${seconds}s"
        }
    }

    private fun resetAvatarToDefault() {
        ivAvatar.setImageResource(R.drawable.ic_call)
        ivAvatar.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (18 * resources.displayMetrics.density).toInt()
        ivAvatar.setPadding(pad, pad, pad, pad)
    }
}
