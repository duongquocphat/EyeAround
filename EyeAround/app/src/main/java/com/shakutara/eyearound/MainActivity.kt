package com.shakutara.eyearound

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.label.FirebaseVisionImageLabeler
import com.google.firebase.ml.vision.label.FirebaseVisionOnDeviceImageLabelerOptions
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), LifecycleOwner {

    private val TAG = MainActivity::class.java.simpleName
    private val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS =
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    private val labeler: FirebaseVisionImageLabeler
        get() {
            val options = FirebaseVisionOnDeviceImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.7f)
                .build()
            return FirebaseVision.getInstance().getOnDeviceImageLabeler(options)
        }
    private lateinit var imageCapture: ImageCapture
    private var textToSpeech: TextToSpeech? = null
    private val timer by lazy {
        Timer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // init
        textToSpeech = TextToSpeech(applicationContext,
            TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.US
                }
            })
        viewFinder = findViewById(R.id.view_finder)

        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post {
                startCamera()
            }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

        /***
         *
         */
        // Create configuration object for the image capture use case
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                // We don't set a resolution for image capture; instead, we
                // select a capture mode which will infer the appropriate
                // resolution based on aspect ration and requested mode
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)
            }.build()
        imageCapture = ImageCapture(imageCaptureConfig)
        findViewById<ImageButton>(R.id.capture_button).setOnClickListener {
            val file = File(
                externalMediaDirs.first(),
                "${System.currentTimeMillis()}.jpg"
            )

            imageCapture.takePicture(file, executor,
                object : ImageCapture.OnImageSavedListener {
                    override fun onError(
                        imageCaptureError: ImageCapture.ImageCaptureError,
                        message: String,
                        exc: Throwable?
                    ) {
                        val msg = "Photo capture failed: $message"
                        Log.e("CameraXApp", msg, exc)
                        viewFinder.post {
                            textToSpeech?.speak(
                                "Photo capture failed",
                                TextToSpeech.QUEUE_ADD,
                                null,
                                null
                            )
                        }
                    }

                    override fun onImageSaved(file: File) {
                        val msg = "Photo capture succeeded: ${file.absolutePath}"
                        Log.d("CameraXApp", msg)

                        viewFinder.post {
                            val image: FirebaseVisionImage
                            try {
                                image = FirebaseVisionImage.fromFilePath(
                                    this@MainActivity.applicationContext,
                                    Uri.fromFile(file)
                                )
                                handleLabelImage(image)
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                    }
                })
        }

        // views listener
        switchAuto.setOnCheckedChangeListener { compoundButton, checked ->
            if (checked) {
                capture_button.visibility = View.GONE
                timer.scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        runOnUiThread {
                            findViewById<ImageButton>(R.id.capture_button).performClick()
                        }
                    }
                }, 5_000L, 5_000L)
            } else {
                capture_button.visibility = View.VISIBLE
                timer.cancel()
                timer.purge()
            }
        }
    }

    private fun checkMediaVolume() {
        val audioManager: AudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVolume == 0) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.75F).toInt(),
                0
            )
        }
    }

    override fun onPause() {
        if (textToSpeech != null) {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
        super.onPause()
    }

    private fun handleLabelImage(image: FirebaseVisionImage) {
        vibrate()
        labeler.processImage(image)
            .addOnSuccessListener { labels ->
                checkMediaVolume()
                for (label in labels) {
                    val text = label.text
                    val entityId = label.entityId
                    val confidence = label.confidence
                    Log.d(TAG, "text: $text, entityId: $entityId, confident: $confidence")

                    Handler().postDelayed({
                        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
                    }, 1_000L)
                }
            }
            .addOnFailureListener {

            }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var viewFinder: TextureView

    private fun startCamera() {

        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            setTargetResolution(Size(viewFinder.width, viewFinder.height))
        }.build()

        // Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {

            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        // Setup image analysis pipeline that computes average pixel luminance
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE
            )
        }.build()

        // Build the image analysis use case and instantiate our analyzer
        val analyzerUseCase = ImageAnalysis(analyzerConfig).apply {
            setAnalyzer(executor, LuminosityAnalyzer())
        }

        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(this, preview, imageCapture, analyzerUseCase)
    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when (viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(
                VibrationEffect.createOneShot(
                    100,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(100)
        }
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                textToSpeech?.speak("Permissions not granted", TextToSpeech.QUEUE_ADD, null, null)
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private class LuminosityAnalyzer : ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L

        /**
         * Helper extension function used to extract a byte array from an
         * image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        override fun analyze(image: ImageProxy, rotationDegrees: Int) {
            val currentTimestamp = System.currentTimeMillis()
            // Calculate the average luma no more often than every second
            if (currentTimestamp - lastAnalyzedTimestamp >=
                TimeUnit.SECONDS.toMillis(1)
            ) {
                // Since format in ImageAnalysis is YUV, image.planes[0]
                // contains the Y (luminance) plane
                val buffer = image.planes[0].buffer
                // Extract image data from callback object
                val data = buffer.toByteArray()
                // Convert the data into an array of pixel values
                val pixels = data.map { it.toInt() and 0xFF }
                // Compute average luminance for the image
                val luma = pixels.average()
                // Log the new luma value
                Log.d("CameraXApp", "Average luminosity: $luma")
                // Update timestamp of last analyzed frame
                lastAnalyzedTimestamp = currentTimestamp
            }
        }
    }
}
