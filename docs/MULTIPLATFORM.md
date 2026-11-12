# V-Get 2.0 — Android + iOS

V-Get now has a Kotlin Multiplatform `shared` module used by the existing Android application and a native SwiftUI iOS application. Sharing business logic does not require replacing the Android UI with Compose. This keeps the established Android download, playback, DNS retry and signing behavior.

## What is shared

- URL recognition/normalization, platform allowlists, exact post identity and signed-query preservation.
- Facebook, Threads and Xiaohongshu HTML parsing (Ksoup), yt-dlp metadata parsing (kotlinx.serialization), quality/audio pairing and file sizes.
- Title-based filenames, UTF-8 length limits, NFC normalization and collision suffixes.
- A Ktor public-page workflow used on iOS, with bounded HTML responses, validated redirects, cancellation and explicit restricted-content messages.
- Existing parser/URL regression tests moved to `shared/src/commonTest`; JVM and iOS execute the same fixtures.

Android uses OkHttp and the existing on-device yt-dlp/FFmpeg engine. iOS uses Ktor/Darwin for inspection, URLSession download tasks for disk streaming and AVPlayer for playback. The iOS share extension stores one pending URL in the shared app group; open the main app to continue.

## Capability matrix

| Feature | Android | iOS |
| --- | --- | --- |
| Facebook / Threads / Xiaohongshu public progressive videos | On device | On device |
| YouTube / Instagram | On-device yt-dlp | Optional self-hosted service |
| MP3 192 kbps | On-device FFmpeg | Optional self-hosted service |
| Selected-quality preview | Streaming, including separate tracks | Downloads the exact selected quality to temporary storage, then plays |
| File storage | Downloads/V-Get, MediaStore | Documents/V-Get, Files and share sheet |
| Xiaohongshu encrypted-DNS retry | Preserved | System networking only |
| Login, cookie import, private/DRM/live content | Unsupported | Unsupported |

No service URL is built in. iOS does not silently send links to a third-party extraction site. Engine-dependent actions explain the missing service configuration. Configure your own HTTPS endpoint and token under Settings. The token is stored in Keychain; the endpoint is in UserDefaults. Source changes, region restrictions, IP-based media signatures and platform rate limits can still prevent extraction/download.

## Android

JDK 17, Android SDK 34, Gradle 8.14.3, Android Gradle Plugin 8.7.3 and Kotlin 2.2.21 are used. Application ID stays `com.vget.app`; version is 2.0.0 / 15.

```sh
./gradlew :shared:jvmTest :app:testDebugUnitTest :app:lintDebug
```

APK packaging still requires the retained certificate and private key described in [SIGNING.md](SIGNING.md). The migration does not generate a replacement key. Existing Android Actions behavior is retained.

## iOS

Requirements: macOS, Xcode 26.0/26.0.1, JDK 17 and XcodeGen (`brew install xcodegen`). Minimum deployment target: iOS 16.

```sh
cd iosApp
xcodegen generate
open VGet.xcodeproj
```

Select the `VGet` scheme and an iPhone simulator. The Xcode build phase builds and embeds `VGetShared.framework`. The Xcode project and plists are generated from `project.yml` so they have a single source of truth.

For a physical iPhone, set your development team on both `VGet` and `VGetShare`. Register/enable the `group.com.vget.app` App Group for both targets (or consistently change it in `project.yml`, `DownloadModel.swift` and `ShareViewController.swift`). If `com.vget.app` is unavailable for your Apple team, change the Apple bundle IDs in `project.yml`; the Android application ID must remain unchanged.

An unsigned simulator `.app` is not an installable iPhone IPA. Device installs/TestFlight require the appropriate Apple signing and provisioning. CI builds/tests the simulator app and compiles the device framework without publishing to TestFlight or the App Store.

```sh
./gradlew :shared:iosSimulatorArm64Test
# On an Intel Mac use :shared:iosX64Test instead.
```

Keep the app in the foreground while downloading. Going to the background cancels the local operation and incomplete downloads are removed. A remote engine process can continue until its timeout after the phone disconnects. Preview files are temporary; downloaded files use collision-safe page-title filenames. WebM/MKV/unsupported codecs can be saved/shared even when AVPlayer cannot play them.

## Optional engine service

See [server/README.md](../server/README.md) for the complete reference implementation and deployment instructions. It is not deployed automatically. Both engine endpoints require a bearer token and a user-supplied HTTPS reverse proxy. No Apple or Android signing secrets belong in this service.

## Verification boundaries

Unit tests and simulator builds establish parser behavior and compilation. They do not prove live availability of every source or playback on a physical iPhone. Before release, check public clips from all five sources, exact selected quality and audio, expired URLs, restricted Threads/Facebook pages, MP3 bytes, cancellation, low storage, Files export and the share extension. Test Android 9 and Android 10+ storage and validate the APK certificate before distributing an APK.
