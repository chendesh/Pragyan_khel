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
                val maxFps = if (highSpeedRanges.isNotEmpty()) highSpeedRanges.maxOf { it.upper } else 0
                val highSpeedSizes = configMap.highSpeedVideoSizes ?: emptyArray()
                
                if (highSpeedSizes.isEmpty()) {
                    results[cameraId] = HighSpeedCapability(false, 0, emptyList())
                    continue
                }

                // Find sizes that support 240fps (or the max fps)
                val target = if (maxFps > 0) maxFps else 240
                val supportedByFps = highSpeedSizes.filter { size ->
                    try {
                        configMap.getHighSpeedVideoFpsRangesFor(size).any { it.upper >= target }
                    } catch (e: Exception) { false }
                }

                // User requirement: 240fps -> 1080p, 120/60fps -> 720p
                val preferredSize = if (target == 240) {
                    supportedByFps.find { it.width == 1920 && it.height == 1080 }
                        ?: supportedByFps.maxByOrNull { it.width * it.height }
                } else {
                    supportedByFps.find { it.width == 1280 && it.height == 720 }
                        ?: supportedByFps.minByOrNull { it.width * it.height }
                } ?: highSpeedSizes.firstOrNull() ?: android.util.Size(1280, 720)

                results[cameraId] = HighSpeedCapability(
                    isSupported = maxFps >= 120,
                    maxFps = maxFps,
                    resolutions = highSpeedSizes.map { "${it.width}x${it.height}" },
                    preferredSize = preferredSize
                )

                Log.d("CapabilityCheck", "Camera $cameraId: Max FPS $maxFps, Selected Size: $preferredSize")
            }
        } catch (e: Exception) {
            Log.e("CapabilityCheck", "Error checking high speed support", e)
        }

        return results
    }

    fun getBestSizeForFps(cameraId: String, targetFps: Int): android.util.Size? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        
        val highSpeedSizes = configMap.highSpeedVideoSizes ?: return null
        val supportedByFps = highSpeedSizes.filter { size ->
            try {
                configMap.getHighSpeedVideoFpsRangesFor(size).any { it.upper >= targetFps }
            } catch (e: Exception) { false }
        }

        if (supportedByFps.isEmpty()) return null

        // User requirement: 240fps -> 1080p, 120/60fps -> 720p
        return if (targetFps >= 240) {
            supportedByFps.find { it.width == 1920 && it.height == 1080 }
                ?: supportedByFps.maxByOrNull { it.width * it.height } // Fallback to largest if 1080p missing
        } else {
            supportedByFps.find { it.width == 1280 && it.height == 720 }
                ?: supportedByFps.minByOrNull { it.width * it.height } // Fallback to smallest/standard if 720p missing
        }
    }
}
