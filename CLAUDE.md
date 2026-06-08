# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Native **Android** (Kotlin + Jetpack Compose) video compressor app named **VideoCompressor**
(package `com.example.videocompressor`). Despite living under a `FlutterProject` directory, there is
no Flutter here — it is a pure Gradle/Android project. UI strings are in Chinese.

- minSdk 26, compile/target SDK 35, Java 17, Kotlin 2.0.21, AGP 8.7.3
- Stack: Compose + Material3, Hilt (DI via KSP), Navigation Compose, Coroutines, Gson
- Versions are pinned in `gradle/libs.versions.toml` (plugins) and hardcoded in `app/build.gradle.kts` (libraries)

## Commands

Use the Gradle wrapper (`./gradlew` on Unix, `gradlew.bat` on Windows PowerShell).

```bash
./gradlew assembleDebug      # build debug APK
./gradlew installDebug       # build + install on connected device/emulator
./gradlew lint               # Android Lint
./gradlew test               # unit tests (none exist yet)
./gradlew clean
```

There are currently **no tests** (no `src/test` or `src/androidTest`). Release build has
`isMinifyEnabled = true` (R8/ProGuard), so verify ProGuard rules when adding reflection-based code.

## Architecture

Layered (data / domain / service / ui), wired together by Hilt. The non-obvious part is the
**compression pipeline and how the UI talks to the background work** — it is *not* a bound service or
a shared Flow.

### Compression flow

1. `HomeScreen` → `CompressViewModel.startCompress()` serializes `CompressConfig` to JSON (Gson) and
   starts **`CompressService`** as a foreground service, passing the video `Uri` + config JSON as Intent extras.
2. `CompressService` (foreground, `dataSync` type, with WAKE_LOCK) runs `CompressVideoUseCase`, which
   delegates to the `VideoCompressor` implementation.
3. `MediaCodecCompressor` does the actual transcode and saves the result to the system gallery (MediaStore).
4. Results flow **back to the UI via `sendBroadcast`** — the Service broadcasts
   `ACTION_PROGRESS_UPDATE` / `ACTION_COMPRESS_COMPLETE` / `ACTION_COMPRESS_ERROR`, and
   `MainActivity`'s `BroadcastReceiver` forwards them into `CompressViewModel`, which updates
   `CompressUiState.status` (a `CompressStatus` sealed class: Idle/Running/Done/Error).

So: **ViewModel → Service is via Intent; Service → ViewModel is via broadcasts.** The receiver is
registered `RECEIVER_NOT_EXPORTED`. Note `CompressVideoUseCase` is injected into both the ViewModel
and the Service, but only the Service actually invokes it.

Navigation is a 4-route `NavHost` in `MainActivity` (`home` → `progress` → `result`/`error`), driven
by the same shared `CompressViewModel` (activity-scoped).

### The actual transcode (`domain/compressor/MediaCodecCompressor.kt`)

This is the heart of the project. Surface-to-surface MediaCodec transcode:

- Input `Uri` is first copied to `cacheDir/input_*.mp4` (old `input_*` cache files >1h are pruned).
- A `MediaExtractor` feeds a **decoder** whose output `Surface` is the **encoder's input surface**
  (no manual pixel copy). Encoder MIME is HEVC (`video/hevc`) when available, else AVC (`video/avc`).
- Bitrate is computed in `calcBitrate` from output pixels × a quality factor (HIGH/BALANCED/SMALL),
  frame rate fixed at 30, I-frame interval 1.
- **Audio is pass-through copied** frame-by-frame via a second `MediaExtractor` → `MediaMuxer`
  (not re-encoded). The audio track must be added to the muxer before `muxer.start()`.
- Output is muxed to the app's external files dir, then **inserted into MediaStore (`Movies/`)** via
  `IS_PENDING`, and the temp file deleted. The success Result carries the MediaStore content `Uri` string.
- A `PARTIAL_WAKE_LOCK` is held for the duration.

### Subtitles / chapters — the "字幕 / 节点" feature (offloaded to a PC)

A **second, parallel pipeline** that has nothing to do with MediaCodec. The phone does *not*
transcribe locally — it ships the work to a **faster-whisper HTTP server running on your PC** and just
handles audio extraction, upload, and saving the results. Exposed on `HomeScreen` as the
`SectionCard("字幕 / 节点")` (`SubtitleSection`), with a "电脑服务地址" field (e.g. `192.168.1.20:8000`)
and two buttons:

- **软字幕(SRT)** → `MODE_TRANSCRIBE`: extract audio, upload, receive transcript, write **sidecar
  files** (SRT + chapters + extras), shareable.
- **烧进视频 (hard subs)** → `MODE_BURN`: upload the **whole video**, the PC burns subtitles in +
  re-encodes, the phone downloads the finished mp4 and **saves it to the gallery (`Movies/`)**.

Flow and components:

1. `CompressViewModel.startTranscribe()` / `startBurn()` start **`TranscribeService`** (foreground,
   `dataSync`), passing the video `Uri`, server URL, display name, and mode as Intent extras. The
   server URL is persisted via `SettingsStore` (`updateServerUrl`).
2. `TranscribeService` runs the chosen mode on an IO coroutine scope:
   - transcribe: `AudioExtractor.extractToM4a` → `TranscribeClient.transcribe(server, audio)` (POST
     multipart to `<base>/transcribe`) → `saveSidecars(...)`.
   - burn: copy `Uri` to a cache mp4 → `TranscribeClient.burn(...)` (POST multipart to `<base>/burn`,
     streams the returned mp4 to a temp file) → `insertVideoToGallery(...)`.
3. `TranscribeClient` (OkHttp) normalizes the URL (prepends `http://`, trims trailing `/`) and uses
   **very long timeouts** (30 min write, 120 min read, no call timeout) because a 2-hour lecture can
   take tens of minutes server-side.
   - **Progress is streamed back via NDJSON** (the server endpoints return
     `application/x-ndjson`, one JSON object per line). `transcribe`/`burn` read the body line by
     line (`source().readUtf8Line()`): progress lines are `{"stage":"…","progress":0.42}` (forwarded
     to `onProgress(stage, progress)`), the **final line** is `{"done":true, …}`, and an error line is
     `{"error":"…"}`. For `transcribe` the final line *is* the full `TranscribeResponse`
     (`srt`/`vtt`/`text`/`chapters`/`summary`). For `burn` the final line carries
     `{"video_url":"/download/<token>"}`; the client then GETs that to download the finished mp4.
     The server computes transcribe progress from `segment.end / info.duration` (mapped to ~0–0.9),
     plus markers for LLM correction / chapters / summary; `burn` maps the transcribe phase to 0–0.8
     and emits a stage marker during the ffmpeg burn.
4. `saveSidecars` writes `<stem>.srt` to the app's external `Movies/` dir, and opportunistically also
   `<stem>.vtt`, `<stem>.txt`, `<stem>.chapters.txt`, and `<stem>.summary.txt` (overview + timeline)
   when those fields are present. All are shareable via the `FileProvider`.
5. Results flow **back via `TranscribeBus`** (a `@Singleton` in-process `StateFlow`, like
   `CompressProgressBus` — *not* the broadcast mechanism the compress path uses): events
   `Running(stage, progress)` / `Done(srtPath, chaptersPath)` / `DoneVideo(videoUri)` / `Error`, which
   the ViewModel maps to `TranscribeStatus` on `CompressUiState.transcribeStatus`. `Running.progress`
   is a nullable `Float` (0–1, `null` = indeterminate); `HomeScreen` shows a `LinearProgressIndicator`
   (determinate when progress is known, else indeterminate) plus a `<pct>%` label.

The **server side IS in this repo** at `tools/pc-transcribe/` (`server.py` = FastAPI endpoints,
`transcribe.py` = faster-whisper + ffmpeg + optional local-LLM logic). It is a standalone PC service
(run with `python server.py`, default `0.0.0.0:8000`), not part of the Android build. The app is just
its LAN client (needs INTERNET + the PC reachable). The streaming endpoints return NDJSON;
`/burn` additionally exposes `GET /download/{token}` to fetch the finished mp4 (registered in the
in-memory `_burn_outputs` map, deleted after download). Both `/transcribe` and `/burn` run the heavy
work on a worker `threading.Thread`, pushing progress/result dicts through a `queue.Queue` that the
`StreamingResponse` generator drains — this keeps the FastAPI event loop unblocked while streaming.

> Note: the compress path itself has also moved to a `CompressProgressBus` `StateFlow` (the ViewModel
> collects it), so the "Service → ViewModel via broadcasts" description above may be stale — verify
> against `CompressViewModel`/`CompressService` before relying on it.

### Gotchas / things that look wired but aren't

- **`CompressConfig.Encoder`** exposes `AUTO`, `HARDWARE_HEVC`, `FFMPEG_HEVC`, `FFMPEG_H264`, but
  **there is no FFmpeg dependency**. `MediaCodecCompressor.selectEncoderMime` only ever picks
  hardware HEVC vs AVC; the FFmpeg options effectively just toggle the HEVC preference.
- **`EncoderDetector`** (references Qualcomm codec names / `libx265`) is defined but **not used** in
  the compression path.
- **`CompressConfig.Quality.crf`** is defined but unused — bitrate is derived from pixel count, not CRF.
- `VideoCompressor.cancel()` exists and `MediaCodecCompressor` honors a `cancelled` flag, but nothing
  in the Service/UI currently calls `cancel()`.
- `MainActivity.startCompressService(...)` is dead code (the ViewModel starts the service instead).
- `CompressService` rebuilds a near-empty `VideoInfo` from the Intent (only the `Uri` matters there);
  the rich `VideoInfo` from `VideoRepository.getVideoInfo` is only used for UI display.

### DI

`di/AppModule.kt` (`@InstallIn(SingletonComponent)`) provides `VideoRepository`,
`VideoCompressor` (→ `MediaCodecCompressor`), and `CompressVideoUseCase` as singletons. `App` is
`@HiltAndroidApp`; `MainActivity`, `CompressViewModel`, and `CompressService` are Hilt entry points.

## Permissions / manifest notes

`READ_MEDIA_VIDEO` (API 33+) plus legacy storage perms, `INTERNET` (for the PC transcribe/burn
upload), `WAKE_LOCK`, `FOREGROUND_SERVICE` +
`FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`. Both `CompressService` and `TranscribeService`
are declared `dataSync` foreground services. A `FileProvider`
(`${applicationId}.fileprovider`, paths in `res/xml/file_paths.xml`) is declared for sharing output.
`BatteryOptimizationHelper` exists to prompt users to exempt the app (MIUI/HyperOS background-kill mitigation).
