package com.atothei.roadhouse.overlay

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hands-free camera screen (CameraX). Opened by voice — "record" /
 * "start recording" starts video right away with NO taps, rear camera
 * by default; "take a photo" snaps a still. Clips save to
 * Movies/RoadHouse and photos to DCIM/RoadHouse with a timestamped
 * filename ("RoadHouse_20261124_023015.mp4"), everything on the phone,
 * nothing uploaded anywhere.
 *
 * Recording indicator: a real visible element tied to the actual
 * recording state — a red "● REC 00:00" badge plus a Stop button,
 * shown only while VideoRecordEvent says a recording is running.
 * "Stop recording" works by voice (CameraRecorder.requestStop reaches
 * this activity) AND by tapping the Stop button. Recording never runs
 * in the background: it lives and dies with this visible activity.
 *
 * As a dashcam it records what the phone sees — mount the phone
 * facing forward. Keep-screen-on is set while recording.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var recBadge: TextView
    private lateinit var stopButton: Button

    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var recording: Recording? = null
    private var cameraBound = false
    private var mode: String = CameraRecorder.MODE_VIDEO
    private var clipName: String = ""
    private var recordStartMs: Long = 0L

    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (!CameraRecorder.isRecording) return
            val elapsed = (System.currentTimeMillis() - recordStartMs) / 1000
            val mm = elapsed / 60
            val ss = elapsed % 60
            recBadge.text = "● REC %02d:%02d".format(mm, ss)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        previewView = findViewById(R.id.camera_preview)
        recBadge = findViewById(R.id.text_rec)
        stopButton = findViewById(R.id.btn_stop_recording)
        stopButton.setOnClickListener { stopRecording() }
        mode = intent.getStringExtra(CameraRecorder.EXTRA_MODE) ?: CameraRecorder.MODE_VIDEO
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_CODE
            )
            return
        }
        if (mode == CameraRecorder.MODE_REQUEST) {
            Toast.makeText(this, "Camera permission is on — say \"record\" to start.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startCamera()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        if (!hasCameraPermission()) {
            Toast.makeText(
                this,
                "Camera permission denied — nothing was recorded.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        if (mode == CameraRecorder.MODE_REQUEST) {
            Toast.makeText(this, "Camera permission is on — say \"record\" to start.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        startCamera()
    }

    private fun startCamera() {
        if (cameraBound) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture,
                    imageCapture
                )
                cameraBound = true
                when (mode) {
                    CameraRecorder.MODE_PHOTO -> takePhoto()
                    else -> startRecording()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open the camera: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val capture = videoCapture ?: run {
            Toast.makeText(this, "Camera isn't ready yet — try again.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        clipName = CameraRecorder.timestampedName("RoadHouse", "mp4")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, clipName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RoadHouse")
            }
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
        val pending = capture.output.prepareRecording(this, output)
        val withAudio = if (hasAudioPermission()) {
            try {
                pending.withAudioEnabled()
            } catch (e: Exception) {
                pending
            }
        } else {
            pending
        }
        recording = withAudio.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    recordStartMs = System.currentTimeMillis()
                    CameraRecorder.markStarted(clipName)
                    CameraRecorder.onStopRequested = { stopRecording() }
                    recBadge.visibility = View.VISIBLE
                    stopButton.visibility = View.VISIBLE
                    recBadge.text = "● REC 00:00"
                    timerHandler.post(timerTick)
                    Toast.makeText(this, "Recording — $clipName", Toast.LENGTH_SHORT).show()
                }
                is VideoRecordEvent.Finalize -> {
                    timerHandler.removeCallbacks(timerTick)
                    recBadge.visibility = View.GONE
                    stopButton.visibility = View.GONE
                    CameraRecorder.markStopped()
                    recording = null
                    if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                        Toast.makeText(this, "Saved: $clipName", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Recording didn't save (error ${event.error}).", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            }
        }
    }

    /** Stop from the button or from voice ("stop recording"). Safe when nothing is recording. */
    private fun stopRecording() {
        val rec = recording
        if (rec == null) {
            if (CameraRecorder.isRecording) CameraRecorder.markStopped()
            finish()
            return
        }
        try {
            rec.stop()
        } catch (e: Exception) {
            CameraRecorder.markStopped()
            finish()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera isn't ready yet — try again.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val photoName = CameraRecorder.timestampedName("RoadHouse", "jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photoName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RoadHouse")
            }
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@CameraActivity, "Photo saved: $photoName", Toast.LENGTH_LONG).show()
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@CameraActivity,
                        "Photo didn't save: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerTick)
        try {
            recording?.stop()
        } catch (e: Exception) {
        }
        CameraRecorder.onStopRequested = null
        if (CameraRecorder.isRecording) CameraRecorder.markStopped()
    }

    companion object {
        private const val REQUEST_CODE = 40
    }
}
