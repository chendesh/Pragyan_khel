package com.procamera.api

import com.procamera.models.VideoMetadata
import retrofit2.http.Body
import retrofit2.http.POST

interface ProCameraApi {
    @POST("/api/metadata")
    suspend fun uploadMetadata(@Body metadata: VideoMetadata): MetadataResponse
}

data class MetadataResponse(
    val success: Boolean,
    val message: String
)
