# MLBB Highlight Editor

## Product goal

Build an Android app that turns one MLBB gameplay recording into a polished,
editable highlight montage. Keep screen recording and ordinary manual editing,
but make the main editing path:

**Record or import → trim → Auto Highlight → choose a style → preview → export**

Do not require users to place every cut or effect by hand. Auto-generated work
must remain timeline data that users can edit before it is rendered.

## Main workflow

1. Record a match or import one video.
2. Choose the source range in Trim.
3. Run **Auto Highlight** and choose Cinematic, Aggressive, Clean, or Classic.
4. Review OCR-recognized event moments; provide **Mark Moment** for events OCR
   misses or cannot reliably recognize.
5. Edit or remove generated clips, effects, speed, zoom, shake, and text.
6. Optionally import a music file and set its mix level.
7. Render and watch a full montage preview before exporting.

Preserve the existing recording, video library, trim, manual-effects, playback,
delete, and share workflows.

## Automatic event detection

- Analyze sampled frames from the selected trim range; do not decode every frame
  without a demonstrated need.
- OCR may create an event only when supported event text is actually visible and
  passes a confidence threshold.
- Never claim visual recognition of a kill, team fight, Lord/Turtle steal, or
  other event unless a validated detector can identify it reliably.
- When automatic recognition is unavailable or uncertain, let users mark a
  moment while reviewing the source video.
- Keep recognition language and limitations visible in the UI.
- Expand event coverage or add an ML model only after evaluation on legally
  obtained MLBB gameplay.

## Generated timeline and editing

Represent a generated montage as editable clips/events, not as a baked video.
Keep source timestamps, clip ranges, event type and origin, style/effect preset,
speed curve, text overlay, and audio cues in the edit model.

For a readable event, start with roughly 1.5–3 seconds before it and 1–2 seconds
after it. Merge nearby events into a meaningful sequence, avoid tiny cuts, and
clamp all ranges to the user's selected trim. Users must be able to edit clip
start/end, effect, effect intensity (Off/Low/Medium/High), speed, zoom, shake,
text, and text position, or remove the clip.

Use subtle, event-synchronized effects by default. Do not apply shake or slow
motion across the entire recording. Keep game audio audible when optional music
is mixed in.

## Export

- Concatenate selected source ranges into one MP4 using the existing Media3
  Transformer pipeline.
- Render a full preview from the same composition used for final export.
- Support vertical 9:16 by default, landscape 16:9, and square 1:1, with 720p
  and 1080p output sizes.
- Keep aspect-ratio cropping centered and avoid stretching gameplay.
- Save final exports in the user's selected folder; keep previews temporary.
- Surface export, storage, and media-processing failures explicitly.

Do not bundle music or sound effects without appropriate rights. Until a licensed
asset library is available, allow user-provided music rather than displaying
nonfunctional genre presets. Do not add controls for audio or export behavior
that the pipeline does not actually implement.

## Android architecture

Prefer Kotlin, Jetpack Compose, MediaProjection, MediaCodec/MediaMuxer for
recording, coroutines for background work, and AndroidX Media3 ExoPlayer and
Transformer for editing and export. Capture belongs in a foreground service,
independent of the editor UI. Use modern Android permissions and storage APIs;
verify platform restrictions before changing recording behavior.

Keep components reusable and separated by responsibility:

- `recording/`: capture service, encoder, rolling-buffer components
- `highlight/`: editable event/clip/timeline models, planner, composition export
- `detection/`: OCR and future validated detectors
- `ui/`: import, trim, Auto Highlight, timeline editing, preview, manual effects
- `storage/`: source library, temporary render files, final output repository

Reuse the existing player, timeline, trim system, effects, and export pipeline
where practical. Keep automatic and manual editing interoperable without
removing manual editing.

## Performance and validation

- Sample scaled frames off the main thread and release bitmaps/retrievers.
- Bound scan work for long recordings and provide clear scanning/export state.
- Avoid keeping full video files or decoded frame sequences in memory.
- Validate the generated timeline planner and event boundaries with unit tests.
- Validate preview/export composition and audio/video synchronization on Android
  devices before claiming production reliability.
- Document detector coverage and any unimplemented sound, music, or frame-rate
  behavior accurately.
