package com.example.callguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles in-call audio recording using public Android MediaRecorder APIs.
 * Saved to app-specific external storage (no external storage permissions required).
 */
object CallRecorder {

    private var recorder: MediaRecorder? = null
    private var activeFile: File? = null
    private var startTimestamp = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Synchronized
    fun startRecording(context: Context, phoneNumber: String?): Boolean {
        if (_isRecording.value) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            CallStateLogger.log("RECORD", "Cannot start recording: RECORD_AUDIO permission missing")
            return false
        }

        try {
            val dir = context.getExternalFilesDir("CallRecordings") ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()

            val cleanNumber = (phoneNumber ?: "Call").replace(Regex("[^0-9a-zA-Z+]"), "_")
            val fileName = "Call_${cleanNumber}_${dateFormat.format(Date())}.m4a"
            val file = File(dir, fileName)

            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            // Prefer VOICE_COMMUNICATION for calls, fallback to MIC
            try {
                mr.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            } catch (e: Exception) {
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            }

            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128000)
            mr.setAudioSamplingRate(44100)
            mr.setOutputFile(file.absolutePath)

            mr.prepare()
            mr.start()

            recorder = mr
            activeFile = file
            startTimestamp = System.currentTimeMillis()

            _isRecording.value = true
            _currentFile.value = file

            CallStateLogger.log("RECORD", "Started recording call to: ${file.name}")
            return true
        } catch (e: Exception) {
            CallStateLogger.log("RECORD", "Failed to start recording: ${e.message}")
            stopRecording()
            return false
        }
    }

    @Synchronized
    fun stopRecording(): File? {
        if (!_isRecording.value && recorder == null) return null

        val saved = activeFile
        try {
            recorder?.stop()
        } catch (e: Exception) {
            CallStateLogger.log("RECORD", "Exception while stopping MediaRecorder: ${e.message}")
        } finally {
            try {
                recorder?.reset()
                recorder?.release()
            } catch (_: Exception) {}
            recorder = null
        }

        _isRecording.value = false
        _currentFile.value = null
        activeFile = null

        if (saved != null && saved.exists()) {
            CallStateLogger.log("RECORD", "Call recording saved (${saved.length()} bytes): ${saved.absolutePath}")
        }
        return saved
    }

    fun getRecordingDurationSeconds(): Long {
        if (!_isRecording.value) return 0L
        return ((System.currentTimeMillis() - startTimestamp) / 1000L).coerceAtLeast(0L)
    }
}
