# MLBB Highlight MVP Todo

## Phase 1: Capture and replay buffer

- [ ] Verify Android screen capture permissions and foreground service requirements
- [ ] Set up a foreground recording service using MediaProjection
- [ ] Implement a screen capture encoder and write short video segments
- [ ] Maintain a rolling 30-second replay buffer with segment cleanup
- [ ] Make buffer duration configurable in settings
- [ ] Ensure the app keeps only the newest replay footage in memory/storage

## Phase 2: Manual highlight flow

- [ ] Add a floating/overlay highlight trigger button
- [ ] On trigger, keep previous 20 seconds and record next 10 seconds
- [ ] Combine the selected clip into one final MP4
- [ ] Save highlight video as a timestamped file in local storage
- [ ] Show saved highlights in the app UI

## Phase 3: Highlight management

- [ ] Add play support for saved highlight videos
- [ ] Add delete support for saved highlight videos
- [ ] Add share support for saved highlight videos
- [ ] Guard against overlapping highlight requests during recording

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
- [x] Manual highlight save path exists and copies the latest segment to a Highlights folder
- [ ] Full rolling 30-second replay buffer with true segment rotation is still the main next milestone
- [ ] Real 20s-before + 10s-after highlight timing and MP4 merge are still pending
- [ ] Play/delete/share highlight management is still pending
- [ ] OCR / AI detection is intentionally deferred until the non-AI MVP works reliably

## Notes

- This project should first prove the replay-buffer system works reliably before adding AI detection.
- The initial MVP focus is manual highlight creation with a valid replay buffer and saved MP4 exports.
- The app is currently on the non-AI path: manual capture and highlight logic are active, while OCR and model-based detection remain future work.
