package com.gls.dev.houndex

/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import androidx.fragment.app.Fragment
import com.gls.dev.houndex.customview.AutoFitTextureView
import com.gls.dev.houndex.env.ImageUtils
import java.io.IOException
import kotlin.properties.Delegates.notNull

class LegacyCameraConnectionFragment() : Fragment() {

    companion object{
        const val TAG = "LegacyCamConnFrag"
    }

    /** Conversion from screen rotation to JPEG orientation.  */
    private val orientations = SparseIntArray()

    init {
        orientations.append(Surface.ROTATION_0, 90)
        orientations.append(Surface.ROTATION_90, 0)
        orientations.append(Surface.ROTATION_180, 270)
        orientations.append(Surface.ROTATION_270, 180)
    }

    private var camera: Camera? = null
    private lateinit var imageListener: Camera.PreviewCallback
    private lateinit var desiredSize: Size

    /** The layout identifier to inflate for this Fragment.  */
    private var layout by notNull<Int>()
    /** An [AutoFitTextureView] for camera preview.  */
    private var textureView: AutoFitTextureView? = null
    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [ ].
     */
    private val surfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                texture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                val index = getCameraId()
                camera = Camera.open(index)

                try {
                    val parameters = camera!!.getParameters()
                    val focusModes = parameters.supportedFocusModes
                    if (
                        focusModes != null &&
                        focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)
                    ) {
                        parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                    }
                    val cameraSizes = parameters.supportedPreviewSizes
                    val sizes = arrayOfNulls<Size>(cameraSizes.size)
                    var i = 0
                    for (size in cameraSizes) {
                        sizes[i++] = Size(size.width, size.height)
                    }
                    val previewSize = CameraConnectionFragment.chooseOptimalSize(
                        sizes, desiredSize.width, desiredSize.height
                    )
                    parameters.setPreviewSize(previewSize.width, previewSize.height)
                    camera!!.setDisplayOrientation(90)
                    camera!!.parameters = parameters
                    camera!!.setPreviewTexture(texture)
                } catch (exception: IOException) {
                    camera!!.release()
                }

                camera!!.setPreviewCallbackWithBuffer(imageListener)
                val s = camera!!.getParameters().previewSize
                camera!!.addCallbackBuffer(ByteArray(ImageUtils.getYUVByteSize(s.height, s.width)))

                textureView!!.setAspectRatio(s.height, s.width)

                camera!!.startPreview()
            }

            override fun onSurfaceTextureSizeChanged(
                texture: SurfaceTexture, width: Int, height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }

    /** An additional thread for running tasks that shouldn't block the UI.  */
    private var backgroundThread: HandlerThread? = null

    constructor(imageListener: Camera.PreviewCallback, layout: Int, desiredSize: Size) : this() {
        this.imageListener = imageListener
        this.layout = layout
        this.desiredSize = desiredSize
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(layout, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textureView = view.findViewById(R.id.texture) as AutoFitTextureView
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).

        if (textureView!!.isAvailable) {
            camera!!.startPreview()
        } else {
            textureView!!.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        stopCamera()
        stopBackgroundThread()
        super.onPause()
    }

    /** Starts a background thread and its [Handler].  */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread!!.start()
    }

    /** Stops the background thread and its [Handler].  */
    private fun stopBackgroundThread() {
        backgroundThread!!.quitSafely()
        try {
            backgroundThread!!.join()
            backgroundThread = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Exception!", e)
        }
    }

    private fun stopCamera() {
        camera?.apply {
            stopPreview()
            setPreviewCallback(null)
            release()
            camera = null
        }
    }

    private fun getCameraId(): Int {
        val ci = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, ci)
            if (ci.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i
        }
        return -1 // No camera found
    }
}