# MLBB Highlight

An Android screen-capture app for creating Mobile Legends: Bang Bang gameplay highlights.

## Current Status

The current MVP can:

- Request Android screen-capture permission.
- Start and stop foreground screen recording.
- Encode screen capture as short MP4 segments using `MediaCodec` and `MediaMuxer`.
- Maintain an internal rolling segment buffer for replay capture.
- Save a manual highlight request with previous replay footage plus post-event footage.
- Show saved highlights in the app with play, delete, and share actions.

Automatic event detection is intentionally deferred until the manual replay MVP is reliable.

## Output Location

Highlight files are saved in the app's external files area:

```text
Android/data/com.mlbb.highlight/files/Highlights/
```

Temporary replay segments are stored under the app's external Movies files area and are rotated out by the replay buffer.

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
5. Tap **Save Highlight** while capture is running.
6. Wait for the post-event recording window to finish.
7. Play, delete, or share the saved highlight from the list.

## Architecture

- Kotlin
- Jetpack Compose
- Android MediaProjection
- Foreground service
- MediaCodec and MediaMuxer
- FileProvider for local highlight playback and sharing

## Roadmap

- Configurable buffer and highlight durations
- More exact MP4 trimming inside segment boundaries
- OCR-based event detection
- MLBB-specific computer-vision event classification

## License

No license has been selected yet.
