# MLBB Gameplay Recorder

An Android screen recorder for capturing complete Mobile Legends: Bang Bang gameplay sessions.

## Current MVP

- Request Android screen-capture permission before recording.
- Record the full screen to one MP4 in a foreground service.
- Capture gameplay in landscape orientation at the selected 720p/1080p short edge.
- Show movable, compact floating recorder controls while using other apps; the overlay window uses Android's secure-window flag so its content is excluded from screen capture.
- Provide Pause/Resume and Stop actions in the persistent recording notification, without drawing controls over the gameplay capture.
- Pause capture without adding the paused time to the resulting video's timeline.
- Choose 720p or 1080p, 30 or 60 fps, and game/device audio, microphone, both, or silent before recording.
- Save and manage recordings in the app with play, delete, and share actions.
- Import recordings or other videos, preview them, and trim a clip with draggable timeline handles.
- Use **Auto Highlight** after trimming to sample gameplay frames, establish a single personal K/D/A baseline, and create moments only when that player's kill count increases.
- Treat kill-feed banners as supporting labels only; death/assist changes, other-player feed entries, ambiguous scoreboard rows, and repeated K/D/A readings do not trigger a moment.
- Manually mark a moment if the personal K/D/A display is unavailable or a kill increase is missed; detection does not claim to recognize combat from visuals alone.
- Choose Cinematic, Aggressive, Clean, or Classic pacing, adjust clip ranges/effect intensity/speed/zoom/shake/text, and remove moments before rendering a full preview.
- Export the selected moments as one MP4 at 720p or 1080p, optionally mixing a user-selected music file and choosing vertical 9:16 (default), landscape 16:9, or square 1:1 output.
- In Effects, add scenes manually, then choose one effect per scene.
- Preview the non-destructive edit before exporting. Scene color, blur, flash, zoom, shake, and slow-motion settings are shared by the Media3 preview and export pipeline.
- Use the Home dashboard to open Trim, Effects, and Settings, with Home, My Videos, and Profile in the bottom navigation.

Recording remains manual full-session capture. Auto Highlight operates on one selected recording; it does not yet run in the background or detect all combat events.

## Output Location

Screen recordings are saved directly in the folder selected in Settings. The editor uses temporary app storage only while rendering an export:
Choose a save folder in Settings before recording or exporting. Edits are staged temporarily for export and then saved there. The selected folder's videos appear in **My Videos**.

## Requirements

- Android Studio with Android SDK 35
- JDK 17
- Android device or emulator running Android 10 (API 29) or newer

Each recording requires Android MediaProjection consent. The floating controls also require Android's **Display over other apps** permission; the app requests this on first launch. Start from the floating button opens the system capture-consent prompt before recording begins. Selecting microphone audio requires microphone permission. 720p/1080p refer to the shorter landscape video edge. Android may prevent capture of audio from apps that disallow playback capture. When game audio is silent, try Microphone or Game audio + microphone. The editor preserves audio present in the selected source clip.

## Build

From the project root:

```bash
./gradlew clean assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Run

1. Open the project in Android Studio.
2. Connect an Android device or start an emulator.
3. Install and launch the debug app.
4. Grant the **Display over other apps** permission so the floating recorder controls can appear above gameplay.
5. Choose a save folder, resolution, frame rate, and audio settings before recording. Tap the compact floating control to expand it, tap **Start**, and approve Android's screen-capture prompt.
6. Keep the controls expanded or collapse them back to the compact button while recording. **Pause/Resume** and **Stop** remain available in the expanded overlay; **Stop** finishes and saves the video. The overlay uses `FLAG_SECURE` and is excluded from the captured video. The recording notification also provides **Pause/Resume** and **Stop**.
7. Find completed MP4 files in **My Videos**; tap a video to play, or delete/share it.
8. To create a montage, open **Trim**, choose a video and set its range, then tap **Auto Highlight**. Choose a style and run the scan. Select **Mark Moment** while reviewing if an event banner was missed. Edit or remove generated moments, choose an optional music file and aspect ratio, then render **Preview full montage** before exporting.

## Architecture

- Kotlin and Jetpack Compose
- Android MediaProjection foreground service
- MediaCodec surface video encoder and MediaMuxer
- AAC audio encoding for selected playback and/or microphone input
- AndroidX Media3 ExoPlayer preview and Transformer MP4 export
- OCR sampling for personal K/D/A kill increases, supporting multi-kill banners, and manual moment marking
- Editable highlight events, merged clip ranges, speed curves, captions, and style presets
- Media3 composition export for concatenated moments, optional looping music mix, and aspect-ratio crop
- Modular, timestamped scene effects and manual scene selection
- Recording notification controls
- Secure floating capture controls
- FileProvider for local recording playback and sharing

## Limitations and Future Work

- Actual frame rate and maximum resolution depend on the device's display and encoder capabilities.
- Game audio capture is subject to the source app's Android playback-capture policy.
- Kill shake is a visual camera-shake effect rendered into the selected scene; it does not vibrate the phone.
- A highlight is created only when the tracked personal K/D/A kill value rises above its previous value. Death and assist changes do not trigger highlights, and duplicate readings are ignored. Double/Triple/Maniac/Savage banners can label a moment only when a personal kill increase is also detected.
- Auto Highlight requests scaled frames from `MediaMetadataRetriever` at nominal video times (at least one second apart, capped at 600 samples). These are requested timestamps, not exact decoder presentation timestamps, and the scan does not decode every source frame.
- OCR runs on the whole scaled frame and on an enlarged 4× proportional upper-right crop. Number groups from both are considered, so OCR-readable K/D/A values can still be found if the HUD is moved; the tracked normalized position and ambiguity checks prevent arbitrary row switching. This is not HUD calibration or visual icon detection.
- Candidate stats are assembled from three horizontally aligned OCR number groups; nearby extra values such as shop gold are excluded by gap grouping where possible. The OCR does not identify sword/skull/assist/dollar graphics, so layout overlap can still make a reading ambiguous. When candidates conflict, the tracker waits instead of guessing.
- A baseline requires three stable reads. A kill-counter increase must also be stable and have at least 0.80 OCR confidence to create an event; lower-confidence increases remain visible as "needs review". Missing or ambiguous readings reset the pending stability sequence. Kill-feed text is context only and cannot independently create an event.
- The Auto Highlight screen's "Show detection details" panel displays recent requested sample times, frame size, candidate K/D/A values, OCR confidence, bounding boxes, and tracker decisions. Debug builds also write scan decisions and confirmed events to Logcat under `AutoHighlight`.
- No OpenCV icon templates, trained detector, manually labeled footage, or benchmark dataset is currently included. Icon precision/recall, OCR accuracy, missed kills, and duplicate rates have therefore not been measured. OCR can miss small, obscured, animated, or non-English values; manually mark moments when needed.
- Custom music tracks can be imported and mixed with game audio. The app does not bundle a licensed music or sound-effect library yet.
- Automatic sound-effect placement and separate game/music/effect audio controls are not yet available.
- Output aspect ratio and 720p/1080p size can be selected. Frame-rate controls are not yet available for generated montages.
- Scene selection in the Effects editor is manual.
- Visual effects are silent overlays/transforms; no separate impact sound effect is currently added.
- Effect order is deterministic: scene color adjustments, geometric zoom/shake, blur, then flash or text overlay. Scene timing is relative to the selected clip; preview offsets it to the source-video timeline and export applies it to the clipped timeline.

### Testing Auto Highlight on recorded gameplay

1. Build and install the debug app, import a legally obtained MLBB recording, choose a trim containing a clear pre-game or early-game K/D/A baseline, and run Auto Highlight.
2. Open **Show detection details**. Confirm the candidate boxes sit over your kill/death/assist values, the shop gold is not part of a triplet, and the tracker establishes a baseline only after three consistent samples.
3. Test separate clips containing your kill, death, assist, another player's kill banner, a multi-kill, a temporarily obscured HUD, a scene transition, and a recording with a different aspect ratio/HUD placement. Compare every generated event time and K/D/A change against playback manually.
4. Keep a labeled table with video ID, event timestamp, expected K/D/A, OCR candidate/confidence, and outcome (true positive, false positive, miss, duplicate). Compute precision = TP/(TP+FP), recall = TP/(TP+FN), OCR exact-match accuracy, and duplicate count; do not interpret the current OCR confidence as measured event accuracy.
5. Use Android Studio Logcat filter `AutoHighlight` to inspect requested sample times, rejection reasons, and confirmed kill transitions. Sample times are approximate and may differ from the source frame's exact PTS.
- Validate long-session stability, audio/video synchronization, and output playback across devices.
- Expand event detection only when it can be validated reliably on permitted MLBB gameplay recordings.

## License

No license has been selected yet.
