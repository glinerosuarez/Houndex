package com.gls.dev.houndex.tflite

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Trace
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*

class TFLiteObjectDetectionAPIModel : Classifier {

    companion object {
        // Only return this many results.
        const val NUM_DETECTIONS = 10
        // Float model
        const val IMAGE_MEAN = 128.0f
        const val IMAGE_STD = 128.0f
        // Number of threads in the java app
        const val NUM_THREADS = 4

        const val TAG = "TFLiteODAPIModel"

        /** Memory-map the model file in Assets.  */
        @Throws(IOException::class)
        private fun loadModelFile(assets: AssetManager, modelFilename: String): MappedByteBuffer {
            val fileDescriptor = assets.openFd(modelFilename)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }

        /**
         * Initializes a native TensorFlow session for classifying images.
         *
         * @param assetManager The asset manager to be used to load assets.
         * @param modelFilename The filepath of the model GraphDef protocol buffer.
         * @param labelFilename The filepath of label file for classes.
         * @param inputSize The size of image input
         * @param isQuantized Boolean representing model is quantized or not
         */
        @Throws(IOException::class)
        fun create(
            assetManager: AssetManager,
            modelFilename: String,
            labelFilename: String,
            inputSize: Int,
            isQuantized: Boolean
        ): Classifier {
            val d = TFLiteObjectDetectionAPIModel()

            val actualFilename = labelFilename.split("file:///android_asset/")[1]
            Log.d(TAG, "actualFilename: $actualFilename")
            val labelsInput = assetManager.open(actualFilename)
            val br = BufferedReader(InputStreamReader(labelsInput))
            var line: String? = br.readLine()
            while (line != null) {
                Log.w(TAG, line)
                d.labels.add(line)
                line = br.readLine()
            }
            br.close()

            d.inputSize = inputSize

            try {
                Log.d(TAG, "modelFilename: $modelFilename")
                d.tfLite = Interpreter(loadModelFile(assetManager, modelFilename))
            } catch (e: Exception) {
                throw RuntimeException(e)
            }

            d.isModelQuantized = isQuantized
            // Pre-allocate buffers.
            val numBytesPerChannel: Int = if (isQuantized) 1 else 4 // Floating point

            //TODO: Remove exclamation marks
            d.imgData =
                ByteBuffer.allocateDirect(1 * d.inputSize * d.inputSize * 3 * numBytesPerChannel)
            d.imgData!!.order(ByteOrder.nativeOrder())
            d.intValues = IntArray(d.inputSize * d.inputSize)

            d.tfLite!!.setNumThreads(NUM_THREADS)
            d.outputLocations = Array(1) { Array(NUM_DETECTIONS) { FloatArray(4) } }
            d.outputClasses = Array(1) { FloatArray(NUM_DETECTIONS) }
            d.outputScores = Array(1) { FloatArray(NUM_DETECTIONS) }
            d.numDetections = FloatArray(1)
            return d
        }
    }

    private var isModelQuantized: Boolean = false
    // Config values.
    private var inputSize: Int = 0
    // Pre-allocated buffers.
    private val labels = Vector<String>()
    private var intValues: IntArray? = null
    // outputLocations: array of shape [Batchsize, NUM_DETECTIONS,4]
    // contains the location of detected boxes
    private var outputLocations: Array<Array<FloatArray>>? = null
    // outputClasses: array of shape [Batchsize, NUM_DETECTIONS]
    // contains the classes of detected boxes
    private var outputClasses: Array<FloatArray>? = null
    // outputScores: array of shape [Batchsize, NUM_DETECTIONS]
    // contains the scores of detected boxes
    private var outputScores: Array<FloatArray>? = null
    // numDetections: array of shape [Batchsize]
    // contains the number of detected boxes
    private var numDetections: FloatArray? = null

    private var imgData: ByteBuffer? = null

    private var tfLite: Interpreter? = null

    override fun recognizeImage(bitmap: Bitmap): List<Classifier.Recognition> {
        // Log this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage")

        Trace.beginSection("preprocessBitmap")
        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        //TODO: Remove exlamation marks
        imgData!!.rewind()
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                //TODO: Remove exclamation marks
                val pixelValue = intValues!![i * inputSize + j]
                if (isModelQuantized) {
                    // Quantized model
                    imgData!!.put((pixelValue shr 16 and 0xFF).toByte())
                    imgData!!.put((pixelValue shr 8 and 0xFF).toByte())
                    imgData!!.put((pixelValue and 0xFF).toByte())
                } else { // Float model
                    imgData!!.putFloat(((pixelValue shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                    imgData!!.putFloat(((pixelValue shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                    imgData!!.putFloat(((pixelValue and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                }
            }
        }
        Trace.endSection() // preprocessBitmap

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed")
        outputLocations = Array(1) { Array(NUM_DETECTIONS) { FloatArray(4) } }
        outputClasses = Array(1) { FloatArray(NUM_DETECTIONS) }
        outputScores = Array(1) { FloatArray(NUM_DETECTIONS) }
        numDetections = FloatArray(1)

        val inputArray = arrayOf(imgData as Any)
        val outputMap = mapOf(
            0 to outputLocations as Any,
            1 to outputClasses as Any,
            2 to outputScores as Any,
            3 to numDetections as Any
        )

        Trace.endSection()

        // Run the inference call.
        Trace.beginSection("run")
        //TODO: Remove exclamation marks
        tfLite!!.runForMultipleInputsOutputs(inputArray, outputMap)
        Trace.endSection()

        // Show the best detections.
        // after scaling them back to the input size.
        val recognitions = ArrayList<Classifier.Recognition>(NUM_DETECTIONS)
        for (i in 0 until NUM_DETECTIONS) {
            //todo: Remove
            val detection = RectF(
                outputLocations!![0][i][1] * inputSize,
                outputLocations!![0][i][0] * inputSize,
                outputLocations!![0][i][3] * inputSize,
                outputLocations!![0][i][2] * inputSize
            )
            // SSD Mobilenet V1 Model assumes class 0 is background class
            // in label file and class labels start from 1 to number_of_classes+1,
            // while outputClasses correspond to class index from 0 to number_of_classes
            val labelOffset = 1
            recognitions.add(
                Classifier.Recognition(
                    "" + i,
                    //TODO: Remove
                    labels[outputClasses!![0][i].toInt() + labelOffset],
                    outputScores!![0][i],
                    detection
                )
            )
        }
        Trace.endSection() // "recognizeImage"
        return recognitions
    }

    override fun enableStatLogging(debug: Boolean) {}

    override val statString: String get() = ""

    override fun close() {}

    override fun setNumThreads(num_threads: Int) {
        //todo: deprecation
        if (tfLite != null) tfLite!!.setNumThreads(num_threads)
    }

    override fun setUseNNAPI(isChecked: Boolean) {
        //todo: deprecation
        if (tfLite != null) tfLite!!.setUseNNAPI(isChecked)
    }

}