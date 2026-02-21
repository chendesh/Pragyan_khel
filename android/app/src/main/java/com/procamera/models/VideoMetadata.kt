package com.procamera.models

data class VideoMetadata(
    val filename: String,
    val iso: Int,
    val shutterSpeed: String,
    val actualFps: Int,
    val resolution: String,
    val timestamp: Long = System.currentTimeMillis()
)
