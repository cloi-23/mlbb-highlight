package com.mlbb.highlight.detection

class OcrDetector {
    fun sampleFrame(frame: ByteArray): DetectionResult {
        // placeholder for future OCR logic
        return DetectionResult(eventName = null, confidence = 0f)
    }
}

data class DetectionResult(
    val eventName: String?,
    val confidence: Float
)
