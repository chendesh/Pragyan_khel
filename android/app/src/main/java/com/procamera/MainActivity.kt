package com.procamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.procamera.ui.ProCameraScreen
import com.procamera.ui.CameraViewModel
import com.procamera.logic.*

class MainActivity : ComponentActivity() {
    
    // In a production app, use Dependency Injection (like Hilt)
    private lateinit var viewModel: CameraViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle results if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val manualController = ManualController()
        val camera2Manager = Camera2Manager(this, manualController)
        val recordingEngine = RecordingEngine()
        val sidecarWriter = SidecarMetadataWriter()
        val capabilityCheck = HardwareCapabilityCheck(this)

        viewModel = CameraViewModel(
            this,
            camera2Manager,
            manualController,
            recordingEngine,
            sidecarWriter,
            capabilityCheck
        )

        checkPermissions()

        setContent {
            ProCameraScreen(viewModel)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}
