# MLBB Highlight MVP Todo

## Phase 1: Capture and replay buffer

- [x] Verify Android screen capture permissions and foreground service requirements
- [x] Set up a foreground recording service using MediaProjection
- [x] Implement a screen capture encoder and write short video segments
- [x] Maintain a rolling 30-second replay buffer with segment cleanup
- [ ] Make buffer duration configurable in settings
- [x] Ensure the app keeps only the newest replay footage in memory/storage

## Phase 2: Manual highlight flow

- [x] Add an in-app manual highlight trigger button
- [x] On trigger, keep previous 20 seconds and record next 10 seconds
- [x] Combine the selected clip into one final MP4
- [x] Save highlight video as a timestamped file in local storage
- [x] Show saved highlights in the app UI
- [ ] Add a floating/overlay highlight trigger button

## Phase 3: Highlight management

- [x] Add play support for saved highlight videos
- [x] Add delete support for saved highlight videos
- [x] Add share support for saved highlight videos
- [x] Guard against overlapping highlight requests during recording

## Phase 4: App polish

- [ ] Add a Home screen with start/stop recording controls
- [ ] Add a Settings screen for replay length and capture preferences
- [ ] Validate that output videos can be opened and played correctly
- [ ] Improve app behavior and edge-case handling

## Phase 5: Future automation (after MVP works)

- [ ] Add OCR-based frame sampling for game text detection
- [ ] Match OCR results to MLBB events
- [ ] Use confidence thresholds to reduce false positives
- [ ] Trigger highlight creation from automatic event detection
- [ ] Explore a lightweight custom ML model for event detection

## Current roadmap status

- [x] Capture permissions and foreground service setup are in place for the app shell
- [x] Recording flow is stable enough to keep the capture service alive
- [x] Segment rotation feeds a rolling replay buffer and removes expired segment files
- [x] Manual highlight requests now wait for post-event footage and merge selected segments
- [x] Play/delete/share highlight management is wired through FileProvider URIs
- [ ] Buffer length and highlight timing are still hard-coded and need settings support
- [ ] Segment-level clips are working toward the MVP; exact frame-level trimming is still pending
- [ ] OCR / AI detection is intentionally deferred until the non-AI MVP works reliably

## Notes

- This project should first prove the replay-buffer system works reliably before adding AI detection.
- The initial MVP focus is manual highlight creation with a valid replay buffer and saved MP4 exports.
- The app is currently on the non-AI path: manual capture and highlight logic are active, while OCR and model-based detection remain future work.
