package com.procamera.logic

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class SidecarMetadataWriter {

    fun saveMetadata(
        videoFile: File,
        iso: Int,
        shutterSpeed: Long,
        fps: Int,
        resolution: String
    ): String {
        val metadataFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}_metadata.json")
        val srtFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}.srt")
        
        val shutterVal = if (shutterSpeed > 0) "1/${(1_000_000_000.0 / shutterSpeed).toInt()}s" else "Auto"
        
        // 1. Generate JSON
        val json = JSONObject().apply {
            put("filename", videoFile.name)
            put("timestamp", System.currentTimeMillis())
            put("iso", iso)
            put("shutter_speed_ns", shutterSpeed)
            put("shutter_speed_formatted", shutterVal)
            put("fps", fps)
            put("resolution", resolution)
        }
        val jsonString = json.toString(4)

        // 2. Generate SRT (Subtitles) - Visible in external players
        val srtContent = """
            1
            00:00:00,000 --> 00:59:59,000
            ISO: $iso | SHUTTER: $shutterVal | $fps FPS | $resolution
        """.trimIndent()

        try {
            // Write JSON
            FileOutputStream(metadataFile).use { it.write(jsonString.toByteArray()) }
            // Write SRT
            FileOutputStream(srtFile).use { it.write(srtContent.toByteArray()) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return jsonString
    }
}
