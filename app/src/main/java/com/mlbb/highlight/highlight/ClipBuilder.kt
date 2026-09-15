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
        outputDirectory.mkdirs()
        val outputFile = File(outputDirectory, outputName)

        if (segments.isEmpty()) {
            throw IllegalArgumentException("No segments provided for highlight creation")
        }

        val ordered = segments.sortedBy { it.createdAtMs }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var videoTrackIndex = -1
        var muxerStarted = false

        try {
            for (segment in ordered) {
                val extractor = MediaExtractor()
                extractor.setDataSource(segment.file.absolutePath)
                val trackIndex = findVideoTrack(extractor)
                if (trackIndex == -1) {
                    extractor.release()
                    continue
                }

                val format = extractor.getTrackFormat(trackIndex)
                extractor.selectTrack(trackIndex)

                if (muxerTrackIndex == -1) {
                    muxerTrackIndex = muxer.addTrack(format)
                    muxer.start()
                    muxerStarted = true
                }

                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    val buffer = ByteBuffer.allocateDirect(1024 * 64)
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    buffer.position(0)
                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }

                extractor.release()
            }
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
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

}
