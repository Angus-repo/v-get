# V-Get — Facebook Video Downloader for Android

V-Get is a native Kotlin Android app. Paste a public Facebook video link, choose an available quality, and save it on your phone. All parsing runs on the device; no server, API key, web module, or login is required.

[繁體中文使用說明](README_zh_TW.md)

## Features

- Scrollable phone interface with large touch targets and light/dark themes.
- Paste a link or share text directly from Facebook to V-Get.
- Inspect the video title and choose the available HD/SD progressive MP4 stream. An Open Graph fallback is labeled MP4 without assuming its quality.
- Android DownloadManager handles background transfers, retries, and completion notifications.
- View progress, cancel a transfer, and open the saved video with an installed player.
- The latest system download ID is stored locally so reopening the app restores its status. This is not a full download history.
- Files are saved in `Download/V-Get/` and can be found using **My downloads**.

## Usage

1. Copy the public video's own link, or select **Share → V-Get**.
2. Tap **貼上連結**, then **解析影片**.
3. Select one of the available qualities and tap **下載到手機**.
4. After completion, tap **播放影片** or **我的下載**.

Sharing fills the input; downloading starts only after you choose to download. When another transfer is active, finish or cancel it before sharing a new link.

## Supported links and limitations

Recognized page hosts include `facebook.com`, its standard mobile/web hosts, `fb.com`, and `fb.watch`. Supported paths include `/watch/?v=…`, `/reel/…`, and `/…/videos/…`; short/share links must resolve to an accessible public page.

Only publicly accessible pages that expose progressive video URLs can be downloaded. Private/login-required videos, live streams, Stories, and DASH/HLS or separate video/audio tracks are unsupported. No cookies, account credentials, backend extractor, or external command-line programs are used. For videos in comments, copy the video's own link: comment-thread URLs are rejected to avoid downloading the parent post.

Facebook can change page structure or restrict access by region/network. Public visibility alone does not guarantee extraction. The parser matches a video ID when available and fails on ambiguous results instead of selecting a recommendation. Expired download URLs require analyzing the link again.

## Build and run

- Android 7.0+ (API 24+).
- JDK **17**, Android SDK **34**, and Android Studio compatible with AGP 8.1.
- The repository includes a Gradle **8.5** wrapper. No Spring Boot, Node.js, Python, or FFmpeg runtime is required.

```bash
git clone https://github.com/Angus-repo/v-get.git
cd v-get
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:installDebug
```

Set `ANDROID_HOME`, or configure `sdk.dir` in your untracked `local.properties`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

| Component | Responsibility |
| --- | --- |
| `MainActivity` / XML views | Native phone interface, clipboard, sharing, permission prompt, player intents |
| `MainViewModel` | UI state across rotation, coroutine cancellation, restore the latest system download |
| `FacebookUrl` | Parse and validate page/CDN hosts; preserve signed query parameters |
| `FacebookPageParser` | Match target video IDs, decode JSON/HTML, return actual progressive formats |
| `VideoExtractor` | Cancellable on-device OkHttp requests, bounded HTML, validated redirects |
| `VideoDownloader` | Android DownloadManager requests, progress, cancellation and safe file names |

Dependencies: AndroidX, Material Components, Kotlin coroutines, OkHttp, Gson and jsoup. There is a single `:app` module.

## Permissions

- `INTERNET`: retrieve public pages and download videos.
- `WRITE_EXTERNAL_STORAGE`: requested only when starting a download on Android 7–9 (API 24–28).
- Android 10+ uses the system download service without broad media/storage read permissions. The app never asks to read the user's video library.

Android API references: [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager), [public download destinations](https://developer.android.com/reference/android/app/DownloadManager.Request#setDestinationInExternalPublicDir(java.lang.String,%20java.lang.String)).

## Verification

Unit tests cover supported/rejected URLs, signed query preservation, selecting the requested video, nested metadata, legacy fields, ambiguous results, unsupported streams, safe filenames, and share redirects. The HTTP regression test checks that navigation headers are sent on both the share and target requests: omitting them caused HTTP 400 for a public share link. No login or browser cookies are used.

By default, the live test is skipped and all other fixtures are synthetic. To explicitly verify a public link and read the MP4 header from each returned quality:

```bash
./gradlew :app:testDebugUnitTest -PvgetLiveUrl="https://www.facebook.com/share/v/YOUR_LINK/"
```

This opt-in check makes real network requests; it does not exercise Android DownloadManager or an installed media player. Version 1.0.1 (version code 2) includes the share-link HTTP 400 fix.

Device checks before release:

- On API 28, allow/deny the storage prompt; on API 29+ confirm no storage/media prompt appears.
- Share a public video, choose each available quality, and check saved audio/video playback.
- Rotate, switch apps, and reopen during a download; progress should reconnect to the system task.
- Cancel a transfer and confirm its partial file is removed; a previous completed file must remain.
- Try a restricted link, an expired URL, an offline connection, and insufficient storage.

A build/unit-test pass does not replace live Facebook and physical-device verification. The current target SDK remains 34; review distribution requirements before a store release.

## License and usage

This project is for learning and personal use only. Commercial use is not permitted. Download only videos you own or have permission to save, and comply with applicable laws and Facebook's terms of service. The developers are not liable for misuse.

V-Get is not affiliated with or endorsed by Facebook. Questions and suggestions are welcome through Issues or Pull Requests.
