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
    val currentMessage: String = ""
)

class CameraViewModel(
    private val context: Context,
    private val cameraManager: Camera2Manager,
    private val manualController: ManualController,
    private val recordingEngine: RecordingEngine,
    private val metadataWriter: SidecarMetadataWriter,
    private val capabilityCheck: HardwareCapabilityCheck
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
        // Update ManualController with the current FPS before starting
        manualController.setFpsRange(android.util.Range(_uiState.value.fps, _uiState.value.fps))
        
        val surface = previewSurface ?: return
        val recordingActive = _uiState.value.isRecording
        
        val surfaces = mutableListOf(surface)
        
        if (recordingActive) {
            val file = currentVideoFile ?: return
            val fps = _uiState.value.fps
            val size = _uiState.value.preferredSize
            // 40Mbps for high clarity. Reports 240fps in gallery while being "lightly" slow.
            val recordingSurface = recordingEngine.setup(file, size.width, size.height, fps, 240, 40_000_000)
            surfaces.add(recordingSurface)
        }
        
        cameraManager.createHighSpeedSession(surfaces)
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
        _uiState.value = _uiState.value.copy(fps = fps)
        manualController.setFpsRange(android.util.Range(fps, fps))
        
        // Safety: If FPS is high, Shutter MUST be fast enough (e.g., 240fps needs < 1/240s)
        val maxShutterNs = (1_000_000_000L / fps) - 100_000L // Subtract a bit for safety
        if (_uiState.value.shutterSpeed > maxShutterNs) {
            updateShutterSpeed(4_000_000L) // Set to 1/250s as a safe default for high speed
        } else {
            // FPS change requires session restart
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
                    // Save sidecar metadata
                    metadataWriter.saveMetadata(file, _uiState.value.iso, _uiState.value.shutterSpeed, _uiState.value.fps, "1280x720")
                    
                    // Move to Gallery if on Android 10+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveToGalleryAndroidQ(file)
                    } else {
                        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ ->
                            viewModelScope.launch(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(isSaving = false, currentMessage = "Saved to Gallery!")
                            }
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

    private fun saveToGalleryAndroidQ(file: File) {
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
                file.delete() // Clean up cache
                
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isSaving = false, currentMessage = "Saved to Gallery!")
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "MediaStore save error", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.closeCamera()
        cameraManager.stopBackgroundThread()
    }
}
