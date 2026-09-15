package com.mlbb.highlight.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecorder(
    context: Context,
    private val width: Int,
    private val height: Int,
    outputFile: File? = null
) {
    private val appContext = context.applicationContext
    private val bufferInfo = MediaCodec.BufferInfo()
    private val finishedLatch = CountDownLatch(1)
    private val isStopping = AtomicBoolean(false)
    private val encoder: MediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val muxer: MediaMuxer
    private val drainThread: Thread
    private val outputPath: File = outputFile ?: createOutputFile()

    val outputFile: File
        get() = outputPath
    val inputSurface: Surface

    @Volatile
    private var muxerStarted = false
    private var trackIndex = -1

    init {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        muxer = MediaMuxer(outputPath.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        encoder.start()

        drainThread = Thread(::drainEncoder, "ScreenRecorderDrain").also { it.start() }
    }

    fun stop(): File {
        if (isStopping.compareAndSet(false, true)) {
            encoder.signalEndOfInputStream()
        }

        finishedLatch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        inputSurface.release()
        return outputFile
    }

    private fun drainEncoder() {
        try {
            while (true) {
                when (val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder output format changed after muxer start." }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    else -> {
                        if (outputBufferIndex >= 0) {
                            writeOutputBuffer(outputBufferIndex)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            releaseEncoder()
            finishedLatch.countDown()
        }
    }

    private fun writeOutputBuffer(outputBufferIndex: Int) {
        val encodedData = encoder.getOutputBuffer(outputBufferIndex)
            ?: error("Encoder output buffer $outputBufferIndex was null.")

        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            bufferInfo.size = 0
        }

        if (bufferInfo.size > 0 && muxerStarted) {
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
        }

        encoder.releaseOutputBuffer(outputBufferIndex, false)
    }

    private fun releaseEncoder() {
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        if (muxerStarted) {
            runCatching { muxer.stop() }
        }
        runCatching { muxer.release() }
    }

    private fun createOutputFile(): File {
        val directory = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            OUTPUT_DIRECTORY
        ).apply { mkdirs() }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(directory, "recording_$timestamp.mp4")
    }

    companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_SECONDS = 8L
        private const val OUTPUT_DIRECTORY = "Recordings"

        private fun estimateBitrate(width: Int, height: Int): Int {
            return (width * height * 4).coerceIn(4_000_000, 12_000_000)
        }
    }
}
