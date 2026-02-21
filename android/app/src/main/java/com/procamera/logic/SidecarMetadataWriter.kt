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
    ) {
        val metadataFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}_metadata.json")
        val json = JSONObject().apply {
            put("filename", videoFile.name)
            put("timestamp", System.currentTimeMillis())
            put("iso", iso)
            put("shutter_speed_ns", shutterSpeed)
            put("shutter_speed_formatted", "${1_000_000_000.0 / shutterSpeed}s")
            put("fps", fps)
            put("resolution", resolution)
        }

        try {
            FileOutputStream(metadataFile).use { fos ->
                fos.write(json.toString(4).toByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
