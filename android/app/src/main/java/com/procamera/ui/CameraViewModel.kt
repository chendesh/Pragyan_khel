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
    val showPlayer: Boolean = false
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
        
        // Use the high-speed camera if found, otherwise default to the first available camera (usually "0")
        cameraId = supportedEntry?.key ?: "0"
        val supported = supportedEntry != null

        _uiState.value = _uiState.value.copy(
            isHighSpeedSupported = supported,
            preferredSize = supportedEntry?.value?.preferredSize ?: android.util.Size(1280, 720),
            fps = supportedEntry?.value?.maxFps ?: 240,
            currentMessage = if (supported) "240 FPS Supported!" else "Limited Hardware - 240 FPS not available"
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
            // Update ManualController with the current FPS before starting
            manualController.setFpsRange(android.util.Range(_uiState.value.fps, _uiState.value.fps))
            
            val surface = previewSurface ?: return
            val recordingActive = _uiState.value.isRecording
            
            val surfaces = mutableListOf(surface)
            
            if (recordingActive) {
                val file = currentVideoFile ?: return
                val targetFps = _uiState.value.fps
                val size = _uiState.value.preferredSize // Use the validated hardware-supported size
                
                Log.d("CameraViewModel", "Recording Initialized: ${size.width}x${size.height} @ $targetFps fps")
                
                try {
                    val settingsTag = "ISO: ${_uiState.value.iso} | 1/${(1_000_000_000.0 / _uiState.value.shutterSpeed).toInt()}s | $targetFps FPS"
                    // setup(file, width, height, captureFps, playbackFps, bitrate, settingsTag)
                    val recordingSurface = recordingEngine.setup(file, size.width, size.height, targetFps, 240, 50_000_000, settingsTag)
                    surfaces.add(recordingSurface)
                } catch (e: Exception) {
                    Log.e("CameraViewModel", "Recorder setup failed: $e")
                    _uiState.value = _uiState.value.copy(isRecording = false, currentMessage = "Hardware Encoder Error")
                    return
                }
            }
            
            cameraManager.createHighSpeedSession(surfaces)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Session failed: $e")
            _uiState.value = _uiState.value.copy(currentMessage = "Camera busy or restricted")
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
        val bestSize = capabilityCheck.getBestSizeForFps(id, fps) ?: android.util.Size(1280, 720)
        
        _uiState.value = _uiState.value.copy(
            fps = fps,
            preferredSize = bestSize,
            currentMessage = "Mode: ${bestSize.width}x${bestSize.height} @ $fps FPS"
        )
        
        manualController.setFpsRange(android.util.Range(fps, fps))
        
        // Safety: If FPS is high, Shutter MUST be fast enough
        val maxShutterNs = (1_000_000_000L / fps) - 100_000L
        if (_uiState.value.shutterSpeed > maxShutterNs) {
            updateShutterSpeed(4_000_000L) 
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
            // Internal app storage temporarily, then move to MediaStore? 
            // Better: just use a file in cache and move it.
            videoFile = File(context.cacheDir, filename)
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val outputDir = File(moviesDir, "ProCamera").apply { if (!exists()) mkdirs() }
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
                // Tight loop without fixed delay for maximum performance at 240fps
                while (_uiState.value.isRecording) {
                    recordingEngine.drainEncoder(false)
                    // Removing fixed delay, using yield for fairer scheduling
                    kotlinx.coroutines.yield() 
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Recording loop error: $e")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(currentMessage = "Recording interrupted")
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
                        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, uri ->
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(
                                    isSaving = false, 
                                    currentMessage = "Saved to Gallery!",
                                    latestVideoUri = uri
                                )
                                parseMetadata(jsonResponse)
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

    private fun saveToGalleryAndroidQ(file: File, jsonContent: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProCamera")
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
                saveJsonToPublicStorage(file.nameWithoutExtension + "_metadata.json", jsonContent)
                
                // Construct SRT content for public storage
                val srtName = file.nameWithoutExtension + ".srt"
                val sProject = jsonContent.let { 
                    val obj = org.json.JSONObject(it)
                    "ISO: ${obj.getInt("iso")} | SHUTTER: ${obj.getString("shutter_speed_formatted")} | ${obj.getInt("fps")} FPS | ${obj.getString("resolution")}"
                }
                val srtContent = "1\n00:00:00,000 --> 00:59:59,000\n$sProject"
                saveJsonToPublicStorage(srtName, srtContent) // Reusing the same function for .srt
                
                file.delete() // Clean up video cache
                
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false, 
                        currentMessage = "Saved to Gallery!",
                        latestVideoUri = uri
                    )
                    parseMetadata(jsonContent)
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "MediaStore save error", e)
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

    private fun saveJsonToPublicStorage(filename: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Movies/ProCamera")
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
