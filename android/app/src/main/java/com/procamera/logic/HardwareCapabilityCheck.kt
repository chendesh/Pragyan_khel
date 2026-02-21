package com.procamera.logic

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log

class HardwareCapabilityCheck(private val context: Context) {

    data class HighSpeedCapability(
        val isSupported: Boolean,
        val maxFps: Int,
        val resolutions: List<String>,
        val preferredSize: android.util.Size? = null
    )

    fun checkHighSpeedSupport(): Map<String, HighSpeedCapability> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val results = mutableMapOf<String, HighSpeedCapability>()

        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                
                if (configMap == null) continue

                val highSpeedRanges = configMap.highSpeedVideoFpsRanges
                if (highSpeedRanges.isEmpty()) {
                    results[cameraId] = HighSpeedCapability(false, 0, emptyList())
                    continue
                }

                val maxFps = highSpeedRanges.maxOf { it.upper }
                val highSpeedSizes = configMap.highSpeedVideoSizes
                
                // Find sizes that support the maximum FPS
                val supportedSizes = highSpeedSizes.filter { size ->
                    configMap.getHighSpeedVideoFpsRangesFor(size).any { it.upper >= maxFps }
                }

                // Prefer a size that has a FIXED range at the max FPS (e.g. [240, 240])
                val preferredSize = supportedSizes.find { size ->
                    configMap.getHighSpeedVideoFpsRangesFor(size).any { it.lower == maxFps && it.upper == maxFps }
                } ?: supportedSizes.maxByOrNull { it.width * it.height } ?: highSpeedSizes.firstOrNull()

                results[cameraId] = HighSpeedCapability(
                    isSupported = maxFps >= 120,
                    maxFps = maxFps,
                    resolutions = highSpeedSizes.map { "${it.width}x${it.height}" },
                    preferredSize = preferredSize
                )

                Log.d("CapabilityCheck", "Camera $cameraId: Max FPS $maxFps, Optimal Size: $preferredSize")
            }
        } catch (e: Exception) {
            Log.e("CapabilityCheck", "Error checking high speed support", e)
        }

        return results
    }
}
