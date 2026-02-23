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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.*
import androidx.compose.foundation.shape.RoundedCornerShape
import android.view.SurfaceView
import android.view.SurfaceHolder
import java.io.File

@Composable
fun ProCameraScreen(viewModel: CameraViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current
    var showHistory by remember { mutableStateOf(false) }

    if (showHistory) {
        VideoHistoryScreen(
            viewModel = viewModel,
            onClose = { showHistory = false }
        )
        return
    }

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

        // Top Info Bar: High Visibility HUD
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(uiState.currentMessage, color = Color.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row {
                Text("FPS: ${uiState.fps}", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("ISO: ${uiState.iso}", color = Color.Yellow)
                Spacer(Modifier.width(12.dp))
                val shutterDisplayString = "1/${(1_000_000_000.0 / uiState.shutterSpeed).toInt()}s"
                Text("SHUTTER: $shutterDisplayString", color = Color.Yellow)
            }
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
                        16_666_666L -> 8_000_000L  // 1/60 -> 1/125
                        8_000_000L -> 4_000_000L   // 1/125 -> 1/250
                        4_000_000L -> 2_000_000L   // 1/250 -> 1/500
                        2_000_000L -> 1_000_000L   // 1/500 -> 1/1000
                        1_000_000L -> 16_666_666L  // 1/1000 -> 1/60
                        else -> 16_666_666L
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
            
            // Record Row with Gallery Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Playback button (left of record button)
                if (uiState.latestVideoUri != null) {
                    Box(
                        modifier = Modifier
                            .padding(end = 24.dp)
                            .size(50.dp)
                            .background(Color.DarkGray, CircleShape)
                            .clickable { viewModel.togglePlayer(true) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("PLAY", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Record Button
                Button(
                    onClick = { viewModel.toggleRecording() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isRecording) Color.Red else Color.White
                    ),
                    modifier = Modifier.size(80.dp)
                ) {}
                
                // Video List button (right of record button)
                Box(
                    modifier = Modifier
                        .padding(start = 24.dp)
                        .size(50.dp)
                        .background(Color.DarkGray, CircleShape)
                        .clickable { showHistory = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text("LIST", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Full Screen Video Player with Metadata Overlay
        if (uiState.showPlayer && uiState.latestVideoUri != null) {
            PlaybackScreen(
                uri = uiState.latestVideoUri!!,
                metadata = uiState.latestMetadata,
                onClose = { viewModel.togglePlayer(false) }
            )
        }

        // Saved to Gallery Confirmation Notification
        AnimatedVisibility(
            visible = uiState.showSavedConfirmation,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF2E7D32), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("Saved to Gallery!", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
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

@Composable
fun PlaybackScreen(
    uri: android.net.Uri,
    metadata: com.procamera.models.VideoMetadata?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val playbackEngine = remember { com.procamera.logic.PlaybackEngine(context) }
    var slowMoEnabled by remember { mutableStateOf(true) } // Default to slow motion

    LaunchedEffect(slowMoEnabled) {
        playbackEngine.setSlowMotion(slowMoEnabled, metadata?.actualFps ?: 120)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Video Player
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    useController = true
                    playbackEngine.initializePlayer(this, uri)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Control Overlay (Top)
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Slow Mo Toggle
            Button(
                onClick = { slowMoEnabled = !slowMoEnabled },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (slowMoEnabled) Color.Yellow else Color.DarkGray
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (slowMoEnabled) "SLOW MOTION: ON" else "SLOW MOTION: OFF",
                    color = if (slowMoEnabled) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // Close Button
            IconButton(
                onClick = { 
                    playbackEngine.release()
                    onClose() 
                }
            ) {
                Text("✕", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
            }
        }

        // Metadata Overlay (Top-Left)
        if (metadata != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 80.dp, start = 24.dp) // Padded down to avoid Slow-Mo button
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text("CAPTURE METADATA", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                MetadataItem("ISO", metadata.iso.toString(), size = 16)
                MetadataItem("SHUTTER", metadata.shutterSpeed, size = 16)
                MetadataItem("RECORD FPS", metadata.actualFps.toString(), size = 16)
                MetadataItem("RESOLUTION", metadata.resolution, size = 16)
                val date = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(metadata.timestamp))
                MetadataItem("TIME", date, size = 16)
            }
        }
    }
}

@Composable
fun VideoHistoryScreen(
    viewModel: CameraViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        // App Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("INTERNAL VIDEOS", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(onClick = onClose) {
                Text("✕", color = Color.White, fontSize = 24.sp)
            }
        }

        if (uiState.localVideos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No videos stored in app yet.", color = Color.Gray)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.localVideos.size) { index ->
                    val file = uiState.localVideos[index]
                    val date = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                            .clickable { viewModel.playLocalVideo(file) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(48.dp).background(Color.Yellow.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶", color = Color.Yellow, fontSize = 20.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(file.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(date, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetadataItem(label: String, value: String, size: Int = 12) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color.Yellow, fontSize = size.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
    }
}
