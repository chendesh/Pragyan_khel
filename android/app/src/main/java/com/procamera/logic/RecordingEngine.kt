package com.procamera.logic

import android.media.*
import android.util.Log
import android.view.Surface
import java.io.File

class RecordingEngine {

    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var inputSurface: Surface? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var muxerStarted = false
    private var frameCount = 0
    private var startTimeUs: Long = -1
    private var playbackFps: Int = 240
    private var captureFps: Int = 240

    @Synchronized
    fun setup(outputFile: File, width: Int, height: Int, captureFps: Int, playbackFps: Int, bitrate: Int): Surface {
        this.captureFps = captureFps
        this.playbackFps = playbackFps
        
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, playbackFps) // Target playback speed
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, captureFps) // Original capture speed
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        
        // Optimize for speed: Remove B-frames
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        
        // High speed hints for the encoder
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, captureFps)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        mediaCodec?.start()

        mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false
        videoTrackIndex = -1
        frameCount = 0
        startTimeUs = -1

        return inputSurface!!
    }

    @Synchronized
    fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return

        if (endOfStream) {
            try {
                // Signal end of stream using reflection to bypass SDK stub issues
                mediaCodec?.javaClass?.getMethod("signalEndOfStream")?.invoke(mediaCodec)
            } catch (e: Exception) {
                Log.e("RecordingEngine", "signalEndOfStream fail: $e")
            }
        }

        var retryCount = 0
        while (true) {
            // During active recording use 0 timeout (non-blocking), during EOS use 2ms
            val timeoutUs = if (endOfStream) 2000L else 0L
            val outputBufferIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            } catch (e: Exception) {
                Log.e("RecordingEngine", "dequeueOutputBuffer fail: $e")
                -1
            }

            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break
                } else {
                    retryCount++
                    if (retryCount > 20) {
                        Log.w("RecordingEngine", "EOS timeout, breaking drain loop")
                        break
                    }
                    continue
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    Log.w("RecordingEngine", "Format changed twice, ignoring")
                    continue
                }
                val newFormat = codec.outputFormat
                videoTrackIndex = muxer.addTrack(newFormat)
                muxer.start()
                muxerStarted = true
            } else if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex) ?: continue

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0 && muxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    
                    // PURE 240 FPS: No time stretching
                    // This ensures the metadata reports exactly 240 FPS in the gallery
                    if (startTimeUs == -1L) startTimeUs = bufferInfo.presentationTimeUs
                    val frameDurationUs = 1_000_000L / captureFps
                    bufferInfo.presentationTimeUs = startTimeUs + (frameCount * frameDurationUs).toLong()

                    try {
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        frameCount++
                        if (frameCount % 240 == 0) Log.d("RecordingEngine", "Total frames written: $frameCount")
                    } catch (e: Exception) {
                        Log.e("RecordingEngine", "muxer.writeSampleData fail: $e")
                    }
                }

                codec.releaseOutputBuffer(outputBufferIndex, false)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d("RecordingEngine", "EOS received in drain loop")
                    break
                }
            } else {
                break
            }
        }
    }

    @Synchronized
    fun release() {
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.e("RecordingEngine", "Codec stop fail: $e")
        }
        
        mediaCodec?.release()
        mediaCodec = null
        
        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
        } catch (e: Exception) {
            Log.e("RecordingEngine", "Muxer stop fail: $e")
        }

        try {
            mediaMuxer?.release()
        } catch (e: Exception) {
            Log.e("RecordingEngine", "Muxer release fail: $e")
        }

        mediaMuxer = null
        muxerStarted = false
        inputSurface?.release()
        inputSurface = null
    }
}
