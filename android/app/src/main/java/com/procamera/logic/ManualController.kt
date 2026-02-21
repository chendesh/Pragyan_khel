package com.procamera.logic

import android.hardware.camera2.CaptureRequest
import android.util.Range

class ManualController {

    private var currentIso: Int = 400
    private var currentShutterSpeed: Long = 1_000_000L // 1ms in nanoseconds
    private var currentFpsRange: Range<Int> = Range(240, 240)

    fun applyManualSettings(builder: CaptureRequest.Builder) {
        // Enable manual control by turning off Auto-Exposure
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

        // Apply Shutter Speed (Sensor Exposure Time)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentShutterSpeed)

        // Apply ISO (Sensor Sensitivity)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        
        // Ensure we are in video record intent for better stability
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)

        // Apply Target FPS Range
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, currentFpsRange)
    }

    fun setIso(iso: Int) {
        currentIso = iso
    }

    fun setShutterSpeed(nanoseconds: Long) {
        currentShutterSpeed = nanoseconds
    }

    fun setFpsRange(range: Range<Int>) {
        currentFpsRange = range
    }

    fun getTargetFps(): Int = currentFpsRange.upper

    fun getCurrentSettings(): String {
        return "ISO: $currentIso | Shutter: ${currentShutterSpeed / 1_000_000.0}ms | FPS: ${currentFpsRange.upper}"
    }
}
