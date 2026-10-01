# MLBB Gameplay Recorder Todo

## Full gameplay recording MVP

- [x] Request screen-capture permission
- [x] Record a full session to a single MP4 using a foreground service
- [x] Add Pause/Resume and Stop actions to the recording notification
- [x] Exclude paused time from the saved video's timeline
- [x] Save recordings and show them in the app with play, delete, and share actions
- [x] Add 720p/1080p and 30/60 fps setup
- [x] Add silent, game/device audio, microphone, and combined audio setup
- [x] Add optional notification voice action for spoken Pause/Resume/Stop
- [x] Remove floating overlay so no controls are captured in the video
- [x] Apply consistent dark, card-based styling across Home, My Videos, and Settings
- [ ] Verify pause/resume and stop behavior on physical Android devices
- [ ] Validate audio/video synchronization and long recording playback across devices
- [ ] Test device audio capture on supported games; use microphone when playback capture is blocked

## Next

- [ ] Improve recording settings and duration/storage feedback
- [ ] Revisit replay highlights and automatic event detection only if the product direction changes
