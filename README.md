# V-Get - Social Video Downloader

V-Get downloads public Facebook, YouTube, Instagram, Threads and Xiaohongshu/RedNote videos on Android. Paste a video link or the complete share text, or share it directly to V-Get from another app.

## New in 1.3.0

- Xiaohongshu/RedNote video notes, including `xhslink.cn` and `xhslink.com` short links embedded in Chinese share text. Required share tokens are preserved; only streams attached to the requested note are selected.
- Quality choices show file size. Source metadata and HTTP headers supply exact sizes when available; bitrate estimates, merged streams and MP3 estimates are labelled approximate. Unknown sizes remain explicitly unavailable.
- Download the selected audio as a real 192 kbps MP3. Separate YouTube audio is selected directly; other sources may require downloading the video before conversion. Silent sources cannot produce MP3 audio.
- Both videos and MP3 files use `Downloads/V-Get/` and support playback after download.

Login or verification redirects stop with an actionable message; this version does not add login or cookie import. Version 1.3.0 (versionCode 5) retains the existing 1.2.1 signing key.

> Looking for the Traditional Chinese guide? Check out [README_zh_TW.md](README_zh_TW.md).

## Features
- ✨ Clean and intuitive user interface
- 📱 Facebook videos, YouTube videos/Shorts, Instagram posts/Reels, Threads and Xiaohongshu video posts
- 📊 Real-time download progress updates
- 💾 Automatically saves to the `Downloads/V-Get` folder
- 🔐 Handles runtime permissions for you
- 🌐 Detects the platform automatically, including shared text and short links
- 🎬 Merges separate YouTube audio/video streams with FFmpeg
- 🎚️ Analyze the link, then choose from the source's available qualities before downloading
- ▶️ Preview the selected quality and play the completed download inside the app
- 🔄 Update the YouTube/Instagram download engine from the app

## Requirements

- Android 7.0 (API Level 24) or higher
- Internet access
- Storage permission (varies by Android version)

## How It Works

1. **Copy the video link**
   - Locate the target video in Facebook, YouTube, Instagram or Threads
   - Tap *Share* and choose *Copy link*

2. **Paste the link**
   - Open the V-Get app
   - Tap *Paste* to auto-fill the copied URL, or enter it manually
   - Alternatively, use another app’s *Share* menu and select *V-Get*

3. **Choose a quality and download**
   - Tap **Analyze quality** (分析畫質)
   - Select one of the available qualities; only qualities reported by the source are listed
   - Tap **Preview** (試播所選畫質) to test playback, or **Download selected quality** (下載所選畫質)
   - Separate audio/video streams are paired for preview and merged when downloading; the app does not silently switch to another quality
   - Receive a completion message with the saved location

4. **Watch your video**
   - Files are saved under `Downloads/V-Get/`
   - Tap **Play last download** (播放上次下載) to check the saved file inside V-Get, or use another player

YouTube/Instagram choices show the reported resolution, frame rate and container. Equivalent formats prefer broadly supported codecs. Threads lists the distinct progressive versions in the target post. Facebook lists HD/SD when supplied. A source with only one version has one choice; missing dimensions are marked as unknown, never guessed.

## Supported URL Formats

- `https://www.facebook.com/watch/?v=xxxxx`
- `https://www.facebook.com/username/videos/xxxxx`
- `https://fb.watch/xxxxx`
- `https://m.facebook.com/...`
- Video links embedded in Facebook comments

| Platform | Supported formats |
| --- | --- |
| YouTube | `youtube.com/watch?v=VIDEO_ID`, `youtu.be/VIDEO_ID`, `youtube.com/shorts/VIDEO_ID` |
| Instagram | `instagram.com/p/CODE/`, `instagram.com/reel/CODE/`, `instagram.com/tv/CODE/`, share links |
| Threads | `threads.com/@user/post/CODE`, `threads.net/@user/post/CODE`, `threads.com/t/CODE`, `threads.com/share/CODE` |
| Xiaohongshu/RedNote | `xhslink.cn/o/CODE`, `xhslink.com/m/CODE`, `xiaohongshu.com/explore/NOTE_ID`, `xiaohongshu.com/discovery/item/NOTE_ID`, `rednote.com/explore/NOTE_ID` |

## Project Structure

```
app/
├── src/main/
│   ├── java/com/vget/app/
│   │   ├── MainActivity.kt              # Main activity
│   │   ├── PlayerActivity.kt            # Selected-quality preview and saved-file playback
│   │   ├── network/
│   │   │   ├── VideoExtractor.kt        # Video URL extractor
│   │   │   ├── VideoDownloader.kt       # Progressive downloads
│   │   │   ├── VideoDownloadService.kt  # Platform routing
│   │   │   ├── VideoSource.kt           # URL validation and normalization
│   │   │   ├── YtDlpDownloader.kt       # YouTube/Instagram engine
│   │   │   ├── YtDlpMetadataParser.kt   # Exact formats, audio pairing and preview streams
│   │   │   ├── VideoDetails.kt          # Quality choices and saved-video model
│   │   │   ├── ThreadsPageParser.kt     # Targeted Threads post parsing
│   │   │   └── VideoStorage.kt          # MediaStore / legacy storage
│   │   └── utils/
│   │       └── PermissionHelper.kt      # Permission helper
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml        # Primary layout
│   │   ├── values/
│   │   │   ├── strings.xml              # String resources
│   │   │   ├── colors.xml               # Color palette
│   │   │   └── themes.xml               # Theme definitions
│   │   └── xml/
│   │       ├── backup_rules.xml
│   │       └── data_extraction_rules.xml
│   └── AndroidManifest.xml              # App configuration
├── build.gradle                          # Module build script
└── proguard-rules.pro                    # ProGuard rules
```

## Development Setup

### Prerequisites

- Android Studio Giraffe or newer
- JDK 17
- Android SDK API Level 34

### Build Steps

1. **Clone the project**
   ```bash
   git clone https://github.com/Angus-repo/v-get.git
   cd v-get
   ```

2. **Open in Android Studio**
   - Import the project
   - Wait for Gradle sync to finish

3. **Build the app**
   ```bash
   ./gradlew testDebugUnitTest assembleDebug lintDebug
   ```

4. **Install on a device or emulator**
   ```bash
   ./gradlew installDebug
   ```

APK builds use the retained V-Get 1.2.1 signing key, allowing later versions to update the installed app. Restore the private keystore and follow [Signing setup](docs/SIGNING.md); missing or different keys stop packaging.

Pull requests run tests and lint without the signing key. Pushes to `main` and manual runs additionally build and upload `v-get-debug` using the `VGET_KEYSTORE_BASE64` repository secret. Configure that secret before using Actions to create APKs. Test/lint reports remain available on all runs.

## Tech Stack

- **Kotlin** – Primary language
- **Material Design Components** – UI toolkit
- **OkHttp** – HTTP client
- **Kotlin Coroutines** – Asynchronous programming
- **ViewBinding** – Type-safe view access
- **AndroidX Libraries** – Jetpack components
- **Media3 ExoPlayer** – In-app streaming and local playback

## Permission Usage

Depending on the Android version, the app may request:

- `INTERNET` – Required for downloading videos
- `WRITE_EXTERNAL_STORAGE` – Android 7–9 only
- Android 10+ uses MediaStore to create downloads; it does not request access to existing photos or videos.

## Important Notes

⚠️ **Please remember:**

1. Download videos only when you have the right to do so
2. Respect copyright and use downloads for personal purposes
3. Avoid downloading copyrighted material
4. Private or restricted videos might not be accessible
5. Platform changes or rate limits may temporarily prevent downloads

## Known Limitations

- Only public, non-login-gated videos are supported; live/upcoming streams and DRM content are not supported
- Playlists/accounts are not batch-downloaded. Multi-video posts download the first video only
- The app must remain open while downloading; leaving/destroying the activity cancels its job
- Temporary storage is required for downloading, merging and copying the completed video
- Streaming preview needs a playable direct/HLS URL. Fragment-only formats must be downloaded before playback; the app explains this when selected
- Playback depends on the device's codec support. If a high-resolution codec cannot play, try an available H.264 option
- Media URLs expire. Analyze the link again if preview or the chosen quality stops working
- Threads selects the requested post ID from public page data; it never substitutes a recommended video
- Instagram reels/videos attached inline to that Threads post are supported; quoted posts and arbitrary external links are not automatically followed
- Threads support requires public progressive video URLs in the page. Login walls, DASH-only posts or layout changes may prevent extraction
- Private-account videos require authentication (not supported)
- Facebook Stories are not currently supported

## Roadmap

- [ ] Batch downloads
- [x] Source quality selection before download
- [x] Selected-quality preview and completed-download playback
- [x] YouTube, Instagram and Threads video support
- [ ] Download history
- [ ] Dark mode enhancements

## Troubleshooting

### Download failed

1. Check your network connection
2. Verify the URL format
3. Make sure storage permissions are granted
4. Copy the link again and retry
5. The first YouTube/Instagram analysis updates the engine automatically (internet required). If extraction fails later, tap **Update download engine**. Updates come from the official yt-dlp stable release; failure falls back to the bundled engine and can be retried.

### Cannot find the video

- Ensure the video is public
- Be aware of regional restrictions
- Open the link in a browser to confirm availability

### Permission issues

- Go to *Settings > Apps > V-Get > Permissions*
- On Android 7–9, enable storage permission manually; Android 10+ needs no storage permission

## Download Engine and Validation

- YouTube/Instagram use [youtubedl-android 0.18.1](https://github.com/yausername/youtubedl-android) (GPL-3.0), including Python, QuickJS and yt-dlp, plus FFmpeg for audio/video merging. Native libraries increase APK size.
- Threads uses a separate public-page parser, including public link-preview HTML when needed. Login, cookies and private accounts are not supported.
- Unit tests cover URL variants, deceptive domains, requested-post selection, mixed carousels, signed URLs, completed-file validation, exact selected-format arguments, quality extraction, audio pairing and preview headers.
- Device checks still required: public clips from all four platforms, selected-quality streaming with audio, saved-file playback, cancellation, Android 9 and 10+ storage, and private/deleted/rate-limited failures. Build and unit tests do not establish on-device playback or live platform availability.

## License

This project is for learning and personal use only. Commercial use is not permitted.

## Disclaimer

V-Get is provided for educational and research purposes. You are responsible for complying with local laws and the source platforms' terms of service. The developers are not liable for any misuse.

## Contact

Open an issue or submit a pull request if you have questions or suggestions.

---

**Note:** This project is not affiliated with or endorsed by Facebook, YouTube, Instagram or Threads.
