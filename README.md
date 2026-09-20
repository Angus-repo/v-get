# V-Get - Social Video Downloader

V-Get downloads public Facebook, YouTube, Instagram and Threads videos on Android. Paste a video link or share it directly to V-Get from another app.

> Looking for the Traditional Chinese guide? Check out [README_zh_TW.md](README_zh_TW.md).

## Features
- ✨ Clean and intuitive user interface
- 📱 Facebook videos, YouTube videos/Shorts, Instagram posts/Reels and Threads video posts
- 📊 Real-time download progress updates
- 💾 Automatically saves to the `Downloads/V-Get` folder
- 🔐 Handles runtime permissions for you
- 🌐 Detects the platform automatically, including shared text and short links
- 🎬 Merges separate YouTube audio/video streams with FFmpeg
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
   - Alternatively, use another app’s *Share* menu and select *V-Get*; tap *Download* to confirm

3. **Download the video**
   - Hit *Download*
   - The app analyzes the link and shows progress updates
   - Receive a completion message with the saved location

4. **Watch your video**
   - Files are saved under `Downloads/V-Get/`
   - Open with any media player you prefer

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

## Project Structure

```
app/
├── src/main/
│   ├── java/com/vget/app/
│   │   ├── MainActivity.kt              # Main activity
│   │   ├── network/
│   │   │   ├── VideoExtractor.kt        # Video URL extractor
│   │   │   ├── VideoDownloader.kt       # Progressive downloads
│   │   │   ├── VideoDownloadService.kt  # Platform routing
│   │   │   ├── VideoSource.kt           # URL validation and normalization
│   │   │   ├── YtDlpDownloader.kt       # YouTube/Instagram engine
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

Pull requests and pushes to `main` also run the Android build in GitHub Actions. A successful run uploads `v-get-debug` containing an installable debug APK and the test/lint reports.

## Tech Stack

- **Kotlin** – Primary language
- **Material Design Components** – UI toolkit
- **OkHttp** – HTTP client
- **Kotlin Coroutines** – Asynchronous programming
- **ViewBinding** – Type-safe view access
- **AndroidX Libraries** – Jetpack components

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
- Threads selects the requested post ID from public page data; it never substitutes a recommended video
- Threads support requires public progressive video URLs in the page. Login walls, DASH-only posts or layout changes may prevent extraction
- Private-account videos require authentication (not supported)
- Facebook Stories are not currently supported

## Roadmap

- [ ] Batch downloads
- [ ] Quality selection (HD / SD)
- [ ] Video preview before download
- [x] YouTube, Instagram and Threads video support
- [ ] Download history
- [ ] Dark mode enhancements

## Troubleshooting

### Download failed

1. Check your network connection
2. Verify the URL format
3. Make sure storage permissions are granted
4. Copy the link again and retry
5. The first YouTube/Instagram download updates the engine automatically (internet required). If extraction fails later, tap **Update download engine**. Updates come from the official yt-dlp stable release; failure falls back to the bundled engine and can be retried.

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
- Unit tests cover URL variants, deceptive domains, requested-post selection, mixed carousels, signed URLs, completed-file validation and engine arguments.
- Device checks: public clips from all four platforms, merged YouTube audio/video, cancellation, Android 9 and 10+ storage, and private/deleted/rate-limited failures. Unit tests do not guarantee live platform availability.

## License

This project is for learning and personal use only. Commercial use is not permitted.

## Disclaimer

V-Get is provided for educational and research purposes. You are responsible for complying with local laws and the source platforms' terms of service. The developers are not liable for any misuse.

## Contact

Open an issue or submit a pull request if you have questions or suggestions.

---

**Note:** This project is not affiliated with or endorsed by Facebook, YouTube, Instagram or Threads.
