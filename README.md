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
- In Effects, add scenes manually, then choose one effect per scene.
- Preview the non-destructive edit before exporting. Adjust effect intensity and slow-motion speed when selected.
- Use the Home dashboard to open Trim, Effects, and Settings, with Home, My Videos, and Profile in the bottom navigation.

The recorder currently targets manual, full-session capture. Automatic event detection and replay highlights are not part of this workflow.

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
7. Find completed MP4 files in **My Videos**; tap a video to play, or delete/share it. To edit, open **Trim**, choose a video, and set the clip range. In **Effects**, use **Select Scene**, choose an effect, preview the edit, then export. Exports appear in the selected folder and **My Videos**. The editor uses temporary app storage while an export is being rendered.

## Architecture

- Kotlin and Jetpack Compose
- Android MediaProjection foreground service
- MediaCodec surface video encoder and MediaMuxer
- AAC audio encoding for selected playback and/or microphone input
- AndroidX Media3 ExoPlayer preview and Transformer MP4 export
- Modular, timestamped scene effects and manual scene selection
- Recording notification controls
- Secure floating capture controls
- FileProvider for local recording playback and sharing

## Limitations and Future Work

- Actual frame rate and maximum resolution depend on the device's display and encoder capabilities.
- Game audio capture is subject to the source app's Android playback-capture policy.
- Kill shake is a visual camera-shake effect rendered into the selected scene; it does not vibrate the phone.
- English is currently the only app language.
- Scene selection in the Effects editor is manual.
- Visual effects are silent overlays/transforms; no separate impact sound effect is currently added.
- Validate long-session stability, audio/video synchronization, and output playback across devices.
- Revisit replay highlights and OCR/event detection if the product direction changes

## License

No license has been selected yet.
