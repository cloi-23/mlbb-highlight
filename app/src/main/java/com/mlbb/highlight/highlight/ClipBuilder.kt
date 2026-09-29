package com.mlbb.highlight.highlight

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mlbb.highlight.recording.SegmentFile
import java.io.File
import java.nio.ByteBuffer

class ClipBuilder(
    private val outputDirectory: File
) {
    fun buildFromSegments(segments: List<SegmentFile>, outputName: String): File {
        val startMs = segments.minOfOrNull { it.createdAtMs } ?: 0L
        val endMs = segments.maxOfOrNull { it.endTimeMs } ?: startMs
        return buildWindowFromSegments(segments, outputName, startMs, endMs)
    }

    fun buildWindowFromSegments(
        segments: List<SegmentFile>,
        outputName: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): File {
        outputDirectory.mkdirs()
        val outputFile = File(outputDirectory, outputName)

        if (segments.isEmpty()) {
            throw IllegalArgumentException("No segments provided for highlight creation")
        }

        val ordered = segments.sortedBy { it.createdAtMs }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false
        var nextPresentationTimeUs = 0L
        val buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE)
        var wroteSamples = false

        try {
            for (segment in ordered) {
                val segmentStartOffsetUs = ((windowStartMs - segment.createdAtMs).coerceAtLeast(0L)) * 1_000L
                val segmentEndOffsetUs =
                    ((windowEndMs - segment.createdAtMs).coerceIn(0L, segment.durationMs)) * 1_000L
                if (segmentEndOffsetUs <= segmentStartOffsetUs) continue

                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.file.absolutePath)
                    val trackIndex = findVideoTrack(extractor)
                    if (trackIndex == -1) {
                        continue
                    }

                    val format = extractor.getTrackFormat(trackIndex)
                    extractor.selectTrack(trackIndex)
                    val firstFileSampleTimeUs = extractor.sampleTime
                    if (firstFileSampleTimeUs < 0L) continue

                    val trimStartSampleTimeUs = firstFileSampleTimeUs + segmentStartOffsetUs
                    val trimEndSampleTimeUs = firstFileSampleTimeUs + segmentEndOffsetUs
                    var writeStartSampleTimeUs = trimStartSampleTimeUs
                    if (segmentStartOffsetUs > 0L) {
                        extractor.seekTo(trimStartSampleTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        if (extractor.sampleTime >= 0L) {
                            writeStartSampleTimeUs = extractor.sampleTime
                        }
                    }

                    if (muxerTrackIndex == -1) {
                        muxerTrackIndex = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    }

                    val bufferInfo = MediaCodec.BufferInfo()
                    var firstSegmentSampleTimeUs = -1L
                    var lastWrittenPresentationTimeUs = nextPresentationTimeUs
                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < writeStartSampleTimeUs) {
                            extractor.advance()
                            continue
                        }
                        if (sampleTimeUs > trimEndSampleTimeUs) break

                        if (firstSegmentSampleTimeUs < 0L) {
                            firstSegmentSampleTimeUs = sampleTimeUs
                        }

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs =
                            nextPresentationTimeUs + (sampleTimeUs - firstSegmentSampleTimeUs)
                        bufferInfo.flags = extractor.sampleFlags
                        buffer.position(0)
                        buffer.limit(sampleSize)
                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        lastWrittenPresentationTimeUs = bufferInfo.presentationTimeUs
                        wroteSamples = true
                        extractor.advance()
                    }

                    if (firstSegmentSampleTimeUs >= 0L) {
                        nextPresentationTimeUs = lastWrittenPresentationTimeUs + FRAME_DURATION_US
                    }
                } finally {
                    extractor.release()
                }
            }
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }

        if (!wroteSamples) {
            outputFile.delete()
            throw IllegalStateException("No video samples found for highlight window")
        }

        return outputFile
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    companion object {
        private const val SAMPLE_BUFFER_SIZE = 1024 * 1024
        private const val FRAME_DURATION_US = 33_333L
    }

}
