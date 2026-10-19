package com.mlbb.highlight.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
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
        val autoRegion = automaticHudRegion(frame)
        val regions = buildList {
            add("calibrated" to hudRegion)
            if (autoRegion != hudRegion) add("viewport-top-right" to autoRegion)
        }
        val candidates = mutableListOf<Pair<String, KdaReading>>()
        val parsedCandidates = mutableListOf<KdaReading>()
        val observations = mutableListOf<OcrTextObservation>()
        val rejections = mutableListOf<String>()
        val rawTexts = mutableListOf<String>()

        for ((label, region) in regions) {
            val bounds = region.toPixelRect(frame.width, frame.height)
            if (bounds == null || bounds.isEmpty) {
                rejections += "$label ROI invalid for ${frame.width}x${frame.height} frame"
                continue
            }
            val crop = Bitmap.createBitmap(frame, bounds.left, bounds.top, bounds.width(), bounds.height())
            val enlarged = Bitmap.createScaledBitmap(
                crop,
                crop.width * KDA_CROP_SCALE,
                crop.height * KDA_CROP_SCALE,
                true
            )
            val cropInfo = KdaCrop(crop, bounds.left, bounds.top, KDA_CROP_SCALE.toFloat())
            val text = try {
                recognizer.process(InputImage.fromBitmap(enlarged, 0)).await()
            } finally {
                enlarged.recycle()
                crop.recycle()
            }
            val lines = text.textBlocks.flatMap { block -> block.lines }.map { it.text }
            rawTexts += "$label crop=${bounds.toShortString()} text=${lines.joinToString(" | ")}"
            observations += textObservations(text) { mapCropBoundsToFrame(it, cropInfo) }
            val tokens = extractNumberTokens(text) { mapCropBoundsToFrame(it, cropInfo) }
            val parsed = KdaSpatialParser.parseDetailed(tokens, frame.width, frame.height)
            parsedCandidates += parsed.readings
            val accepted = parsed.readings.filter { reading ->
                val insideCrop = bounds.contains(reading.bounds)
                val compactRow = reading.bounds.width() <= bounds.width() * MAX_ROW_WIDTH_OF_ROI &&
                    reading.bounds.height() <= bounds.height() * MAX_ROW_HEIGHT_OF_ROI
                if (!reading.isTripletNumericallyValid) {
                    rejections += "$label '${reading.rawText}' rejected: K/D/A has a non-numeric or implausible component"
                    false
                } else if (!insideCrop || !compactRow) {
                    rejections += "$label '${reading.rawText}' rejected: candidate geometry is outside the compact HUD row"
                    false
                } else {
                    true
                }
            }
            candidates += accepted.map { label to it }
            if (parsed.readings.isEmpty()) {
                rejections += parsed.rejectedReasons.map { "$label: $it" }
                rejections += "$label OCR text did not form a numerical K/D/A triplet"
            }
        }

        fun uniqueReadings(rows: List<KdaReading>): List<KdaReading> = rows.distinctBy { reading ->
            listOf(
                reading.kills,
                reading.deaths,
                reading.assists,
                reading.bounds.centerX() / DUPLICATE_POSITION_BUCKET_PX,
                reading.bounds.centerY() / DUPLICATE_POSITION_BUCKET_PX
            )
        }
        val calibratedCandidates = uniqueReadings(candidates.filter { it.first == "calibrated" }.map { it.second })
        val automaticCandidates = uniqueReadings(candidates.filter { it.first != "calibrated" }.map { it.second })
        val selectedCandidates = when {
            // The automatic ROI follows the active gameplay viewport. Prefer it
            // when an old manual calibration happens to contain unrelated values.
            automaticCandidates.size == 1 && calibratedCandidates.size == 1 &&
                !sameKda(automaticCandidates.single(), calibratedCandidates.single()) -> {
                rejections += "Calibrated ROI disagrees with viewport HUD; using viewport-validated K/D/A"
                automaticCandidates
            }
            automaticCandidates.size == 1 -> automaticCandidates
            calibratedCandidates.size == 1 && automaticCandidates.isEmpty() -> calibratedCandidates
            calibratedCandidates.size == 1 && automaticCandidates.size > 1 -> calibratedCandidates
            automaticCandidates.size == 1 && calibratedCandidates.size > 1 -> automaticCandidates
            calibratedCandidates.isNotEmpty() || automaticCandidates.isNotEmpty() -> {
                rejections += "Multiple numerical rows in the HUD ROI; waiting instead of guessing"
                emptyList()
            }
            else -> emptyList()
        }
        if (selectedCandidates.isEmpty() && rejections.isEmpty()) {
            rejections += "No numerical K/D/A row found in calibrated or viewport top-right HUD ROI"
        }
        return DetectionResult(
            confidence = selectedCandidates.maxOfOrNull(KdaReading::confidence) ?: 0f,
            kdaCandidates = selectedCandidates,
            textObservations = observations,
            rejectedDetections = rejections.distinct(),
            rawOcrText = rawTexts.joinToString("; "),
            parsedKdaCandidates = parsedCandidates.distinctBy { reading ->
                listOf(reading.kills, reading.deaths, reading.assists, reading.bounds.toShortString())
            }
        )
    }

    private fun sameKda(first: KdaReading, second: KdaReading): Boolean =
        first.kills == second.kills && first.deaths == second.deaths && first.assists == second.assists

    override fun close() {
        recognizer.close()
    }

    /** Find the gameplay viewport, then scan only its top-right K/D/A counter area. */
    private fun automaticHudRegion(frame: Bitmap): HudRegion {
        val viewport = activeViewportBounds(frame)
        val viewportWidth = viewport.width().coerceAtLeast(1)
        val viewportHeight = viewport.height().coerceAtLeast(1)
        val roiLeft = viewport.left + (viewportWidth * AUTO_ROI_LEFT_FRACTION).toInt()
        val roiRight = viewport.left + (viewportWidth * AUTO_ROI_RIGHT_FRACTION).toInt()
        val roiTopPx = viewport.top
        val roiBottomPx = (viewport.top + (viewportHeight * AUTO_ROI_HEIGHT_FRACTION).toInt())
            .coerceAtMost(viewport.bottom)
        return HudRegion(
            left = (roiLeft.toFloat() / frame.width).coerceIn(0f, 1f - HudRegion.MIN_REGION_SIZE),
            top = (roiTopPx.toFloat() / frame.height).coerceIn(0f, 1f - HudRegion.MIN_REGION_SIZE),
            right = (roiRight.toFloat() / frame.width).coerceIn(
                (roiLeft.toFloat() / frame.width) + HudRegion.MIN_REGION_SIZE,
                1f
            ),
            bottom = (roiBottomPx.toFloat() / frame.height).coerceIn(
                (roiTopPx.toFloat() / frame.height) + HudRegion.MIN_REGION_SIZE,
                1f
            )
        )
    }

    /** Exclude black letterboxing so ROI coordinates follow the actual gameplay viewport. */
    private fun activeViewportBounds(frame: Bitmap): Rect {
        val sampleStepX = (frame.width / 64).coerceAtLeast(1)
        val sampleStepY = (frame.height / 64).coerceAtLeast(1)
        val rowStep = (frame.height / 240).coerceAtLeast(1)
        val colStep = (frame.width / 240).coerceAtLeast(1)
        val litRows = mutableListOf<Int>()
        var y = 0
        while (y < frame.height) {
            var lit = 0
            var sampled = 0
            var x = 0
            while (x < frame.width) {
                val color = frame.getPixel(x, y)
                val brightness = maxOf(
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color)
                )
                if (brightness > BLACK_BAR_BRIGHTNESS) lit += 1
                sampled += 1
                x += sampleStepX
            }
            if (sampled > 0 && lit.toFloat() / sampled >= MIN_LIT_ROW_FRACTION) {
                litRows += y
            }
            y += rowStep
        }
        val litColumns = mutableListOf<Int>()
        var x = 0
        while (x < frame.width) {
            var lit = 0
            var sampled = 0
            var sampleY = 0
            while (sampleY < frame.height) {
                val color = frame.getPixel(x, sampleY)
                val brightness = maxOf(
                    android.graphics.Color.red(color),
                    android.graphics.Color.green(color),
                    android.graphics.Color.blue(color)
                )
                if (brightness > BLACK_BAR_BRIGHTNESS) lit += 1
                sampled += 1
                sampleY += sampleStepY
            }
            if (sampled > 0 && lit.toFloat() / sampled >= MIN_LIT_ROW_FRACTION) {
                litColumns += x
            }
            x += colStep
        }
        val activeVerticalSpan = inferActiveSpanBounds(
            samples = litRows,
            dimension = frame.height,
            sampleStep = rowStep,
            minimumFraction = MIN_ACTIVE_VIEWPORT_HEIGHT_FRACTION,
            minimumSamples = MIN_ACTIVE_ROW_SAMPLES
        )
        val top = activeVerticalSpan?.first ?: 0
        val bottom = activeVerticalSpan?.second ?: frame.height
        val left = if (litColumns.size >= MIN_ACTIVE_ROW_SAMPLES &&
            litColumns.last() - litColumns.first() >= frame.width * MIN_ACTIVE_VIEWPORT_FRACTION
        ) (litColumns.first() - colStep).coerceAtLeast(0) else 0
        val right = if (left == 0 && (litColumns.size < MIN_ACTIVE_ROW_SAMPLES ||
                litColumns.last() - litColumns.first() < frame.width * MIN_ACTIVE_VIEWPORT_FRACTION)
        ) frame.width else (litColumns.last() + colStep + 1).coerceAtMost(frame.width)
        return Rect(left, top, right, bottom)
    }

    private fun mapCropBoundsToFrame(bounds: Rect, crop: KdaCrop): Rect {
        val scale = crop.scale
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
                val lineToken = line.boundingBox?.let { raw ->
                    val mapped = mapBounds(raw)
                    KdaNumberToken(
                        text = line.text,
                        bounds = mapped,
                        confidence = line.confidence ?: elements.mapNotNull { it.confidence }
                            .average().toFloat().takeIf { it.isFinite() } ?: 0.6f,
                        boxLeft = mapped.left,
                        boxTop = mapped.top,
                        boxRight = mapped.right,
                        boxBottom = mapped.bottom
                    )
                }
                listOfNotNull(lineToken) + elements.mapNotNull { element ->
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
        const val KDA_CROP_SCALE = 4
        const val AUTO_ROI_LEFT_FRACTION = 0.77f
        const val AUTO_ROI_RIGHT_FRACTION = 0.94f
        const val AUTO_ROI_HEIGHT_FRACTION = 0.16f
        const val MAX_ROW_WIDTH_OF_ROI = 0.98f
        const val MAX_ROW_HEIGHT_OF_ROI = 0.80f
        const val DUPLICATE_POSITION_BUCKET_PX = 8
        const val BLACK_BAR_BRIGHTNESS = 22
        const val MIN_LIT_ROW_FRACTION = 0.08f
        const val MIN_ACTIVE_ROW_SAMPLES = 16
        const val MIN_ACTIVE_VIEWPORT_FRACTION = 0.35f
        const val MIN_ACTIVE_VIEWPORT_HEIGHT_FRACTION = 0.10f
    }

    private data class KdaCrop(
        val bitmap: Bitmap,
        val left: Int,
        val top: Int,
        val scale: Float
    )
}

internal fun inferActiveSpanBounds(
    samples: List<Int>,
    dimension: Int,
    sampleStep: Int,
    minimumFraction: Float,
    minimumSamples: Int
): Pair<Int, Int>? {
    if (samples.size < minimumSamples || dimension <= 0 || samples.isEmpty() ||
        samples.last() - samples.first() < dimension * minimumFraction
    ) return null
    return (samples.first() - sampleStep).coerceAtLeast(0) to
        (samples.last() + sampleStep + 1).coerceAtMost(dimension)
}

data class DetectionResult(
    val confidence: Float,
    val kdaCandidates: List<KdaReading> = emptyList(),
    val textObservations: List<OcrTextObservation> = emptyList(),
    val rejectedDetections: List<String> = emptyList(),
    val visualCues: List<HudVisualCue> = emptyList(),
    val rawOcrText: String = "",
    /** All parser outputs, including candidates later rejected by ROI arbitration. */
    val parsedKdaCandidates: List<KdaReading> = kdaCandidates
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
    val isFirstDigitValid: Boolean = true,
    val isTripletNumericallyValid: Boolean = true
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
    private const val MAX_NUMBER_GAP_TOKEN_HEIGHTS = 2.5f
    private const val MAX_KDA_COMPONENT = 99

    fun normalizeFirstDigit(text: String): Int? {
        val trimmed = text.trim().replace(Regex("(?i)^x(?=[0-9])"), "")
        if (trimmed.isEmpty()) return null
        // The HUD ROI and three-number row geometry provide context; retain
        // legitimate two-digit counter values instead of applying a low cap.
        if (trimmed.all { it in '0'..'9' }) {
            return trimmed.toIntOrNull()?.takeIf { it <= MAX_KDA_COMPONENT }
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
            val iconStrippedText = token.text
                .replace(Regex("(?i)^\\s*x(?=\\s*[0-9])"), "")
            val compactInput = iconStrippedText
                .filterNot(Char::isWhitespace)
            val exactMatch = sequenceOf(
                iconStrippedText to tripletPattern.find(iconStrippedText),
                iconStrippedText to spaceSeparatedTripletPattern.find(iconStrippedText),
                token.text to tripletPattern.find(token.text),
                token.text to spaceSeparatedTripletPattern.find(token.text),
                compactInput to compactTripletPattern.find(compactInput)
            ).firstOrNull { (source, candidate) ->
                candidate != null && source.substring(0, candidate.range.first).isBlank() &&
                    source.substring(candidate.range.last + 1).isBlank()
            } ?: return@mapNotNull null
            val match = exactMatch.second ?: return@mapNotNull null
            val rawPart1 = match.groupValues[1]
            val rawPart2 = match.groupValues[2]
            val rawPart3 = match.groupValues[3]
            val killsDigit = normalizeFirstDigit(rawPart1)
            val deaths = rawPart2.toIntOrNull()
            val assists = rawPart3.toIntOrNull()
            val firstChar = rawPart1.trim()
            val rawText = token.text
            createReading(
                kills = killsDigit ?: -1,
                deaths = deaths ?: -1,
                assists = assists ?: -1,
                tokens = listOf(token),
                confidence = token.confidence,
                rawText = rawText,
                firstChar = firstChar,
                isFirstDigitValid = killsDigit != null,
                isTripletNumericallyValid = killsDigit != null && deaths != null && assists != null
            )
        }
        val spatialTriplets = validTokens.mapNotNull { token ->
            val values = tokenPattern.findAll(token.text).toList()
            if (values.size != 3 || tripletPattern.containsMatchIn(token.text) || spaceSeparatedTripletPattern.containsMatchIn(token.text)) {
                return@mapNotNull null
            }
            val rawPart1 = values[0].value
            val killsDigit = normalizeFirstDigit(rawPart1)
            val deaths = values[1].value.toIntOrNull()
            val assists = values[2].value.toIntOrNull()
            createReading(
                kills = killsDigit ?: -1,
                deaths = deaths ?: -1,
                assists = assists ?: -1,
                tokens = listOf(token),
                confidence = token.confidence,
                rawText = token.text,
                firstChar = rawPart1.trim(),
                isFirstDigitValid = killsDigit != null,
                isTripletNumericallyValid = killsDigit != null && deaths != null && assists != null
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
                    token.boxLeft - previous.boxRight <= minOf(
                        frameWidth * MAX_NUMBER_GAP_FRACTION,
                        maxOf(
                            token.boxBottom - token.boxTop,
                            previous.boxBottom - previous.boxTop
                        ) * MAX_NUMBER_GAP_TOKEN_HEIGHTS
                    )
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
                val deaths = segment[1].text.trim().toIntOrNull()
                val assists = segment[2].text.trim().toIntOrNull()
                val confidence = segment.map(KdaNumberToken::confidence).average().toFloat()
                val rawText = segment.joinToString(" ") { it.text }
                createReading(
                    kills = killsDigit ?: -1,
                    deaths = deaths ?: -1,
                    assists = assists ?: -1,
                    tokens = segment,
                    confidence = confidence,
                    rawText = rawText,
                    firstChar = rawPart1,
                    isFirstDigitValid = killsDigit != null,
                    isTripletNumericallyValid = killsDigit != null && deaths != null && assists != null
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
        isFirstDigitValid: Boolean = true,
        isTripletNumericallyValid: Boolean = isFirstDigitValid
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
            isFirstDigitValid = isFirstDigitValid,
            isTripletNumericallyValid = isTripletNumericallyValid
        )
    }
}
