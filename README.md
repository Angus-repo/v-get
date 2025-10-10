# V-Get - Facebook Video Downloader

V-Get is an easy-to-use Android application built for downloading Facebook videos, including clips shared inside comments.

> Looking for the Traditional Chinese guide? Check out [README_zh_TW.md](README_zh_TW.md).

## Features
- ✨ Clean and intuitive user interface
- 📱 Supports downloading regular Facebook videos and comment videos
- 📊 Real-time download progress updates
- 💾 Automatically saves to the `Downloads/V-Get` folder
- 🔐 Handles runtime permissions for you
- 🌐 Works with multiple Facebook URL formats

## Requirements

- Android 7.0 (API Level 24) or higher
- Internet access
- Storage permission (varies by Android version)

## How It Works

1. **Copy the video link**
   - Locate the target video in the Facebook app or on the web
   - Tap *Share* and choose *Copy link*

2. **Paste the link**
   - Open the V-Get app
   - Tap *Paste* to auto-fill the copied URL, or enter it manually

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

## Project Structure

```
app/
├── src/main/
│   ├── java/com/vget/app/
│   │   ├── MainActivity.kt              # Main activity
│   │   ├── network/
│   │   │   ├── VideoExtractor.kt        # Video URL extractor
│   │   │   └── VideoDownloader.kt       # Download manager
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

- Android Studio Arctic Fox or newer
- JDK 8+
- Android SDK API Level 34

### Build Steps

1. **Clone the project**
   ```bash
   git clone https://github.com/yourusername/v-get.git
   cd v-get
   ```

2. **Open in Android Studio**
   - Import the project
   - Wait for Gradle sync to finish

3. **Build the app**
   ```bash
   ./gradlew build
   ```

4. **Install on a device or emulator**
   ```bash
   ./gradlew installDebug
   ```

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
- `READ_EXTERNAL_STORAGE` (Android 10 and below)
- `WRITE_EXTERNAL_STORAGE` (Android 9 and below)
- `READ_MEDIA_VIDEO` (Android 13+)

## Important Notes

⚠️ **Please remember:**

1. Download videos only when you have the right to do so
2. Respect copyright and use downloads for personal purposes
3. Avoid downloading copyrighted material
4. Private or restricted videos might not be accessible
5. Facebook platform changes can break functionality without warning

## Known Limitations

- Some live streams cannot be downloaded
- Private-account videos require authentication (not supported)
- Facebook Stories are not currently supported

## Roadmap

- [ ] Batch downloads
- [ ] Quality selection (HD / SD)
- [ ] Video preview before download
- [ ] Instagram video support
- [ ] Download history
- [ ] Dark mode enhancements

## Troubleshooting

### Download failed

1. Check your network connection
2. Verify the URL format
3. Make sure storage permissions are granted
4. Copy the link again and retry

### Cannot find the video

- Ensure the video is public
- Be aware of regional restrictions
- Open the link in a browser to confirm availability

### Permission issues

- Go to *Settings > Apps > V-Get > Permissions*
- Enable the required storage/media permissions manually

## License

This project is for learning and personal use only. Commercial use is not permitted.

## Disclaimer

V-Get is provided for educational and research purposes. You are responsible for complying with local laws and Facebook's terms of service. The developers are not liable for any misuse.

## Contact

Open an issue or submit a pull request if you have questions or suggestions.

---

**Note:** This project is not affiliated with or endorsed by Facebook.
