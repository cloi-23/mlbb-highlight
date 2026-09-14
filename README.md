# MLBB Highlight

An Android screen-capture app for creating Mobile Legends: Bang Bang gameplay highlights.

## Current Status

The current MVP can:

- Request Android screen-capture permission.
- Start and stop foreground screen recording.
- Encode recordings as MP4 using `MediaCodec` and `MediaMuxer`.
- Save completed recordings to the public Movies folder.
- Open the saved recording from the system notification.

Replay-buffer capture, manual highlight clipping, and automatic event detection are planned next.

## Output Location

Recordings are saved on the device at:

```text
Internal storage/Movies/MLBBHighlight/
```

## Requirements

- Android Studio with Android SDK 35
- JDK 17
- Android device or emulator running Android 10 (API 29) or newer

Screen recording requires the user to approve the Android MediaProjection permission each time capture starts.

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
4. Tap **Start Capture** and approve screen recording permission.
5. Tap **Stop Capture** when finished.
6. Tap the **Recording saved** notification to play the video.

## Architecture

- Kotlin
- Jetpack Compose
- Android MediaProjection
- Foreground service
- MediaCodec and MediaMuxer
- MediaStore for public video storage

## Roadmap

- Segment-based rolling replay buffer
- Manual highlight button with pre-event and post-event footage
- Highlights list with playback, sharing, and deletion
- OCR-based event detection
- MLBB-specific computer-vision event classification

## License

No license has been selected yet.
