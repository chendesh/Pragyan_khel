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
    private var captureSession: CameraConstrainedHighSpeedCaptureSession? = null
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

    fun createHighSpeedSession(surfaces: List<Surface>) {
        val camera = cameraDevice ?: return
        sessionSurfaces = surfaces
        
        // Close existing session before starting a new one
        captureSession?.close()
        captureSession = null
        
        try {
            // Use ConstrainedHighSpeedCaptureSession for High-FPS (>120 FPS)
            camera.createConstrainedHighSpeedCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (session is CameraConstrainedHighSpeedCaptureSession) {
                            captureSession = session
                            startHighSpeedPreview()
                        } else {
                            // Fallback if not a high speed session
                            Log.w("Camera2Manager", "Session is not high speed, using normal capture")
                        }
                    }

                    override fun onConfigureFailed(p0: CameraCaptureSession) {
                        Log.e("Camera2Manager", "Failed to configure high speed session")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e("Camera2Manager", "High Speed Session failed, falling back to normal session: $e")
            // Fallback to regular capture session if hardware fails high speed
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = null // Not high-speed
                        // Normal preview start logic
                        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD) ?: return
                        surfaces.forEach { builder.addTarget(it) }
                        manualController.applyManualSettings(builder)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    }
                    override fun onConfigureFailed(p0: CameraCaptureSession) {
                        Log.e("Camera2Manager", "Total session failure")
                    }
                },
                backgroundHandler
            )
        }
    }

    private fun startHighSpeedPreview() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        // Use TEMPLATE_RECORD explicitly for high speed
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD) ?: return
        
        // Add all surfaces as targets
        sessionSurfaces.forEach { builder.addTarget(it) }
        
        // Use CONTINUOUS_VIDEO AF to keep the video sharp while recording
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        
        // Critical: Enable High Speed Scene Mode
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HIGH_SPEED_VIDEO)
        
        // Push the sensor to its limits: Disable power saving/ZSL for 240fps
        builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        
        // Detect hardware characteristics
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(camera.id)
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        
        // Strictly target 240
        val ranges = configMap?.getHighSpeedVideoFpsRanges() ?: emptyArray()
        val validRange = ranges.find { it.upper == 240 && it.lower == 240 } 
            ?: ranges.find { it.upper == 240 }
            ?: android.util.Range(240, 240)

        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, validRange)

        manualController.setFpsRange(validRange)
        manualController.applyManualSettings(builder)
        
        // List of capture requests for high speed (must match the high speed session requirements)
        val requestList = session.createHighSpeedRequestList(builder.build())
        session.setRepeatingBurst(requestList, null, backgroundHandler)
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }
}
