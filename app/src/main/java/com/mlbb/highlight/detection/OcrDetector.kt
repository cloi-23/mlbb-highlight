package com.mlbb.highlight.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mlbb.highlight.highlight.HighlightEventType
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface HudSignalDetector : Closeable {
    suspend fun analyze(frame: Bitmap, presentationTimeMs: Long): DetectionResult
}

class OcrDetector(
    private val hudRegion: HudRegion = HudRegion.DEFAULT
) : HudSignalDetector {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun analyze(frame: Bitmap, presentationTimeMs: Long): DetectionResult {
        val fullFrameText = recognizer.process(InputImage.fromBitmap(frame, 0)).await()
        val kdaCrop = cropKdaHud(frame) ?: return DetectionResult(
            eventType = classifyBanner(fullFrameText),
            confidence = 0f,
            textObservations = textObservations(fullFrameText)
        )
        val enlargedKdaCrop = Bitmap.createScaledBitmap(
            kdaCrop.bitmap,
            kdaCrop.bitmap.width * KDA_CROP_SCALE,
            kdaCrop.bitmap.height * KDA_CROP_SCALE,
            true
        )
        val kdaText = try {
            recognizer.process(InputImage.fromBitmap(enlargedKdaCrop, 0)).await()
        } finally {
            enlargedKdaCrop.recycle()
            kdaCrop.bitmap.recycle()
        }
        val focusedHudTokens = extractNumberTokens(kdaText) { bounds ->
            mapCropBoundsToFrame(bounds, kdaCrop)
        }
        var parseResult = KdaSpatialParser.parseDetailed(
            tokens = focusedHudTokens,
            frameWidth = frame.width,
            frameHeight = frame.height
        )
        if (parseResult.readings.isEmpty()) {
            val upperHudTokens = extractNumberTokens(fullFrameText) { Rect(it) }
                .filter { token ->
                    token.boxTop < frame.height * 0.30f &&
                        token.boxLeft > frame.width * 0.50f
                }
            if (upperHudTokens.isNotEmpty()) {
                val fallbackResult = KdaSpatialParser.parseDetailed(
                    tokens = upperHudTokens,
                    frameWidth = frame.width,
                    frameHeight = frame.height
                )
                if (fallbackResult.readings.isNotEmpty()) {
                    parseResult = fallbackResult
                }
            }
        }
        val lines = fullFrameText.textBlocks.flatMap { it.lines }
        val candidates = lines.mapNotNull { line ->
            val eventType = classifyBanner(line.text) ?: return@mapNotNull null
            val confidence = (line.confidence ?: 0.5f).coerceIn(0f, 1f)
            if (confidence < MIN_LINE_CONFIDENCE) return@mapNotNull null
            DetectionResult(eventType, confidence, line.text)
        }
        val banner = candidates.maxByOrNull(DetectionResult::confidence)
        return DetectionResult(
            eventType = banner?.eventType,
            confidence = banner?.confidence ?: 0f,
            recognizedText = banner?.recognizedText,
            kdaCandidates = parseResult.readings,
            textObservations = textObservations(fullFrameText) +
                textObservations(kdaText) { bounds -> mapCropBoundsToFrame(bounds, kdaCrop) },
            rejectedDetections = parseResult.rejectedReasons
        )
    }

    override fun close() {
        recognizer.close()
    }

    private fun classifyBanner(text: String): HighlightEventType? {
        val normalized = text.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            containsPhrase(normalized, "SAVAGE") -> HighlightEventType.SAVAGE
            containsPhrase(normalized, "MANIAC") -> HighlightEventType.MANIAC
            containsPhrase(normalized, "TRIPLE KILL") -> HighlightEventType.TRIPLE_KILL
            containsPhrase(normalized, "DOUBLE KILL") -> HighlightEventType.DOUBLE_KILL
            containsPhrase(normalized, "FIRST BLOOD") -> HighlightEventType.FIRST_BLOOD
            containsPhrase(normalized, "VICTORY") -> HighlightEventType.VICTORY
            containsPhrase(normalized, "DEFEAT") -> HighlightEventType.DEFEAT
            containsPhrase(normalized, "YOU HAVE SLAIN") ||
                containsPhrase(normalized, "AN ENEMY HAS BEEN SLAIN") ||
                containsPhrase(normalized, "HAS SLAIN") ||
                containsPhrase(normalized, "KILLING SPREE") ||
                containsPhrase(normalized, "MEGA KILL") ||
                containsPhrase(normalized, "UNSTOPPABLE") ||
                containsPhrase(normalized, "MONSTER KILL") ||
                containsPhrase(normalized, "GODLIKE") ||
                containsPhrase(normalized, "LEGENDARY") ||
                containsPhrase(normalized, "WIPEOUT") -> HighlightEventType.KILL
            else -> null
        }
    }

    private fun cropKdaHud(frame: Bitmap): KdaCrop? {
        val bounds = hudRegion.toPixelRect(frame.width, frame.height) ?: return null
        return KdaCrop(
            bitmap = Bitmap.createBitmap(frame, bounds.left, bounds.top, bounds.width(), bounds.height()),
            left = bounds.left,
            top = bounds.top
        )
    }

    private fun mapCropBoundsToFrame(bounds: Rect, crop: KdaCrop): Rect {
        val scale = KDA_CROP_SCALE.toFloat()
        return Rect(
            crop.left + (bounds.left / scale).toInt(),
            crop.top + (bounds.top / scale).toInt(),
            crop.left + kotlin.math.ceil(bounds.right / scale).toInt(),
            crop.top + kotlin.math.ceil(bounds.bottom / scale).toInt()
        )
    }

    private fun extractNumberTokens(
        text: com.google.mlkit.vision.text.Text,
        mapBounds: (Rect) -> Rect
    ): List<KdaNumberToken> =
        text.textBlocks.flatMap { it.lines }.flatMap { line ->
            val elements = line.elements
            if (elements.isEmpty()) {
                line.boundingBox?.let { raw ->
                    val mapped = mapBounds(raw)
                    listOf(
                        KdaNumberToken(
                            text = line.text,
                            bounds = mapped,
                            confidence = line.confidence ?: 0f,
                            boxLeft = mapped.left,
                            boxTop = mapped.top,
                            boxRight = mapped.right,
                            boxBottom = mapped.bottom
                        )
                    )
                }.orEmpty()
            } else {
                elements.mapNotNull { element ->
                    val raw = element.boundingBox ?: return@mapNotNull null
                    val mapped = mapBounds(raw)
                    KdaNumberToken(
                        text = element.text,
                        bounds = mapped,
                        confidence = element.confidence ?: line.confidence ?: 0f,
                        boxLeft = mapped.left,
                        boxTop = mapped.top,
                        boxRight = mapped.right,
                        boxBottom = mapped.bottom
                    )
                }
            }
        }

    private fun classifyBanner(text: com.google.mlkit.vision.text.Text): HighlightEventType? =
        text.textBlocks.asSequence()
            .flatMap { it.lines.asSequence() }
            .mapNotNull { line ->
                val type = classifyBanner(line.text) ?: return@mapNotNull null
                if ((line.confidence ?: 0f) < MIN_LINE_CONFIDENCE) return@mapNotNull null
                type to (line.confidence ?: 0f)
            }
            .maxByOrNull { it.second }
            ?.first

    private fun textObservations(
        text: com.google.mlkit.vision.text.Text,
        mapBounds: (Rect) -> Rect = { Rect(it) }
    ): List<OcrTextObservation> =
        text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                OcrTextObservation(
                    text = line.text,
                    confidence = line.confidence ?: 0f,
                    bounds = mapBounds(bounds).toShortString()
                )
            }
        }

    private fun containsPhrase(text: String, phrase: String): Boolean =
        Regex("(^| )${Regex.escape(phrase)}( |$)").containsMatchIn(text)

    private suspend fun <T> Task<T>.await(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            addOnCanceledListener {
                continuation.cancel()
            }
        }

    private companion object {
        const val MIN_LINE_CONFIDENCE = 0.65f
        const val KDA_CROP_SCALE = 4
    }

    private data class KdaCrop(
        val bitmap: Bitmap,
        val left: Int,
        val top: Int
    )
}

data class DetectionResult(
    val eventType: HighlightEventType?,
    val confidence: Float,
    val recognizedText: String? = null,
    val kdaCandidates: List<KdaReading> = emptyList(),
    val textObservations: List<OcrTextObservation> = emptyList(),
    val rejectedDetections: List<String> = emptyList(),
    val visualCues: List<HudVisualCue> = emptyList()
)

data class HudVisualCue(
    val label: String,
    val bounds: Rect,
    val confidence: Float,
    val detectorSource: String
)

/**
 * Interface for modular HUD visual signal detectors (e.g. hero icon recognition,
 * kill streak icon detectors).
 *
 * NOTE: Reliable recognition of MLBB HUD icons requires verified template assets or trained ML models
 * with labeled MLBB gameplay data. Generic edge heuristics or unverified matching alone cannot
 * reliably recognize MLBB icons across diverse resolutions, skin variants, and compression artifacts.
 * This interface establishes the detection contract so validated detectors can be plugged in
 * without modifying the video editor or timeline pipeline.
 */
interface HudVisualDetector : Closeable {
    val detectorName: String
    suspend fun detectVisualCues(frame: Bitmap, presentationTimeMs: Long): List<HudVisualCue>
}

/**
 * Composite detector that coordinates OCR with optional visual cue detectors.
 */
class CompositeHudDetector(
    private val ocrDetector: OcrDetector,
    private val visualDetectors: List<HudVisualDetector> = emptyList()
) : HudSignalDetector {
    override suspend fun analyze(frame: Bitmap, presentationTimeMs: Long): DetectionResult {
        val ocrResult = ocrDetector.analyze(frame, presentationTimeMs)
        if (visualDetectors.isEmpty()) {
            return ocrResult
        }
        val cues = visualDetectors.flatMap { it.detectVisualCues(frame, presentationTimeMs) }
        return ocrResult.copy(visualCues = cues)
    }

    override fun close() {
        ocrDetector.close()
        visualDetectors.forEach { it.close() }
    }
}

data class OcrTextObservation(
    val text: String,
    val confidence: Float,
    val bounds: String
)

data class KdaParseResult(
    val readings: List<KdaReading>,
    val rejectedReasons: List<String>
)

data class KdaReading(
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val bounds: Rect,
    val confidence: Float,
    val boxLeft: Int = bounds.left,
    val boxTop: Int = bounds.top,
    val boxRight: Int = bounds.right,
    val boxBottom: Int = bounds.bottom,
    val rawText: String = "",
    val firstChar: String = "",
    val isFirstDigitValid: Boolean = true
)

data class KdaNumberToken(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
    val boxLeft: Int = bounds.left,
    val boxTop: Int = bounds.top,
    val boxRight: Int = bounds.right,
    val boxBottom: Int = bounds.bottom
)

object KdaSpatialParser {
    private val tripletPattern = Regex(
        """(?<![A-Za-z0-9])([A-Za-z0-9]{1,2})\s*[/|\\:\-–—.]\s*([A-Za-z0-9]{1,2})\s*[/|\\:\-–—.]\s*([A-Za-z0-9]{1,2})(?![A-Za-z0-9])"""
    )
    private val spaceSeparatedTripletPattern = Regex(
        """(?<![A-Za-z0-9])([A-Za-z0-9]{1,2})\s+([A-Za-z0-9]{1,2})\s+([A-Za-z0-9]{1,2})(?![A-Za-z0-9])"""
    )
    // ML Kit sometimes drops the tiny separators between single digit K/D/A values.
    // Only accept exactly three digits here; longer numbers remain ambiguous.
    private val compactTripletPattern = Regex("(?<![A-Za-z0-9])([0-9])([0-9])([0-9])(?![A-Za-z0-9])")
    private val tokenPattern = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9]{1,2}(?![A-Za-z0-9])""")
    private const val MIN_CONFIDENCE = 0.45f
    private const val SAME_ROW_HEIGHT_FRACTION = 0.035f
    private const val MAX_NUMBER_GAP_FRACTION = 0.12f
    private const val MAX_PLAUSIBLE_KILLS = 30

    fun normalizeFirstDigit(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // Keep valid double digit game totals, but reject implausible merged
        // OCR values such as 48 from a misread K/D/A row.
        if (trimmed.all { it in '0'..'9' }) {
            return trimmed.toIntOrNull()?.takeIf { it <= MAX_PLAUSIBLE_KILLS }
        }
        return null
    }

    fun normalizeOcrText(text: String): String = text.trim()

    fun parse(
        tokens: List<KdaNumberToken>,
        frameWidth: Int,
        frameHeight: Int
    ): List<KdaReading> = parseDetailed(tokens, frameWidth, frameHeight).readings

    fun parseDetailed(
        tokens: List<KdaNumberToken>,
        frameWidth: Int,
        frameHeight: Int
    ): KdaParseResult {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return KdaParseResult(emptyList(), listOf("Invalid frame dimensions"))
        }
        val lowConfidenceCount = tokens.count {
            it.confidence < MIN_CONFIDENCE || isBoxEmpty(it)
        }
        val validTokens = tokens
            .filter { it.confidence >= MIN_CONFIDENCE && !isBoxEmpty(it) }
            .map { it.copy(text = normalizeOcrText(it.text)) }
        val directTriplets = validTokens.mapNotNull { token ->
            val match = tripletPattern.find(token.text)
                ?: spaceSeparatedTripletPattern.find(token.text)
                ?: compactTripletPattern.find(token.text)
                ?: return@mapNotNull null
            val rawPart1 = match.groupValues[1]
            val rawPart2 = match.groupValues[2]
            val rawPart3 = match.groupValues[3]
            val killsDigit = normalizeFirstDigit(rawPart1)
            val deaths = rawPart2.toIntOrNull() ?: 0
            val assists = rawPart3.toIntOrNull() ?: 0
            val firstChar = rawPart1.trim()
            val rawText = token.text
            createReading(
                kills = killsDigit ?: -1,
                deaths = deaths,
                assists = assists,
                tokens = listOf(token),
                confidence = token.confidence,
                rawText = rawText,
                firstChar = firstChar,
                isFirstDigitValid = killsDigit != null
            )
        }
        val spatialTriplets = validTokens.mapNotNull { token ->
            val values = tokenPattern.findAll(token.text).toList()
            if (values.size != 3 || tripletPattern.containsMatchIn(token.text) || spaceSeparatedTripletPattern.containsMatchIn(token.text)) {
                return@mapNotNull null
            }
            val rawPart1 = values[0].value
            val killsDigit = normalizeFirstDigit(rawPart1)
            val deaths = values[1].value.toIntOrNull() ?: 0
            val assists = values[2].value.toIntOrNull() ?: 0
            createReading(
                kills = killsDigit ?: -1,
                deaths = deaths,
                assists = assists,
                tokens = listOf(token),
                confidence = token.confidence,
                rawText = token.text,
                firstChar = rawPart1.trim(),
                isFirstDigitValid = killsDigit != null
            )
        }
        val individualTokens = validTokens.flatMap { token ->
            val matches = tokenPattern.findAll(token.text).toList()
            when {
                matches.size == 3 -> listOf(
                    token.copy(text = matches.joinToString(" ") { it.value })
                )
                matches.size == 1 -> listOf(token.copy(text = matches.single().value))
                else -> emptyList()
            }
        }.filter { tokenPattern.matches(it.text) }
        val grouped = individualTokens
            .sortedBy { boxCenterY(it) }
            .fold(mutableListOf<MutableList<KdaNumberToken>>()) { rows, token ->
                val row = rows.lastOrNull()?.takeIf { existing ->
                    val centerY = existing.map { boxCenterY(it) }.average().toFloat()
                    kotlin.math.abs(boxCenterY(token) - centerY) <=
                        frameHeight * SAME_ROW_HEIGHT_FRACTION
                }
                if (row == null) rows += mutableListOf(token) else row += token
                rows
            }
        val fragmentedTriplets = grouped.flatMap { row ->
            val ordered = row.sortedBy { it.boxLeft }
            val segments = ordered.fold(mutableListOf<MutableList<KdaNumberToken>>()) { result, token ->
                val segment = result.lastOrNull()
                val previous = segment?.lastOrNull()
                if (
                    previous == null ||
                    token.boxLeft - previous.boxRight <=
                    frameWidth * MAX_NUMBER_GAP_FRACTION
                ) {
                    if (segment == null) result += mutableListOf(token) else segment += token
                } else {
                    result += mutableListOf(token)
                }
                result
            }
            segments.mapNotNull { segment ->
                if (segment.size != 3) return@mapNotNull null
                val rawPart1 = segment[0].text.trim()
                val killsDigit = normalizeFirstDigit(rawPart1)
                val deaths = segment[1].text.trim().toIntOrNull() ?: 0
                val assists = segment[2].text.trim().toIntOrNull() ?: 0
                val confidence = segment.map(KdaNumberToken::confidence).average().toFloat()
                val rawText = segment.joinToString(" ") { it.text }
                createReading(
                    kills = killsDigit ?: -1,
                    deaths = deaths,
                    assists = assists,
                    tokens = segment,
                    confidence = confidence,
                    rawText = rawText,
                    firstChar = rawPart1,
                    isFirstDigitValid = killsDigit != null
                )
            }
        }
        val readings = (directTriplets + spatialTriplets + fragmentedTriplets)
            .distinctBy { reading ->
                listOf(
                    reading.kills,
                    reading.deaths,
                    reading.assists,
                    reading.boxLeft / 8,
                    reading.boxTop / 8
                )
            }
        val rejections = buildList {
            if (lowConfidenceCount > 0) {
                add("$lowConfidenceCount OCR token(s) rejected: confidence below $MIN_CONFIDENCE or empty bounds")
            }
            if (readings.isEmpty()) {
                add("No three-number K/D/A row assembled inside calibrated HUD region")
            }
        }
        return KdaParseResult(readings, rejections)
    }

    private fun isBoxEmpty(token: KdaNumberToken): Boolean =
        token.boxRight <= token.boxLeft || token.boxBottom <= token.boxTop

    private fun boxCenterY(token: KdaNumberToken): Float =
        (token.boxTop + token.boxBottom) / 2f

    private fun createReading(
        kills: Int,
        deaths: Int,
        assists: Int,
        tokens: List<KdaNumberToken>,
        confidence: Float,
        rawText: String = "",
        firstChar: String = "",
        isFirstDigitValid: Boolean = true
    ): KdaReading? {
        if (tokens.isEmpty()) return null
        val first = tokens.first()
        var minLeft = first.boxLeft
        var minTop = first.boxTop
        var maxRight = first.boxRight
        var maxBottom = first.boxBottom
        for (i in 1 until tokens.size) {
            val b = tokens[i]
            if (b.boxLeft < minLeft) minLeft = b.boxLeft
            if (b.boxTop < minTop) minTop = b.boxTop
            if (b.boxRight > maxRight) maxRight = b.boxRight
            if (b.boxBottom > maxBottom) maxBottom = b.boxBottom
        }
        return KdaReading(
            kills = kills,
            deaths = deaths,
            assists = assists,
            bounds = Rect(minLeft, minTop, maxRight, maxBottom),
            confidence = confidence,
            boxLeft = minLeft,
            boxTop = minTop,
            boxRight = maxRight,
            boxBottom = maxBottom,
            rawText = rawText,
            firstChar = firstChar,
            isFirstDigitValid = isFirstDigitValid
        )
    }
}
