package com.procamera.logic

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

class Camera2Manager(
    private val context: Context,
    private val manualController: ManualController
) {
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    interface CameraStateCallback {
        fun onOpened(camera: CameraDevice)
        fun onDisconnected()
        fun onError(error: Int)
    }

    private var sessionSurfaces: List<Surface> = emptyList()

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("Camera2Manager", "Interrupted stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, callback: CameraStateCallback) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    callback.onOpened(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDevice?.close()
                    cameraDevice = null
                    callback.onDisconnected()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDevice?.close()
                    cameraDevice = null
                    callback.onError(error)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e("Camera2Manager", "Error opening camera", e)
        }
    }

    fun createSession(surfaces: List<Surface>, isHighSpeed: Boolean) {
        val camera = cameraDevice ?: return
        sessionSurfaces = surfaces
        
        captureSession?.close()
        captureSession = null
        
        try {
            if (isHighSpeed) {
                camera.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            startCapture(true)
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession) {
                            Log.e("Camera2Manager", "HighSpeed configuration failed, retrying standard...")
                            createSession(surfaces, false)
                        }
                    },
                    backgroundHandler
                )
            } else {
                camera.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            startCapture(false)
                        }

                        override fun onConfigureFailed(p0: CameraCaptureSession) {
                            Log.e("Camera2Manager", "Standard configuration failed")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e("Camera2Manager", "Fatal session error: $e")
        }
    }

    private fun startCapture(isHighSpeed: Boolean) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val builder = camera.createCaptureRequest(
            if (isHighSpeed) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        ) ?: return
        
        sessionSurfaces.forEach { builder.addTarget(it) }
        
        if (isHighSpeed) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO)
            
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(camera.id)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val ranges = configMap?.getHighSpeedVideoFpsRanges() ?: emptyArray()
            val validRange = ranges.find { it.upper == 240 } ?: android.util.Range(240, 240)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, validRange)
        }

        manualController.applyManualSettings(builder)
        
        if (isHighSpeed && session is CameraConstrainedHighSpeedCaptureSession) {
            val requestList = session.createHighSpeedRequestList(builder.build())
            session.setRepeatingBurst(requestList, null, backgroundHandler)
        } else {
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        }
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }
}
