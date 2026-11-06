# Playback device tests

`PlaybackTestInstrumentation` validates the shared playback queue and all built-in backend configurations on an Android device. It contains seven independently selectable suites:

- `resume` uses the bundled 25-second local fixture for deterministic resume, restart, preload, reload, pause, and position assertions.
- `server` maps the Jellyfin `Test Videos` library, samples every discovered codec/profile/level/bit-depth, audio codec, container, HDR/Dolby range, and subtitle codec, then checks production routing, direct play, and remux requests.
- `transcode` forces video conversion for every distinct resolution/codec/profile/level/bit-depth/HDR/Dolby signature, rejects every discovered video codec/profile pair while keeping its codec and container advertised, and forces audio or subtitle conversion for every discovered codec. It also generates cases for the exact Jellyfin reason associated with level, resolution, bit depth, frame rate, reference frames, anamorphic video, HDR range, video/audio bitrate, audio channels/profile, stream count, and container bitrate. Reasons the current device-profile protocol cannot express are reported as `SKIP`. Representative conversions are played through every backend, must advance with healthy decoder statistics, and must successfully request server encoding cleanup after release.
- `backend` plays representative SDR, ASS, multi-track, HDR10, and Dolby Vision profile 5/7/8 items through ExoPlayer, ExoPlayer with LibASS, MPV, and VLC. It exercises start, advance, pause, absolute seek, resume, reload, stop, track discovery/selection and persistence, decoded-frame growth, dropped-frame limits, audio decoder initialization, and records decoder, HDR, Dolby transform, and LibASS diagnostics.
- `player-flow` runs seek, chapter seek, intro prompt/automatic skip, same-item audio/subtitle persistence, next-episode transition, and next-episode audio/subtitle persistence through ExoPlayer, ExoPlayer with LibASS, MPV, and VLC.
- `soak` repeats the backend matrix and detects monotonic file-descriptor growth, excessive process-memory growth, decoder allocation failures, crashes, duplicate errors, and unexpected end events. Use `soakIterations` to select 2 through 50 repetitions; the default is 3.
- `recovery` injects a temporary HTTP 503 into the bundled local fixture after playback has started. Every backend must recover near the previous position without resetting to zero or reporting duplicate errors.
- `hdmi-audio` discovers the connected HDMI/ARC/eARC sink and maps its AC-3, E-AC-3, DTS, DTS-HD, TrueHD, and AC-4 capabilities to matching server items. Unsupported hardware or missing fixtures produce `SKIP`/`WARN`; ExoPlayer and MPV require passthrough telemetry, while VLC reports a warning because it does not expose equivalent telemetry.

The server suites reuse the server and administrator token already stored by the debug app. They create or repair a hidden, non-administrator `androidtv-playback-test` user with an empty password and a restricted policy. Playback requests use a separate API client and deliberately omit Jellyfin progress-reporting services. Before and after every run, watched position, played flag, play count, last-played date, favourite, likes, and rating are compared for both the normal and test accounts. Tokens and token-shaped values are redacted from reports.

## Build and install

Build both APKs, then install the device ABI split and the Android test APK named by their `output-metadata.json` files. Reinstall with `-r` so the debug app's saved login remains intact.

```sh
gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --parallel
adb -s DEVICE install -r app/build/outputs/apk/debug/ABI_DEBUG_APK
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/ANDROID_TEST_APK
```

The saved normal user must be an administrator the first time so the runner can create and restrict the test account. The test account is reused on later runs.

## Run

Run everything:

```sh
adb -s DEVICE shell am instrument -w -e suite all org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
```

Useful filters:

```sh
adb -s DEVICE shell am instrument -w -e suite resume -e backend MPV org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite server -e scenario 4k-dv7 org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite transcode -e backend MPV org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite transcode -e scenario audio-truehd org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite transcode -e scenario profile-h264-high-10 org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite backend -e backend ExoPlayer-Libass -e scenario subtitles org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite backend -e scenario track-persistence org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite player-flow org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite recovery -e backend VLC org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite soak -e soakIterations 5 org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
adb -s DEVICE shell am instrument -w -e suite hdmi-audio org.jellyfin.androidtv.thor.debug.test/org.jellyfin.androidtv.test.PlaybackTestInstrumentation
```

Arguments:

- `suite`: `all`, `resume`, `server`, `transcode`, `backend`, `player-flow`, `soak`, `recovery`, `hdmi-audio`, or `updater`; defaults to `all`.
- `backend`: `ExoPlayer`, `ExoPlayer-Libass`, `MPV`, or `VLC`.
- `scenario`: an exact case such as `controls`, `dolby-7`, `dolby-7-transcode`, `video-playback`, `audio-truehd`, or a canonical fixture such as `4k-dv7`. The forced `dolby-7-transcode` case verifies that server video conversion clears the local Dolby plan.
- `testUser`: isolated account name; defaults to `androidtv-playback-test`.
- `testFolder`: exact Jellyfin library/folder name; defaults to `Test Videos`.
- `soakIterations`: repetitions per soak scenario, clamped to 2 through 50; defaults to 3.

`FAIL` means a present fixture or asserted backend behavior failed. `WARN` means the run completed but an optional route was unavailable, such as a server choosing transcode instead of remux. `SKIP` means a requested fixture was not present. Missing canonical fixtures are warnings so other coverage continues.

Cleanup checks that the stop-encoding API request succeeds. Server 12 may retain session transcode information after FFmpeg exits, so that field is not used as a process-running assertion. Check server FFmpeg exit logs when validating process cleanup end to end.

Reports are written on the device under:

```text
/sdcard/Android/data/org.jellyfin.androidtv.thor.debug/files/playback-tests/latest.json
/sdcard/Android/data/org.jellyfin.androidtv.thor.debug/files/playback-tests/catalog.json
```

`catalog.json` contains every discovered media source, not only the representative fixtures.

Wake the device and dismiss its screensaver before video tests. Keep the physical TV on and select the device HDMI input. On Fire TV, verify HDMI-CEC `power_status` when an awake Android display still has no active TV output. The runner uses a non-exported activity present only in debug builds.
