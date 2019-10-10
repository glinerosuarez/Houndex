package com.gls.dev.houndex

import android.graphics.*
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.widget.Toast
import com.gls.dev.houndex.customview.OverlayView
import com.gls.dev.houndex.env.BorderedText
import com.gls.dev.houndex.env.ImageUtils
import com.gls.dev.houndex.env.save
import com.gls.dev.houndex.tflite.Classifier
import com.gls.dev.houndex.tflite.TFLiteObjectDetectionAPIModel
import com.gls.dev.houndex.tracking.MultiBoxTracker
import java.io.IOException
import java.util.*

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
open class DetectorActivity : CameraActivity(), ImageReader.OnImageAvailableListener {

    companion object {
        private const val TAG = "DetectorActivity"

        // Configuration values for the prepackaged SSD model.
        private const val TF_OD_API_INPUT_SIZE = 300
        private const val TF_OD_API_IS_QUANTIZED = true
        private const val TF_OD_API_MODEL_FILE = "detect.tflite"
        private const val TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt"
        private val MODE = DetectorMode.TF_OD_API
        // Minimum detection confidence to track a detection.
        private const val MINIMUM_CONFIDENCE_TF_OD_API = 0.5f
        private const val MAINTAIN_ASPECT = false
        private val DESIRED_PREVIEW_SIZE = Size(640, 480)
        private const val SAVE_PREVIEW_BITMAP = false
        private const val TEXT_SIZE_DIP = 10f
    }

    private lateinit var trackingOverlay: OverlayView
    private var sensorOrientation: Int? = null

    private var detector: Classifier? = null

    private var lastProcessingTimeMs: Long = 0
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    private var cropCopyBitmap: Bitmap? = null

    private var computingDetection = false

    private var timestamp: Long = 0

    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null

    private var tracker: MultiBoxTracker? = null

    private var borderedText: BorderedText? = null

    override val layoutId: Int = R.layout.camera_connection_fragment_tracking

    override val desiredPreviewFrameSize: Size = DESIRED_PREVIEW_SIZE

    override fun onPreviewSizeChosen(size: Size, rotation: Int) {
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics
        )
        borderedText = BorderedText(textSizePx)
        borderedText!!.setTypeface(Typeface.MONOSPACE)

        tracker = MultiBoxTracker(this)

        var cropSize = TF_OD_API_INPUT_SIZE

        try {
            detector = TFLiteObjectDetectionAPIModel.create(
                assets,
                TF_OD_API_MODEL_FILE,
                TF_OD_API_LABELS_FILE,
                TF_OD_API_INPUT_SIZE,
                TF_OD_API_IS_QUANTIZED
            )
            cropSize = TF_OD_API_INPUT_SIZE
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e(TAG, "Exception initializing classifier!", e)
            val toast = Toast.makeText(
                applicationContext, "Classifier could not be initialized", Toast.LENGTH_SHORT
            )
            toast.show()
            finish()
        }

        previewWidth = size.width
        previewHeight = size.height

        sensorOrientation = rotation - screenOrientation
        Log.i(TAG, "Camera orientation relative to screen canvas: $sensorOrientation")

        Log.i(TAG, "Initializing at size ${previewWidth}x$previewHeight")
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)

        frameToCropTransform = ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation!!, MAINTAIN_ASPECT
        )

        cropToFrameTransform = Matrix()
        frameToCropTransform!!.invert(cropToFrameTransform)

        trackingOverlay = findViewById(R.id.tracking_overlay)
        trackingOverlay.addCallback(
            object : OverlayView.DrawCallback {
                override fun drawCallback(canvas: Canvas) {
                    tracker!!.draw(canvas)
                    if (isDebug) {
                        tracker!!.drawDebug(canvas)
                    }
                }
            })

        tracker!!.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation!!)
    }

    override fun processImage() {
        ++timestamp
        val currTimestamp = timestamp
        trackingOverlay.postInvalidate()

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage()
            return
        }
        computingDetection = true
        Log.i(TAG, "Preparing image $currTimestamp for detection in bg thread.")

        rgbFrameBitmap!!.setPixels(
            getRgbBytes(),
            0,
            previewWidth,
            0,
            0,
            previewWidth,
            previewHeight
        )

        readyForNextImage()

        val canvas = Canvas(croppedBitmap!!)
        canvas.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            croppedBitmap?.apply { save() }
        }

        runInBackground(
            Runnable {
                Log.i(TAG, "Running detection on image $currTimestamp")
                val startTime = SystemClock.uptimeMillis()
                val results = detector!!.recognizeImage(croppedBitmap!!)
                lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime

                cropCopyBitmap = Bitmap.createBitmap(croppedBitmap!!)
                val canvas = Canvas(cropCopyBitmap!!)
                val paint = Paint()
                paint.color = Color.RED
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.0f

                var minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API
                when (MODE) {
                    DetectorMode.TF_OD_API -> minimumConfidence =
                        MINIMUM_CONFIDENCE_TF_OD_API
                }

                val mappedRecognitions = LinkedList<Classifier.Recognition>()

                for (result in results) {
                    val location = result.getLocation()
                    if (location != null && result.confidence!! >= minimumConfidence) {
                        canvas.drawRect(location, paint)

                        cropToFrameTransform!!.mapRect(location)

                        result.setLocation(location)
                        mappedRecognitions.add(result)
                    }
                }

                tracker!!.trackResults(mappedRecognitions, currTimestamp)
                trackingOverlay.postInvalidate()

                computingDetection = false

                runOnUiThread {
                    showFrameInfo("$previewWidth x $previewHeight")
                    showCropInfo("${cropCopyBitmap?.width}x${cropCopyBitmap?.height}")
                    showInference("$lastProcessingTimeMs ms")
                }
            })
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum class DetectorMode {
        TF_OD_API
    }

    override fun setUseNNAPI(isChecked: Boolean) {
        runInBackground(Runnable { detector!!.setUseNNAPI(isChecked) })
    }

    override fun setNumThreads(numThreads: Int) {
        runInBackground(Runnable { detector!!.setNumThreads(numThreads) })
    }


}