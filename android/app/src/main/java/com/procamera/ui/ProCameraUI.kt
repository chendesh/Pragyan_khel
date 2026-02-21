package com.procamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.SurfaceView
import android.view.SurfaceHolder
import java.io.File

@Composable
fun ProCameraScreen(viewModel: CameraViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Real Camera Preview
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    val preferredSize = uiState.preferredSize
                    // CRITICAL: High Speed mode requires the surface to match the capture size exactly
                    holder.setFixedSize(preferredSize.width, preferredSize.height)
                    
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.onSurfaceReady(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })
                }
            },
            update = { view ->
                val size = uiState.preferredSize
                view.holder.setFixedSize(size.width, size.height)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Black Overlay during recording (Hides preview from screen)
        if (uiState.isRecording) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // Recording Overlay
        if (uiState.isRecording) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Color.Red, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("RECORDING @ 240 FPS", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Top Info Bar
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(uiState.currentMessage, color = Color.Green, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("FPS: ${uiState.fps}", color = Color.White)
        }

        // Manual Controls Bottom Overlay
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), 
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ISO Toggle
                ManualDial(label = "ISO", value = uiState.iso.toString(), onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val nextIso = when(uiState.iso) {
                        100 -> 200
                        200 -> 400
                        400 -> 800
                        800 -> 1600
                        1600 -> 3200
                        else -> 100
                    }
                    viewModel.updateIso(nextIso)
                })

                // Shutter Toggle: Faster speeds for high-speed video
                val shutterDisplay = "1/${(1_000_000_000.0 / uiState.shutterSpeed).toInt()}"
                ManualDial(label = "SHUTTER", value = shutterDisplay, onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val nextShutter = when(uiState.shutterSpeed) {
                        4_000_000L -> 2_000_000L   // 1/250 -> 1/500
                        2_000_000L -> 1_000_000L   // 1/500 -> 1/1000
                        1_000_000L -> 500_000L     // 1/1000 -> 1/2000
                        500_000L -> 16_666_666L    // 1/2000 -> 1/60
                        16_666_666L -> 8_000_000L  // 1/60 -> 1/125
                        8_000_000L -> 4_000_000L   // 1/125 -> 1/250
                        else -> 4_000_000L
                    }
                    viewModel.updateShutterSpeed(nextShutter)
                })

                // FPS Toggle (If supported)
                ManualDial(
                    label = "FPS", 
                    value = uiState.fps.toString(), 
                    enabled = uiState.isHighSpeedSupported,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val nextFps = when(uiState.fps) {
                            60 -> 120
                            120 -> 240
                            else -> 60
                        }
                        viewModel.updateFps(nextFps)
                    }
                )
            }
            
            Spacer(Modifier.height(24.dp))

            // Record Button
            val context = LocalContext.current
            Button(
                onClick = { viewModel.toggleRecording() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.isRecording) Color.Red else Color.White
                ),
                modifier = Modifier.size(80.dp)
            ) {}
        }
    }
}

@Composable
fun ManualDial(
    label: String, 
    value: String, 
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(8.dp)
            .clickable(enabled = enabled && !false) { onClick() }
            .alpha(if (enabled) 1f else 0.3f)
    ) {
        Text(
            text = label, 
            color = Color.Gray, 
            fontSize = 11.sp, 
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value, 
            color = if (enabled) Color.White else Color.DarkGray, 
            fontWeight = FontWeight.ExtraBold, 
            fontSize = 20.sp
        )
    }
}
