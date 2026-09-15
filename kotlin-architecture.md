# Kotlin architecture for the MLBB highlight app

This is a practical file-by-file blueprint for the MVP: a screen-recording app with a rolling replay buffer, manual highlight trigger, and saved MP4 highlights.

## Recommended package structure

```text
app/
  src/
    main/
      java/
        com/
          mlbbhighlight/
            App.kt
            MainActivity.kt
            MainViewModel.kt

            ui/
              HomeScreen.kt
              SettingsScreen.kt
              HighlightsScreen.kt
              components/
                RecordingButton.kt
                HighlightFab.kt
                HighlightListItem.kt

            service/
              ScreenCaptureService.kt
              RecordingForegroundService.kt

            recording/
              ScreenCaptureManager.kt
              SegmentWriter.kt
              SegmentManager.kt
              ReplayBuffer.kt
              RecordingSession.kt
              CaptureConfig.kt

            highlight/
              HighlightManager.kt
              ClipBuilder.kt
              HighlightRequest.kt
              HighlightResult.kt

            storage/
              HighlightRepository.kt
              HighlightDatabase.kt
              HighlightDao.kt
              HighlightEntity.kt
              StoragePaths.kt

            settings/
              AppSettings.kt
              SettingsRepository.kt
              SettingsViewModel.kt

            detection/
              OcrDetector.kt
              DetectionScheduler.kt
              EventClassifier.kt

            util/
              FileUtils.kt
              TimeUtils.kt
              MediaMuxerHelper.kt
              SegmentNaming.kt
```

---

## File-by-file responsibilities

### App root

#### App.kt

- application-level setup
- initialize dependency injection if using Hilt or manual DI
- configure repositories and settings defaults

#### MainActivity.kt

- hosts the Compose UI
- binds to ViewModel
- starts/stops recording
- opens settings and highlights screens

#### MainViewModel.kt

- exposes app state
- handles start/stop capture requests
- exposes highlight trigger action
- reads saved highlight list

---

### UI layer

#### ui/HomeScreen.kt

- main control screen
- start/stop recording button
- current recording status
- highlight trigger button
- maybe a small summary of buffer length

#### ui/SettingsScreen.kt

- replay buffer duration (default 30s)
- pre-event duration (default 20s)
- post-event duration (default 10s)
- segment length preference

#### ui/HighlightsScreen.kt

- show all saved highlights
- allow play, delete, and share
- display filenames and timestamps

#### ui/components/RecordingButton.kt

- reusable UI for start/stop actions

#### ui/components/HighlightFab.kt

- floating action button for manual highlight capture

#### ui/components/HighlightListItem.kt

- row item for list of saved clips

---

### Service and permission layer

#### service/ScreenCaptureService.kt

- foreground service that owns the continuous screen capture process
- receives screen capture permission result from the activity
- starts the encoder and segment writer
- stops the service cleanly

#### service/RecordingForegroundService.kt

- optional wrapper class if you prefer separate naming for service semantics
- keeps recording alive while app is in background

---

### Recording layer

#### recording/CaptureConfig.kt

- configuration data class
- bufferDurationMs
- preTriggerDurationMs
- postTriggerDurationMs
- segmentDurationMs
- outputDirectory

#### recording/ScreenCaptureManager.kt

- coordinates MediaProjection and surface lifecycle
- opens capture session
- passes frames to the encoder
- handles start/stop and errors

#### recording/SegmentWriter.kt

- writes chunks of encoded video to disk
- creates files like segment_001.mp4, segment_002.mp4
- rotates segments by fixed duration

#### recording/SegmentManager.kt

- manages the active segment list
- creates segments on a timer
- ensures the newest segments remain in the replay buffer

#### recording/ReplayBuffer.kt

- holds a queue of segment files for the last N seconds
- removes the oldest segment when the buffer exceeds the configured limit
- exposes methods like addSegment(), getSegmentsForWindow(), clear()

#### recording/RecordingSession.kt

- represents the current recording session state
- tracks start time, current segment count, buffer size, and file paths

---

### Highlight layer

#### highlight/HighlightRequest.kt

- data class representing a trigger request
- fields like triggerTimeMs, preDurationMs, postDurationMs, createdAt

#### highlight/HighlightResult.kt

- result object returned after clip creation
- fields like outputFilePath, success, errorMessage

#### highlight/HighlightManager.kt

- receives manual trigger event
- finds the proper segments from the replay buffer
- requests post-trigger continuation if needed
- delegates final clip merge to ClipBuilder

#### highlight/ClipBuilder.kt

- merges selected segment files into one MP4 clip
- creates final output filename with timestamp
- handles ordering and metadata

---

### Storage layer

#### storage/HighlightEntity.kt

- Room entity model for saved recordings
- fields like id, filePath, createdAt, durationMs, title

#### storage/HighlightDao.kt

- SQL access for saved highlights
- methods: insert, delete, getAll, getById

#### storage/HighlightDatabase.kt

- Room database definition

#### storage/HighlightRepository.kt

- main repository for highlight metadata and file access
- saves metadata and exposes list of saved clips

#### storage/StoragePaths.kt

- defines directory names such as Highlights/ and Temp/
- centralizes file path rules

---

### Settings layer

#### settings/AppSettings.kt

- simple data class with defaults
- replayBufferSeconds = 30
- preEventSeconds = 20
- postEventSeconds = 10
- segmentDurationSeconds = 5

#### settings/SettingsRepository.kt

- saves and reads settings from DataStore or SharedPreferences

#### settings/SettingsViewModel.kt

- exposes settings UI state

---

### Detection layer (future)

#### detection/OcrDetector.kt

- samples frames periodically
- runs OCR to check for text such as SAVAGE or VICTORY
- returns event candidates and confidence

#### detection/DetectionScheduler.kt

- decides when OCR should run
- keeps CPU usage under control by sampling at intervals

#### detection/EventClassifier.kt

- compares OCR output against recognized MLBB event names
- applies confidence thresholds and suppression rules

---

### Utility layer

#### util/FileUtils.kt

- file creation helpers
- safe storage cleanup
- timestamp formatting

#### util/TimeUtils.kt

- convert durations and timestamps
- compute buffer windows

#### util/MediaMuxerHelper.kt

- helper for combining segments into single MP4 output

#### util/SegmentNaming.kt

- naming pattern for segment files and final highlight names

---

## Core MVP flow

### 1. User presses Start Recording

- MainActivity starts ScreenCaptureService
- service asks for MediaProjection permission if needed
- capture begins
- SegmentWriter starts writing 2–5 second clips

### 2. Replay buffer keeps latest 30 seconds

- SegmentManager creates new segment files
- ReplayBuffer stores newest segments only
- oldest segments removed automatically

### 3. User presses Highlight

- HighlightManager records the current time as triggerTime
- it identifies the pre-event window and post-event window
- the current recording continues for 10s after the trigger
- the selected segment files are collected

### 4. ClipBuilder creates final MP4

- selected segments are ordered chronologically
- MediaMuxer or ffmpeg-like merge is used to produce a single highlight file
- filename is timestamped and saved under Highlights/

### 5. Saved highlight screen shows clips

- repository reads highlight metadata
- UI shows play, delete, and share buttons

---

## Minimal Kotlin class signatures

```kotlin
class ReplayBuffer(
    private val maxDurationMs: Long,
    private val segmentDurationMs: Long
) {
    fun addSegment(segment: SegmentFile)
    fun getSegmentsForWindow(startMs: Long, endMs: Long): List<SegmentFile>
    fun trimToLimit()
}

class SegmentManager(
    private val outputDir: File,
    private val segmentDurationMs: Long
) {
    fun start()
    fun stop()
    fun currentSegments(): List<File>
}

class HighlightManager(
    private val replayBuffer: ReplayBuffer,
    private val clipBuilder: ClipBuilder
) {
    fun createManualHighlight(triggerTimeMs: Long): HighlightResult
}

class ClipBuilder(
    private val outputDir: File
) {
    fun mergeSegments(segments: List<File>, outputName: String): File
}
```

---

## Suggested implementation priority

1. Start with the recording service and segment writer
2. Add ReplayBuffer and segment cleanup
3. Add manual highlight trigger behavior
4. Add ClipBuilder and final MP4 export
5. Add highlight list UI and storage metadata
6. Add OCR-based auto detection later

This is the cleanest path to a working MVP without jumping into AI too early.
