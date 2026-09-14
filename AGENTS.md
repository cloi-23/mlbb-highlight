AGENTS.md

Project: MLBB Automatic Highlights / Replay App

Build an Android application that automatically captures and saves highlights from Mobile Legends: Bang Bang (MLBB) gameplay.

The core idea is similar to a gaming highlight/replay application:

1. The app continuously captures gameplay using Android screen recording.
2. It maintains a rolling/replay buffer of recent gameplay.
3. When an important event is detected or the user triggers an event:
   - Keep the gameplay from before the event.
   - Continue recording briefly after the event.
   - Combine the relevant section into a highlight clip.
4. Eventually use computer vision/OCR/ML to automatically detect MLBB events.

---

1. Main Goal

Create an Android app that can produce clips like:

MLBB gameplay
↓
Continuous screen capture
↓
30-second rolling buffer
↓
Event detected
↓
Save previous gameplay +
Continue recording after event
↓
Generate highlight.mp4

Example:

If an event happens at:

10:05:00

The application should be able to save:

10:04:40 → 10:05:10

That means:

- 20 seconds before the event
- 10 seconds after the event

The exact buffer duration should be configurable.

---

2. Important Requirement: Replay Buffer

The application MUST NOT wait until an event happens before starting capture.

It must continuously maintain a rolling buffer.

Example:

[10:03:00]
[10:03:05]
[10:03:10]
[10:03:15]
[10:03:20]
[10:03:25]
[10:03:30]
↓
oldest segments are removed

If the buffer is configured for 30 seconds, only the latest 30 seconds need to remain available.

When an event happens:

Event = 10:03:30

Save:

10:03:10 ───────────── 10:03:40
↑ ↑
before after

Do NOT store an unlimited amount of gameplay.

---

3. Android Technology

Prefer modern Android development.

Recommended:

- Kotlin
- Android Studio
- Jetpack Compose for UI
- Android MediaProjection for screen capture
- MediaCodec / MediaMuxer where appropriate
- Android Media3 where useful
- Coroutines
- Foreground Service for long-running screen capture
- Room/DataStore for local configuration if needed

Do not use deprecated Android APIs when a modern alternative exists.

Before implementing screen recording, verify current Android restrictions and permissions.

---

4. Capture Architecture

Expected architecture:

MLBB
↓
Android Screen
↓
MediaProjection
↓
Video Encoder
↓
Short Video Segments
↓
Rolling Buffer
↓
Event Trigger
↓
Clip Builder
↓
Highlight MP4

The recording service should run independently from the main UI.

Suggested components:

app/
├── ui/
│ ├── HomeScreen
│ ├── SettingsScreen
│ └── HighlightsScreen
│
├── recording/
│ ├── ScreenCaptureService
│ ├── VideoEncoder
│ ├── SegmentManager
│ └── ReplayBuffer
│
├── highlight/
│ ├── HighlightManager
│ ├── ClipBuilder
│ └── EventTrigger
│
├── detection/
│ ├── OcrDetector
│ ├── MlDetector
│ └── EventClassifier
│
└── storage/
├── HighlightRepository
└── SettingsRepository

Adjust the structure if a better architecture is appropriate.

---

5. First MVP

Do NOT start with AI.

The first version should prove that the replay-buffer system works.

MVP requirements:

A. Start/Stop capture

User can start and stop screen capture.

B. Replay buffer

Maintain a configurable rolling buffer.

Default:

Buffer: 30 seconds

C. Manual highlight button

Provide a floating/overlay button or another appropriate mechanism.

When the user presses:

HIGHLIGHT

the app should:

save previous 20 seconds

- # record next 10 seconds
  30-second highlight

D. Save video

Save the resulting clip locally as MP4.

Example:

Highlights/
highlight_2026-09-14_10-05-23.mp4

E. Highlight list

Show saved highlights in the application.

The user should be able to:

- Play
- Delete
- Share

Do not implement ML/AI until this MVP is working reliably.

---

6. Segment-Based Replay Buffer

Prefer a segment-based implementation instead of trying to keep an entire encoded video in RAM.

For example:

segment_001.mp4
segment_002.mp4
segment_003.mp4
segment_004.mp4
segment_005.mp4
segment_006.mp4

Each segment could be approximately 2–5 seconds.

The application maintains only enough segments to cover the configured replay duration.

For a 30-second buffer:

5-second segments

segment 1
segment 2
segment 3
segment 4
segment 5
segment 6

When a new segment is created:

new segment
↓
add to buffer
↓
remove oldest segments
↓
keep approximately 30 seconds

Avoid unnecessary RAM usage.

---

7. Important: Event Timing

When an event occurs, the system needs two phases.

Phase 1: Previous footage

Immediately identify the segments that existed before the event.

Example:

eventTime = 10:05:00

preEventDuration = 20 seconds
postEventDuration = 10 seconds

Phase 2: Future footage

Continue capturing for 10 seconds after the event.

Then create the final clip.

PREVIOUS BUFFER +
POST EVENT
↓
ClipBuilder
↓
highlight.mp4

Multiple highlight requests should be handled safely.

Do not corrupt the recording service if two events happen close together.

---

8. Future Automatic Detection

After the manual replay system works, add automatic detection.

Potential MLBB events:

FIRST BLOOD
KILL
DOUBLE KILL
TRIPLE KILL
MANIAC
SAVAGE
VICTORY
DEFEAT
TURRET DESTROYED
LORD
TURTLE
TEAM FIGHT

Detection should initially focus on events that are visually obvious.

---

9. OCR First

Before training a custom computer-vision model, implement OCR.

The system can periodically analyze frames:

Video frame
↓
OCR
↓
Recognized text
↓
Event matching

Example:

OCR result:

"SAVAGE"

        ↓

Detected event:
SAVAGE

        ↓

Trigger replay buffer

Use a confidence threshold to reduce false positives.

Do not analyze every frame if unnecessary.

Use an appropriate sampling rate to balance:

- CPU usage
- battery usage
- detection accuracy

---

10. ML / Computer Vision

After OCR works, investigate a custom ML model.

Potential approach:

MLBB screenshots
↓
Label events
↓
Train object/event detector
↓
Evaluate model
↓
Export mobile-compatible model
↓
Run inference on Android

Possible tools:

- YOLO / Ultralytics
- Roboflow for dataset labeling
- TensorFlow Lite / LiteRT
- ONNX Runtime if appropriate
- OpenCV

Do not assume a pre-trained MLBB-specific model exists.

If no suitable model exists, plan for a custom dataset.

---

11. Dataset

The dataset should come from legally obtained gameplay recordings/screenshots that we are permitted to use.

Possible labels:

kill
double_kill
triple_kill
maniac
savage
victory
defeat
lord
turtle
turret
team_fight

Start with a small number of high-value labels.

Recommended initial model target:

SAVAGE
MANIAC
DOUBLE KILL
TRIPLE KILL
VICTORY

Do not spend time building a huge dataset before the replay-buffer MVP works.

---

12. Performance Requirements

The application is intended to run while the user is playing MLBB.
