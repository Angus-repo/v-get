# V-Get - 社群影片下載器

下載 Facebook、YouTube、Instagram、Threads 與小紅書公開影片的 Android 應用程式。可貼上影片連結或整段分享文字，也可從其他 App 直接分享至 V-Get。

## 1.3.1 修正

- 小紅書主要影片網址無法連線或下載不完整時，依序嘗試該畫質明確提供的備援網址；影片與 MP3 均適用，不切換其他畫質或筆記。
- 備援重試前清除未完成的檔案；取消、登入要求、限流、儲存或轉換錯誤不會觸發備援下載。
- 錯誤畫面標明「分析畫質／下載影片／下載 MP3」，保留 HTTP 狀態碼，並區分 DNS、安全連線與連線中斷。隱藏簽名網址與權杖。
- 沿用既有簽章，versionCode 升至 6。

## 1.3.0 新增功能

- 小紅書／RedNote 影片筆記：自動辨識中文分享文字中的 `xhslink.cn`、`xhslink.com` 短網址，以及完整筆記網址；保留 `xsec_token` 等必要參數，只解析指定筆記的公開影片。
- 選擇解析度時同時顯示檔案容量。使用來源提供的大小，或從影片標頭查詢；位元率估算、影音合併及 MP3 容量會標示「約」。無法取得時顯示「容量未提供」。
- 「下載 MP3 音訊」會將所選影片的音軌轉為 192 kbps MP3。YouTube 分離影音只下載已配對的音軌；其他來源可能需先暫存影片。沒有音軌時無法轉換。
- 影片與 MP3 均存入 `Downloads/V-Get/`，下載完成後可在 App 內播放。

小紅書若導向登入或驗證頁，會提示限制，不會改抓其他筆記。本版本不提供登入或 Cookie 匯入。保留 1.2.1 的固定簽章，目前版本為 1.3.1（versionCode 6）。

## 功能特色

- ✨ 簡潔直觀的使用者介面
- 📱 支援 Facebook、YouTube／Shorts、Instagram 貼文／Reels、Threads 與小紅書影片貼文
- 📊 即時下載進度顯示
- 💾 自動儲存至 Downloads/V-Get 資料夾
- 🔐 完整的權限管理
- 🌐 自動辨識平台、分享文字與短網址
- 🎬 自動合併 YouTube 的影像與音訊
- 🎚️ 先分析連結，再選擇來源實際提供的畫質下載
- ▶️ 在 App 內試播所選畫質，也可播放剛下載的檔案
- 🔄 可在 App 內更新 YouTube／Instagram 下載引擎

## 系統需求

- Android 7.0 (API Level 24) 或更高版本
- 網路連線權限
- 儲存空間權限

## 使用方式

1. **複製影片連結**
   - 在 Facebook、YouTube、Instagram 或 Threads 中，找到想要下載的影片
   - 點擊分享按鈕，選擇「複製連結」

2. **貼上連結**
   - 開啟 V-Get 應用程式
   - 點擊「貼上」按鈕，自動貼上剛才複製的連結
   - 或手動在輸入框中貼上連結
   - 也可在其他 App 的「分享」選單選擇 V-Get

3. **選擇畫質並下載**
   - 點擊「分析畫質」，列出來源實際提供的選項
   - 選擇畫質，再點擊「試播所選畫質」或「下載所選畫質」
   - 分開提供的影音會配對試播，下載時自動合併；不會自行換成其他畫質
   - 下載完成後會顯示儲存位置

4. **查看影片**
   - 下載完成的影片會儲存在：`Downloads/V-Get/` 資料夾
   - 點擊「播放上次下載」可直接在 App 內檢查，也可使用其他影片播放器

YouTube／Instagram 依來源資料顯示解析度、幀率及檔案格式；相同條件優先選擇較普遍支援的編碼。Threads 列出指定貼文提供的不同完整影片版本；Facebook 有提供時列出 HD／SD。只有一種版本就只顯示一個選項，未提供解析度時明確標示，不推測數值。

## 支援的 URL 格式

- `https://www.facebook.com/watch/?v=xxxxx`
- `https://www.facebook.com/username/videos/xxxxx`
- `https://fb.watch/xxxxx`
- `https://m.facebook.com/...`
- Facebook 留言中的影片連結

| 平台 | 支援格式 |
| --- | --- |
| YouTube | `youtube.com/watch?v=VIDEO_ID`、`youtu.be/VIDEO_ID`、`youtube.com/shorts/VIDEO_ID` |
| Instagram | `instagram.com/p/CODE/`、`instagram.com/reel/CODE/`、`instagram.com/tv/CODE/`、分享短連結 |
| Threads | `threads.com/@user/post/CODE`、`threads.net/@user/post/CODE`、`threads.com/t/CODE`、`threads.com/share/CODE` |
| 小紅書／RedNote | `xhslink.cn/o/CODE`、`xhslink.com/m/CODE`、`xiaohongshu.com/explore/NOTE_ID`、`xiaohongshu.com/discovery/item/NOTE_ID`、`rednote.com/explore/NOTE_ID` |

## 專案結構

```
app/
├── src/main/
│   ├── java/com/vget/app/
│   │   ├── MainActivity.kt              # 主要活動
│   │   ├── PlayerActivity.kt            # 所選畫質試播與已下載檔案播放
│   │   ├── network/
│   │   │   ├── VideoExtractor.kt        # 影片連結提取器
│   │   │   ├── VideoDownloader.kt       # 直接下載影片
│   │   │   ├── VideoDownloadService.kt  # 平台分流
│   │   │   ├── VideoSource.kt           # 網址驗證與正規化
│   │   │   ├── YtDlpDownloader.kt       # YouTube／Instagram 引擎
│   │   │   ├── YtDlpMetadataParser.kt   # 畫質、音軌配對與試播串流
│   │   │   ├── VideoDetails.kt          # 畫質選項與已儲存影片資料
│   │   │   ├── ThreadsPageParser.kt     # 指定 Threads 貼文解析
│   │   │   └── VideoStorage.kt          # MediaStore 與舊版儲存
│   │   └── utils/
│   │       └── PermissionHelper.kt      # 權限管理工具
│   ├── res/
│   │   ├── layout/
│   │   │   └── activity_main.xml        # 主介面佈局
│   │   ├── values/
│   │   │   ├── strings.xml              # 字串資源
│   │   │   ├── colors.xml               # 顏色定義
│   │   │   └── themes.xml               # 主題樣式
│   │   └── xml/
│   │       ├── backup_rules.xml
│   │       └── data_extraction_rules.xml
│   └── AndroidManifest.xml              # 應用程式配置
├── build.gradle                          # 應用程式建置配置
└── proguard-rules.pro                    # ProGuard 規則
```

## 開發環境設置

### 前置要求

- Android Studio Giraffe 或更新版本
- JDK 17
- Android SDK API Level 34

### 建置步驟

1. **Clone 專案**
   ```bash
   git clone https://github.com/Angus-repo/v-get.git
   cd v-get
   ```

2. **開啟專案**
   - 使用 Android Studio 開啟專案
   - 等待 Gradle 同步完成

3. **建置應用程式**
   ```bash
   ./gradlew testDebugUnitTest assembleDebug lintDebug
   ```

4. **安裝至裝置**
   ```bash
   ./gradlew installDebug
   ```

APK 固定沿用 V-Get 1.2.1 的簽章，後續版本可覆蓋更新。請先取回私人備份的金鑰並依[簽章設定](docs/SIGNING.md)配置；金鑰遺失或憑證不同時會停止打包。

Pull Request 會執行不需簽章金鑰的測試與 lint。推送至 `main` 或手動執行時，另使用 repository secret `VGET_KEYSTORE_BASE64` 建置並提供 `v-get-debug` APK；首次使用前需設定此 Secret。各次執行都會提供測試與 lint 報告。

## 使用的技術與函式庫

- **Kotlin** - 主要開發語言
- **Material Design Components** - UI 元件
- **OkHttp** - HTTP 客戶端
- **Kotlin Coroutines** - 非同步處理
- **ViewBinding** - 視圖綁定
- **AndroidX Libraries** - Android 擴充函式庫
- **Media3 ExoPlayer** - App 內串流試播與本機播放

## 權限說明

應用程式需要以下權限：

- `INTERNET` - 下載影片所需
- `WRITE_EXTERNAL_STORAGE` - 僅 Android 7～9 需要
- Android 10 以上使用 MediaStore 儲存新下載的影片，不需取得既有相片或影片的讀取權限。

## 注意事項

⚠️ **重要提醒**

1. 請確保您有權下載該影片
2. 尊重版權，僅供個人使用
3. 不要下載受版權保護的內容
4. 某些私人影片或受限影片可能無法下載
5. 各平台可能調整網頁或限制流量，導致暫時無法解析

## 已知問題

- 僅支援不需登入的公開影片；不支援直播、尚未開始的影片或 DRM 內容
- 不批次下載播放清單或帳號；多影片貼文只下載第一支影片
- 下載期間請保持 App 開啟；活動結束時會取消工作
- 需要暫存空間來下載、合併影音並複製完成的影片
- 線上試播需要可直接播放或 HLS 的網址；僅提供片段的格式須先下載再播放，選取時會顯示說明
- 播放能力取決於裝置支援的編碼；高解析度編碼無法播放時，可改選來源提供的 H.264 版本
- 影片網址有時效；試播或所選畫質失效時，請重新分析連結
- Threads 依貼文 ID 尋找指定影片，不會以推薦影片替代
- 支援 Threads 貼文內嵌的 Instagram Reels／影片；僅讀取該篇貼文的明確附件，不自動跟隨引用貼文或任意外部連結
- Threads 頁面必須提供公開的完整影片網址；登入限制、僅提供 DASH 或網頁結構變動可能導致無法解析
- 私人帳號的影片需要登入才能下載（目前不支援）
- Facebook Stories 暫不支援

## 未來計劃

- [ ] 支援批次下載
- [x] 下載前選擇來源提供的畫質
- [x] 所選畫質試播與已下載檔案播放
- [x] 支援 YouTube、Instagram 與 Threads 影片下載
- [ ] 加入下載歷史記錄
- [ ] 深色模式最佳化

## 疑難排解

### 下載失敗

1. 確認網路連線正常
2. 確認 URL 格式正確
3. 確認已授予儲存空間權限
4. 嘗試重新複製連結
5. 首次分析 YouTube／Instagram 時會自動更新引擎（需要網路）；之後若解析失敗，可點擊「**更新下載引擎**」。更新來源為 yt-dlp 官方穩定版；更新失敗會嘗試內建版本，之後可再更新。

### 找不到影片

- 確認影片是公開的
- 某些影片可能有地區限制
- 嘗試在瀏覽器中開啟連結確認影片存在

### 權限問題

- 進入系統設定 > 應用程式 > V-Get > 權限
- Android 7～9 可手動開啟儲存空間權限；Android 10 以上不需要

## 下載引擎與驗證

- YouTube／Instagram 使用 [youtubedl-android 0.18.1](https://github.com/yausername/youtubedl-android)（GPL-3.0），包含 Python、QuickJS 與 yt-dlp；另使用 FFmpeg 合併影音。原生函式庫使 APK 體積增加。
- Threads 使用獨立的公開頁面解析器；必要時讀取公開連結預覽頁面。不支援登入、匯入 Cookie 或私人帳號。
- 單元測試涵蓋網址格式、偽造網域、貼文定位、混合輪播、簽章網址保留、下載完成檔案判斷、指定畫質參數、畫質解析、音軌配對與試播請求標頭。
- 待實機驗證：四平台公開影片、所選畫質與聲音試播、下載檔案播放、取消下載、Android 9／10+ 儲存，以及私人／已刪除／限流錯誤。建置與單元測試不代表已完成實機播放驗證，也不保證外部平台即時可用性。

## 授權

本專案僅供學習和個人使用。請勿用於商業用途。

## 免責聲明

此應用程式僅供教育和研究目的。使用者應自行承擔使用本應用程式的責任，並確保遵守所有適用的法律和 來源平台的服務條款。開發者不對任何因使用本應用程式而產生的問題負責。

## 聯絡方式

如有問題或建議，歡迎提出 Issue 或 Pull Request。

---

**注意：** 本應用程式並非 Facebook、YouTube、Instagram 或 Threads 的官方產品。
