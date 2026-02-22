package com.procamera.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.procamera.logic.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.view.Surface
import android.hardware.camera2.CameraDevice
import android.media.MediaScannerConnection
import android.os.Environment
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Build

data class CameraUiState(
    val isRecording: Boolean = false,
    val isSaving: Boolean = false,
    val iso: Int = 400,
    val shutterSpeed: Long = 1_000_000L,
    val fps: Int = 240,
    val isHighSpeedSupported: Boolean = false,
    val preferredSize: android.util.Size = android.util.Size(1280, 720),
    val currentMessage: String = "",
    val latestVideoUri: android.net.Uri? = null,
    val latestMetadata: com.procamera.models.VideoMetadata? = null,
    val showPlayer: Boolean = false,
    val showSavedConfirmation: Boolean = false
)

class CameraViewModel(
    private val context: Context,
    private val cameraManager: Camera2Manager,
    private val manualController: ManualController,
    private val recordingEngine: RecordingEngine,
    private val metadataWriter: SidecarMetadataWriter,
    private val capabilityCheck: HardwareCapabilityCheck,
    private val api: com.procamera.api.ProCameraApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState = _uiState.asStateFlow()

    private var previewSurface: Surface? = null
    private var cameraId: String? = null
    private var currentVideoFile: File? = null

    init {
        checkCapabilities()
        manualController.setFpsRange(android.util.Range(240, 240))
        cameraManager.startBackgroundThread()
    }

    private fun checkCapabilities() {
        val caps = capabilityCheck.checkHighSpeedSupport()
        val supportedEntry = caps.entries.firstOrNull { it.value.isSupported }
        
        cameraId = supportedEntry?.key ?: "0"
        val supported = supportedEntry != null
        val maxHardwareFps = supportedEntry?.value?.maxFps ?: 60

        // Preferred Initial State: 
        // If 240 is supported, start at 240/1080p. 
        // If only 120 is supported, start at 120/720p.
        // Otherwise 60/720p.
        val targetFps = if (maxHardwareFps >= 240) 240 else if (maxHardwareFps >= 120) 120 else 60
        val bestSize = capabilityCheck.getBestSizeForFps(cameraId!!, targetFps) ?: android.util.Size(1280, 720)

        _uiState.value = _uiState.value.copy(
            isHighSpeedSupported = supported,
            preferredSize = bestSize,
            fps = targetFps,
            currentMessage = if (supported) "System: ${bestSize.width}x${bestSize.height} @ $targetFps FPS" else "Standard Mode Active"
        )
    }

    fun onSurfaceReady(surface: Surface) {
        previewSurface = surface
        val id = cameraId ?: return
        
        // Safety: Don't open if permission is missing
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            _uiState.value = _uiState.value.copy(currentMessage = "Waiting for Permissions...")
            return
        }

        try {
            cameraManager.openCamera(id, object : Camera2Manager.CameraStateCallback {
                override fun onOpened(camera: CameraDevice) {
                    _uiState.value = _uiState.value.copy(currentMessage = "Camera Ready")
                    startSession()
                }

                override fun onDisconnected() {
                    _uiState.value = _uiState.value.copy(currentMessage = "Camera Disconnected")
                }

                override fun onError(error: Int) {
                    _uiState.value = _uiState.value.copy(currentMessage = "Camera Error: $error")
                }
            })
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Failed to open camera: $e")
        }
    }

    private fun startSession() {
        try {
            val surface = previewSurface ?: return
            val id = cameraId ?: return
            
            // Re-apply FPS and size settings
            manualController.setFpsRange(android.util.Range(_uiState.value.fps, _uiState.value.fps))
            
            val surfaces = mutableListOf(surface)
            val recordingActive = _uiState.value.isRecording
            
            if (recordingActive) {
                val file = currentVideoFile ?: return
                val size = _uiState.value.preferredSize
                try {
                    val recordingSurface = recordingEngine.setup(
                        file, size.width, size.height, 
                        _uiState.value.fps, _uiState.value.fps / 2, 50_000_000, "240FPS_MODE"
                    )
                    surfaces.add(recordingSurface)
                } catch (e: Exception) {
                    Log.e("CameraViewModel", "Recorder setup failed: $e")
                    _uiState.value = _uiState.value.copy(
                        isRecording = false, 
                        currentMessage = "Hardware Encoder Error: ${e.message}"
                    )
                    return
                }
            }
            
            val isHighSpeedRequested = _uiState.value.fps >= 120 && _uiState.value.isHighSpeedSupported
            cameraManager.createSession(surfaces, isHighSpeedRequested)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Session failed: $e")
            _uiState.value = _uiState.value.copy(currentMessage = "Camera Error: Please Restart App")
        }
    }

    fun updateIso(iso: Int) {
        manualController.setIso(iso)
        _uiState.value = _uiState.value.copy(iso = iso)
    }

    fun updateShutterSpeed(ns: Long) {
        manualController.setShutterSpeed(ns)
        _uiState.value = _uiState.value.copy(shutterSpeed = ns)
        // Restart session to apply shutter change in high speed mode if needed
        startSession()
    }

    fun updateFps(fps: Int) {
        val id = cameraId ?: "0"
        
        // Verify if this FPS is actually supported by looking at hardware caps
        val caps = capabilityCheck.checkHighSpeedSupport()[id]
        val maxAvailable = caps?.maxFps ?: 60
        
        // If user requested 240 but hardware only does 120, cap it
        val finalFps = if (fps > maxAvailable) 60 else fps
        
        val bestSize = capabilityCheck.getBestSizeForFps(id, finalFps) ?: android.util.Size(1280, 720)
        
        Log.d("CameraViewModel", "Mode Change: $finalFps FPS | Size: $bestSize | MaxHardware: $maxAvailable")
        
        _uiState.value = _uiState.value.copy(
            fps = finalFps,
            preferredSize = bestSize,
            currentMessage = "Mode: ${bestSize.width}x${bestSize.height} @ $finalFps FPS"
        )
        
        manualController.setFpsRange(android.util.Range(finalFps, finalFps))
        
        val minShutterNs = 1_000_000_000L / finalFps
        if (_uiState.value.shutterSpeed > minShutterNs) {
            updateShutterSpeed(minShutterNs - 100_000L)
        } else {
            startSession()
        }
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (_uiState.value.isRecording || _uiState.value.isSaving) return // Guard
        
        val filename = "VID_${System.currentTimeMillis()}.mp4"
        val videoFile: File
        
        // On Android 10+, we MUST use MediaStore for public folders, or getExternalFilesDir for private
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use cache then move to MediaStore
            videoFile = File(context.cacheDir, filename)
        } else {
            // Legacy: Save directly to DCIM for immediate gallery visibility
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val outputDir = File(dcimDir, "ProCamera").apply { if (!exists()) mkdirs() }
            videoFile = File(outputDir, filename)
        }

        currentVideoFile = videoFile
        _uiState.value = _uiState.value.copy(
            isRecording = true,
            currentMessage = "Recording Started..."
        )
        
        // Restart session with recording surface added
        startSession()
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Critical: Initial drain to clear buffers before high-speed stream hits
                recordingEngine.drainEncoder(false)
                
                while (_uiState.value.isRecording) {
                    recordingEngine.drainEncoder(false)
                    // At 240fps, we need to yield frequently to let the UI and Muxer catch up
                    kotlinx.coroutines.delay(1) 
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Recording loop error: $e")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(currentMessage = "Recording stopped: ${e.message}")
                }
            }
        }
    }

    private fun stopRecording() {
        // 1. Immediately update UI state
        _uiState.value = _uiState.value.copy(
            isRecording = false, 
            isSaving = true, 
            currentMessage = "Finishing Video..."
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 2. Switch camera session back to PREVIEW ONLY immediately
                // This makes the UI feel instant and stops more frames from entering the encoder
                withContext(Dispatchers.Main) {
                    startSession()
                }

                // 3. Finish saving the file in the background
                recordingEngine.drainEncoder(true)
                recordingEngine.release() // Release early to close the file handle
                
                val file = currentVideoFile
                if (file != null && file.exists() && file.length() > 0) {
                    // 1. Generate local metadata JSON string
                    val size = _uiState.value.preferredSize
                    val jsonResponse = metadataWriter.saveMetadata(file, _uiState.value.iso, _uiState.value.shutterSpeed, _uiState.value.fps, "${size.width}x${size.height}")
                    
                    // 2. Move to Gallery (and optionally the JSON too)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveToGalleryAndroidQ(file, jsonResponse)
                    } else {
                        // For legacy devices, wait for MediaScanner
                        kotlinx.coroutines.suspendCancellableCoroutine<android.net.Uri?> { continuation ->
                            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, uri ->
                                continuation.resume(uri) {}
                            }
                        }.let { uri ->
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Saved to Gallery!", android.widget.Toast.LENGTH_SHORT).show()
                                _uiState.value = _uiState.value.copy(
                                    isSaving = false, 
                                    currentMessage = "Recording Completed!",
                                    latestVideoUri = uri,
                                    showSavedConfirmation = true
                                )
                                parseMetadata(jsonResponse)
                                resetSavedConfirmation()
                            }
                        }
                    }

                    // 4. Sync metadata to Cloud Backend
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val metadata = com.procamera.models.VideoMetadata(
                                filename = file.name,
                                iso = _uiState.value.iso,
                                shutterSpeed = "1/${(1_000_000_000.0 / _uiState.value.shutterSpeed).toInt()}",
                                actualFps = _uiState.value.fps,
                                resolution = "${size.width}x${size.height}"
                            )
                            Log.d("CameraViewModel", "Syncing metadata to backend: $metadata")
                            val response = api.uploadMetadata(metadata)
                            if (response.success) {
                                withContext(Dispatchers.Main) {
                                    _uiState.value = _uiState.value.copy(currentMessage = "Synced to Cloud!")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CameraViewModel", "Backend sync failed: $e")
                        }
                    }
                } else {
                    Log.e("CameraViewModel", "File error: exists=${file?.exists()}, size=${file?.length()}")
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isSaving = false, currentMessage = "Error: File empty or missing")
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Background cleanup fail: $e")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isSaving = false, currentMessage = "Processed with errors")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (_uiState.value.isSaving) {
                        _uiState.value = _uiState.value.copy(isSaving = false)
                    }
                }
            }
        }
    }

    private suspend fun saveToGalleryAndroidQ(file: File, jsonContent: String) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)

            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    
                    // Also save the JSON and SRT files to public location
                    try {
                        saveJsonToPublicStorage(file.nameWithoutExtension + "_metadata.json", jsonContent)
                        
                        val srtName = file.nameWithoutExtension + ".srt"
                        val sProject = jsonContent.let { 
                            val obj = org.json.JSONObject(it)
                            "ISO: ${obj.getInt("iso")} | SHUTTER: ${obj.getString("shutter_speed_formatted")} | ${obj.getInt("fps")} FPS | ${obj.getString("resolution")}"
                        }
                        val srtContent = "1\n00:00:00,000 --> 00:59:59,000\n$sProject"
                        saveJsonToPublicStorage(srtName, srtContent)
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Sidecar save failed but video ok: $e")
                    }
                    
                    file.delete() // Clean up video cache
                    
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Saved to Gallery!", android.widget.Toast.LENGTH_SHORT).show()
                        _uiState.value = _uiState.value.copy(
                            isSaving = false, 
                            currentMessage = "Recording Completed!",
                            latestVideoUri = uri,
                            showSavedConfirmation = true
                        )
                        parseMetadata(jsonContent)
                        resetSavedConfirmation()
                    }
                } catch (e: Exception) {
                    Log.e("CameraViewModel", "MediaStore save error", e)
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isSaving = false, currentMessage = "Error Saving to Gallery")
                    }
                }
            }
        }
    }

    private fun parseMetadata(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            val metadata = com.procamera.models.VideoMetadata(
                filename = obj.getString("filename"),
                iso = obj.getInt("iso"),
                shutterSpeed = obj.getString("shutter_speed_formatted"),
                actualFps = obj.getInt("fps"),
                resolution = obj.getString("resolution"),
                timestamp = obj.getLong("timestamp")
            )
            _uiState.value = _uiState.value.copy(latestMetadata = metadata)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Metadata parse failed: $e")
        }
    }

    fun togglePlayer(show: Boolean) {
        _uiState.value = _uiState.value.copy(showPlayer = show)
    }

    private fun resetSavedConfirmation() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.value = _uiState.value.copy(showSavedConfirmation = false)
        }
    }

    private fun saveJsonToPublicStorage(filename: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, if (filename.endsWith(".srt")) "text/plain" else "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/ProCamera")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(content.toByteArray())
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.closeCamera()
        cameraManager.stopBackgroundThread()
    }
}
