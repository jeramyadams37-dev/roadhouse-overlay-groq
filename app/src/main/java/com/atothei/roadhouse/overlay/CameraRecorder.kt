package com.atothei.roadhouse.overlay

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared recording state for the hands-free camera. The voice handler
 * (VoiceCommandHandler) and the camera screen (CameraActivity) live in
 * different components; this object is how "stop recording" by voice
 * reaches a recording that the camera screen started. Nothing here
 * records anything itself — CameraActivity owns the CameraX session.
 *
 * Honest note: the system-camera intent path (ACTION_VIDEO_CAPTURE)
 * was NOT used, because it cannot start recording without a tap in
 * the camera app — it opens the camera and waits. CameraX starts
 * recording the moment the activity opens, with no taps, which is
 * what "record" promised.
 */
object CameraRecorder {

    const val EXTRA_MODE = "camera_mode"
    const val MODE_VIDEO = "video"
    const val MODE_PHOTO = "photo"
    const val MODE_REQUEST = "request"

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var currentClipName: String? = null
        private set

    /** Set by CameraActivity while it owns a recording; invoked by requestStop(). */
    @Volatile
    var onStopRequested: (() -> Unit)? = null

    fun markStarted(clipName: String) {
        isRecording = true
        currentClipName = clipName
    }

    fun markStopped() {
        isRecording = false
        currentClipName = null
        onStopRequested = null
    }

    /** Ask the live recording to stop. True when a recording was actually running to stop. */
    fun requestStop(): Boolean {
        if (!isRecording) return false
        onStopRequested?.invoke()
        return true
    }

    fun openVideo(context: Context) {
        val intent = Intent(context, CameraActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_VIDEO)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openPhoto(context: Context) {
        val intent = Intent(context, CameraActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_PHOTO)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openForPermission(context: Context) {
        val intent = Intent(context, CameraActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_REQUEST)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** "RoadHouse_20261124_023015" — date + time in every filename. */
    fun timestampedName(prefix: String, extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_$stamp.$extension"
    }
}
