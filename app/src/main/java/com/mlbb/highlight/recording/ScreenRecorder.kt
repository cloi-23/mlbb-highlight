package com.mlbb.highlight.recording

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Environment
import android.view.Surface
import com.mlbb.highlight.settings.RecordingAudioSource
import java.io.File
import java.util.ArrayDeque
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScreenRecorder(
    context: Context,
    private val width: Int,
    private val height: Int,
    outputFile: File? = null,
    private val frameRate: Int = DEFAULT_FRAME_RATE,
    private val audioSource: RecordingAudioSource = RecordingAudioSource.SILENT,
    projection: MediaProjection? = null
) {
    private val appContext = context.applicationContext
    private val outputPath: File = outputFile ?: createOutputFile()
    private val muxer = MediaMuxer(outputPath.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val hasAudio = audioSource != RecordingAudioSource.SILENT
    private val muxerCoordinator = MuxerCoordinator(muxer, if (hasAudio) 2 else 1)
    private val videoEncoder = createVideoEncoder()
    private val videoInputSurface: Surface = videoEncoder.createInputSurface()
    private val audioCapture = if (hasAudio) {
        checkNotNull(projection) { "Screen capture permission is required for audio recording" }
        AudioCapture(audioSource, projection)
    } else {
        null
    }
    private val isStopping = AtomicBoolean(false)
    private val accumulatedPauseDurationUs = AtomicReference(0L)
    private val pauseStartedAtNs = AtomicReference<Long?>(null)
    private val firstVideoPresentationTimeUs = AtomicReference<Long?>(null)
    private val lastVideoPresentationTimeUs = AtomicReference(-1L)
    private val audioSignalDetected = AtomicBoolean(false)
    private val encoderError = AtomicReference<Throwable?>(null)
    private val finishedLatch = CountDownLatch(if (hasAudio) 2 else 1)
    private val videoDrainThread: Thread
    private val audioDrainThread: Thread?

    val outputFile: File
        get() = outputPath
    val inputSurface: Surface
        get() = videoInputSurface
    val audioInputDetected: Boolean
        get() = audioSignalDetected.get()

    init {
        videoEncoder.start()
        videoDrainThread = Thread({ drainEncoder(videoEncoder, isVideo = true) }, "VideoEncoderDrain")
            .also { it.start() }

        if (audioCapture != null) {
            audioDrainThread = Thread({ drainEncoder(audioCapture.encoder, isVideo = false) }, "AudioEncoderDrain")
                .also { it.start() }
            try {
                audioCapture.start()
            } catch (error: Exception) {
                isStopping.set(true)
                videoEncoder.signalEndOfInputStream()
                audioCapture.stop()
                val finished = finishedLatch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                videoInputSurface.release()
                if (finished) muxerCoordinator.finish()
                throw error
            }
        } else {
            audioDrainThread = null
        }
    }

    fun stop(): File {
        if (isStopping.compareAndSet(false, true)) {
            closeCurrentPause()
            videoEncoder.signalEndOfInputStream()
            audioCapture?.stop()
        }

        val finished = try {
            finishedLatch.await(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            videoInputSurface.release()
        }
        try {
            check(finished) { "Timed out while finalizing the recording" }
            encoderError.get()?.let { throw IllegalStateException("Failed to encode recording", it) }
        } finally {
            muxerCoordinator.finish()
        }
        return outputFile
    }

    fun pause() {
        pauseStartedAtNs.compareAndSet(null, System.nanoTime())
    }

    fun resume() {
        closeCurrentPause()
        audioCapture?.discardBufferedAudio()
    }

    private fun closeCurrentPause() {
        val pausedAt = pauseStartedAtNs.getAndSet(null) ?: return
        accumulatedPauseDurationUs.updateAndGet { current ->
            current + (System.nanoTime() - pausedAt) / 1_000L
        }
    }

    private fun createVideoEncoder(): MediaCodec {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, estimateBitrate(width, height, frameRate))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
        return MediaCodec.createEncoderByType(VIDEO_MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun drainEncoder(encoder: MediaCodec, isVideo: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        try {
            while (true) {
                when (val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxerCoordinator.addTrack(encoder.outputFormat)
                    }
                    else -> if (outputBufferIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                            ?: error("Encoder output buffer $outputBufferIndex was null")
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            if (isVideo) {
                                val firstPresentationTimeUs = firstVideoPresentationTimeUs.updateAndGet { first ->
                                    first ?: bufferInfo.presentationTimeUs
                                } ?: bufferInfo.presentationTimeUs
                                val pauseDurationUs = accumulatedPauseDurationUs.get()
                                val adjustedTimeUs =
                                    (bufferInfo.presentationTimeUs - firstPresentationTimeUs - pauseDurationUs)
                                        .coerceAtLeast(0L)
                                val previousTimeUs = lastVideoPresentationTimeUs.get()
                                bufferInfo.presentationTimeUs = maxOf(adjustedTimeUs, previousTimeUs + 1L)
                                lastVideoPresentationTimeUs.set(bufferInfo.presentationTimeUs)
                            }
                            muxerCoordinator.write(trackIndex, encodedData, bufferInfo)
                        }
                        val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        if (endOfStream) break
                    }
                }
            }
        } catch (error: Exception) {
            encoderError.compareAndSet(null, error)
        } finally {
            if (trackIndex >= 0) {
                muxerCoordinator.markTrackFinished(trackIndex)
            }
            try {
                encoder.stop()
            } catch (error: IllegalStateException) {
                encoderError.compareAndSet(null, error)
            }
            try {
                encoder.release()
            } catch (error: IllegalStateException) {
                encoderError.compareAndSet(null, error)
            } finally {
                finishedLatch.countDown()
            }
        }
    }

    private inner class AudioCapture(
        source: RecordingAudioSource,
        projection: MediaProjection
    ) {
        val encoder: MediaCodec
        private val audioRecords: List<AudioRecord>
        private val queues: List<ArrayBlockingQueue<ShortArray>>
        private val isAudioStopping = AtomicBoolean(false)
        private val audioFeedThread: Thread

        init {
            val records = mutableListOf<AudioRecord>()
            if (source == RecordingAudioSource.DEVICE || source == RecordingAudioSource.DEVICE_AND_MICROPHONE) {
                records += createDeviceAudioRecord(projection)
            }
            if (source == RecordingAudioSource.MICROPHONE || source == RecordingAudioSource.DEVICE_AND_MICROPHONE) {
                records += createMicrophoneAudioRecord()
            }
            audioRecords = records
            queues = records.map { ArrayBlockingQueue(QUEUE_CAPACITY) }

            val format = MediaFormat.createAudioFormat(AUDIO_MIME_TYPE, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BLOCK_SAMPLES * 2)
            }
            encoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioFeedThread = Thread(::feedAudioEncoder, "AudioCaptureFeed")
        }

        fun start() {
            val startedRecords = mutableSetOf<AudioRecord>()
            try {
                audioRecords.forEachIndexed { index, record ->
                    check(record.state == AudioRecord.STATE_INITIALIZED) {
                        "The selected audio source is unavailable on this device"
                    }
                    record.startRecording()
                    startedRecords += record
                    Thread({ readAudio(record, queues[index]) }, "AudioCapture-$index").start()
                }
            } catch (error: Exception) {
                audioRecords.filterNot { it in startedRecords }.forEach { it.release() }
                stop()
                audioFeedThread.start()
                throw error
            }
            audioFeedThread.start()
        }

        fun stop() {
            if (isAudioStopping.compareAndSet(false, true)) {
                audioRecords.forEach { record ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        try {
                            record.stop()
                        } catch (error: IllegalStateException) {
                            encoderError.compareAndSet(null, error)
                        }
                    }
                }
            }
        }

        fun discardBufferedAudio() {
            queues.forEach { queue -> queue.clear() }
        }

        private fun readAudio(record: AudioRecord, queue: ArrayBlockingQueue<ShortArray>) {
            val samples = ShortArray(AUDIO_BLOCK_SAMPLES)
            try {
                while (!isAudioStopping.get()) {
                    val readCount = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    if (readCount <= 0) {
                        if (isAudioStopping.get()) break
                        encoderError.compareAndSet(
                            null,
                            IllegalStateException("Audio capture failed with code $readCount")
                        )
                        stop()
                        break
                    }
                    var signalDetected = false
                    for (index in 0 until readCount) {
                        if (kotlin.math.abs(samples[index].toInt()) > AUDIO_SIGNAL_THRESHOLD) {
                            signalDetected = true
                            break
                        }
                    }
                    if (signalDetected) {
                        audioSignalDetected.set(true)
                    }

                    val block = if (readCount == samples.size) {
                        samples.copyOf()
                    } else {
                        samples.copyOf(readCount)
                    }
                    if (!queue.offer(block)) {
                        queue.poll()
                        queue.offer(block)
                    }
                }
            } catch (error: Exception) {
                if (!isAudioStopping.get()) {
                    encoderError.compareAndSet(
                        null,
                        IllegalStateException("Audio capture failed", error)
                    )
                    stop()
                }
            } finally {
                record.release()
            }
        }

        private fun feedAudioEncoder() {
            var framePosition = 0L
            try {
                while (!isAudioStopping.get() || queues.any { it.isNotEmpty() }) {
                    val blocks = queues.map { it.poll(AUDIO_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                    if (blocks.all { it == null }) continue
                    if (pauseStartedAtNs.get() != null) continue

                    val samples = mixBlocks(blocks)
                    queueAudio(samples, framePosition * 1_000_000L / AUDIO_SAMPLE_RATE)
                    framePosition += samples.size
                }
                val inputIndex = waitForAudioInputBuffer()
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    framePosition * 1_000_000L / AUDIO_SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            } catch (error: Exception) {
                encoderError.compareAndSet(null, error)
                runCatching {
                    val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        encoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            framePosition * 1_000_000L / AUDIO_SAMPLE_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                }
            }
        }

        private fun mixBlocks(blocks: List<ShortArray?>): ShortArray {
            val blockSize = blocks.filterNotNull().minOf { it.size }
            return ShortArray(blockSize) { index ->
                val sample = blocks.sumOf { it?.get(index)?.toInt() ?: 0 }
                sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        private fun queueAudio(samples: ShortArray, presentationTimeUs: Long) {
            val inputIndex = waitForAudioInputBuffer()
            val input = encoder.getInputBuffer(inputIndex)
                ?: error("Audio encoder input buffer $inputIndex was null")
            input.clear()
            input.order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { input.putShort(it) }
            encoder.queueInputBuffer(
                inputIndex,
                0,
                samples.size * 2,
                presentationTimeUs,
                0
            )
        }

        private fun waitForAudioInputBuffer(): Int {
            while (true) {
                val inputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) return inputIndex
                check(!encoderError.get().let { it != null }) {
                    "Audio encoding stopped unexpectedly"
                }
            }
        }
    }

    private fun createDeviceAudioRecord(projection: MediaProjection): AudioRecord {
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .setBufferSizeInBytes(minimumAudioBufferBytes() * 2)
            .build()
    }

    private fun createMicrophoneAudioRecord(): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minimumAudioBufferBytes() * 2)
            .build()

    private fun minimumAudioBufferBytes(): Int {
        val minSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minSize > 0) { "This device does not support the selected audio format" }
        return maxOf(minSize, AUDIO_BLOCK_SAMPLES * 2)
    }

    private class MuxerCoordinator(
        private val muxer: MediaMuxer,
        private val expectedTracks: Int
    ) {
        private data class EncodedSample(
            val data: ByteArray,
            val presentationTimeUs: Long,
            val flags: Int
        )

        private val pendingSamples = mutableListOf<ArrayDeque<EncodedSample>>()
        private val finishedTracks = mutableSetOf<Int>()
        private var addedTracks = 0
        private var isStarted = false
        private var isFinished = false

        @Synchronized
        fun addTrack(format: MediaFormat): Int {
            check(!isStarted) { "Encoder format changed after muxer start" }
            val trackIndex = muxer.addTrack(format)
            pendingSamples.add(ArrayDeque())
            addedTracks++
            if (addedTracks == expectedTracks) {
                muxer.start()
                isStarted = true
                writeInterleavedSamples()
            }
            return trackIndex
        }

        @Synchronized
        fun write(trackIndex: Int, data: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
            check(!isFinished) { "Muxer is already finished" }
            val bytes = ByteArray(bufferInfo.size)
            val sampleData = data.duplicate()
            sampleData.position(bufferInfo.offset)
            sampleData.limit(bufferInfo.offset + bufferInfo.size)
            sampleData.get(bytes)
            pendingSamples[trackIndex].addLast(
                EncodedSample(bytes, bufferInfo.presentationTimeUs, bufferInfo.flags)
            )
            writeInterleavedSamples()
        }

        @Synchronized
        fun markTrackFinished(trackIndex: Int) {
            finishedTracks += trackIndex
            if (isStarted && !isFinished) {
                writeInterleavedSamples()
            }
        }

        private fun writeInterleavedSamples() {
            if (!isStarted || isFinished) return
            while (pendingSamples.indices.all { trackIndex ->
                    pendingSamples[trackIndex].isNotEmpty() || trackIndex in finishedTracks
                }
            ) {
                val nextTrack = pendingSamples.indices
                    .filter { pendingSamples[it].isNotEmpty() }
                    .minByOrNull { pendingSamples[it].first.presentationTimeUs }
                    ?: return
                val sample = pendingSamples[nextTrack].removeFirst()
                val info = MediaCodec.BufferInfo().apply {
                    set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                }
                val sampleBuffer = ByteBuffer.allocateDirect(sample.data.size)
                    .put(sample.data)
                    .apply { flip() }
                muxer.writeSampleData(nextTrack, sampleBuffer, info)
            }
        }

        @Synchronized
        fun finish() {
            if (isFinished) return
            if (isStarted) {
                finishedTracks.addAll(pendingSamples.indices)
                writeInterleavedSamples()
            }
            isFinished = true
            if (isStarted) muxer.stop()
            muxer.release()
        }
    }

    private fun createOutputFile(): File {
        val directory = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            OUTPUT_DIRECTORY
        ).apply { mkdirs() }
        return File(directory, "recording_${System.currentTimeMillis()}.mp4")
    }

    companion object {
        private const val VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_BLOCK_SAMPLES = 960
        private const val AUDIO_SIGNAL_THRESHOLD = 128
        private const val QUEUE_CAPACITY = 8
        private const val AUDIO_POLL_TIMEOUT_MS = 40L
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val STOP_TIMEOUT_SECONDS = 12L
        private const val DEFAULT_FRAME_RATE = 30
        private const val OUTPUT_DIRECTORY = "Recordings"

        private fun estimateBitrate(width: Int, height: Int, frameRate: Int): Int =
            (width.toLong() * height * frameRate / 4)
                .coerceIn(4_000_000L, 18_000_000L)
                .toInt()
    }
}
