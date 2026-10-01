# MLBB Gameplay Recorder

An Android screen recorder for capturing complete Mobile Legends: Bang Bang gameplay sessions.

## Current MVP

- Request Android screen-capture permission before recording.
- Record the full screen to one MP4 in a foreground service.
- Provide Pause/Resume and Stop actions in the persistent recording notification, without drawing controls over the gameplay capture.
- Pause capture without adding the paused time to the resulting video's timeline.
- Choose 720p or 1080p, 30 or 60 fps, and game/device audio, microphone, both, or silent before recording.
- Optionally enable a Voice action in the notification for spoken Pause, Resume, and Stop commands.
- Save and manage recordings in the app with play, delete, and share actions.
- Import recordings or other videos, preview them, and trim a clip with draggable timeline handles.
- Select a scene within the trimmed clip and apply Cinematic, Neon, Slow Motion, Flash, kill-moment slow motion, camera shake, intensity, and color grading to that scene before exporting an MP4.
- Use the Home dashboard to open Trim, Effects, and Settings, with Home, My Videos, and Profile in the bottom navigation.

The recorder currently targets manual, full-session capture. Automatic event detection and replay highlights are not part of this workflow.

## Output Location

The app keeps a private copy of recordings under its external files directory:

```text
Android/data/com.mlbb.highlight/files/Movies/Recordings/
```

When **Auto-save recordings and edits** is enabled and a save folder is selected in Settings, each completed recording and edited MP4 is also copied into that chosen folder. Videos remain listed in **My Videos**.

## Requirements

- Android Studio with Android SDK 35
- JDK 17
- Android device or emulator running Android 10 (API 29) or newer

Each recording requires Android MediaProjection consent. Audio or voice commands require microphone permission. 720p/1080p refer to the shorter video edge, with the full screen aspect ratio preserved. Android may prevent capture of audio from apps that disallow playback capture. When game audio is silent, try Microphone or Game audio + microphone. Voice commands open Android's speech recognizer from the recording notification; they are not always listening. The editor preserves audio present in the selected source clip.

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
4. Choose resolution, frame rate, and audio settings before starting. Grant any requested microphone permission and approve Android's screen-capture prompt.
5. While playing, open the recording notification to choose **Pause/Resume** or **Stop**. **Voice** appears there when enabled in settings.
6. Find completed MP4 files in **My Videos** from Home; play, delete, or share them there.
7. To edit, open **Trim**, choose a video from the device or select a recording from **MLBB Gallery**. Scrub the preview and drag the timeline handles to set the clip range, continue to **Effects**, and drag the Effect Scene handles to select where the enabled effects should apply. Tap **Apply Effects to Preview** to audition the visual effects and scene-local slow motion in the player; this does not create or save a video. Slow motion extends the selected scene's playback time (a 50% speed setting doubles that scene's duration); if the entire trimmed clip is selected, the entire clip will be slowed. Tap **Export MP4** separately when you want to save the result. Exports appear in **My Videos** and are copied to the selected folder when auto-save is enabled. App recordings may not appear in Android's external file picker because they are kept in the app's private folder; use **MLBB Gallery** instead.

## Architecture

- Kotlin and Jetpack Compose
- Android MediaProjection foreground service
- MediaCodec surface video encoder and MediaMuxer
- AAC audio encoding for selected playback and/or microphone input
- AndroidX Media3 ExoPlayer preview and Transformer MP4 export
- Recording notification controls
- FileProvider for local recording playback and sharing

## Limitations and Future Work

- Actual frame rate and maximum resolution depend on the device's display and encoder capabilities.
- Game audio capture is subject to the source app's Android playback-capture policy.
- Kill shake is a visual camera-shake effect rendered into the selected scene; it does not vibrate the phone.
- Touch indicators are controlled by Android system settings and cannot be toggled by this app. English is currently the only app language.
- Voice recognition depends on an installed Android speech-recognition service and may compete with microphone capture.
- Validate long-session stability, audio/video synchronization, and output playback across devices.
- Revisit replay highlights and OCR/event detection if the product direction changes

## License

No license has been selected yet.
