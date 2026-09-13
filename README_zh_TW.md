# V-Get — Android Facebook 影片下載 App

V-Get 是使用 Kotlin 製作的原生 Android App。貼上公開影片連結後，在手機選擇畫質並下載。影片解析直接在裝置上進行，無須架設伺服器、設定 API Key 或登入 Facebook。

[English guide](README.md)

## 功能

- 適合手機的可捲動介面、大型按鈕，以及淺色／深色主題。
- 支援貼上網址，或從 Facebook「分享」文字至 V-Get。
- 先查看影片標題，再選擇實際取得的 HD／SD MP4。Open Graph 備援來源僅標示 MP4，不推測畫質。
- 使用 Android DownloadManager 在背景下載、重試與顯示完成通知。
- 顯示下載進度，支援取消及開啟已下載影片。
- 保存最近一筆系統下載 ID，重新開啟 App 可恢復狀態；目前不是完整的下載歷史。
- 檔案儲存於 `Download/V-Get/`，可按「我的下載」查看。

## 使用方式

1. 複製公開影片本身的連結，或使用「分享 → V-Get」。
2. 按「貼上連結」，再按「解析影片」。
3. 選擇可用畫質，按「下載到手機」。
4. 完成後按「播放影片」，或透過「我的下載」開啟檔案。

分享只會填入網址，選擇下載後才會開始傳輸。有工作進行時，請先完成或取消，再分享另一個連結。

## 支援範圍

接受 Facebook 標準主網域、手機版、`fb.com` 與 `fb.watch` 連結，包含 `/watch/?v=…`、`/reel/…`、`/…/videos/…`。短網址與分享網址必須能導向可公開存取的影片頁面。

只支援頁面直接提供的完整 MP4 影片。需要登入、私人影片、直播、限時動態、DASH／HLS 及分離影音串流目前不支援。App 不使用登入 Cookie、帳號密碼、後端解析服務或外部命令列程式。留言中的影片請複製影片本身的網址；留言串連結會被拒絕，以免下載到原貼文。

Facebook 頁面格式或地區／網路限制可能導致解析失敗，公開影片不保證一定可下載。有影片 ID 時會比對指定影片，多個無法辨識的結果會顯示錯誤，不會直接下載推薦影片。下載連結過期時請重新解析。

## 開發與安裝

- Android 7.0 以上（API 24+）。
- JDK **17**、Android SDK **34**，以及支援 AGP 8.1 的 Android Studio。
- 專案附有 Gradle **8.5** wrapper，僅有 `:app` 模組。
- 不需要 Spring Boot、Node.js、Python 或 FFmpeg 執行環境。

```bash
git clone https://github.com/Angus-repo/v-get.git
cd v-get
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:installDebug
```

設定 `ANDROID_HOME`，或在不提交版本控制的 `local.properties` 設定 `sdk.dir`。Debug APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。

## 原生架構

| 元件 | 用途 |
| --- | --- |
| MainActivity / XML | 手機介面、剪貼簿、分享、權限及播放 |
| MainViewModel | 旋轉時保留狀態、取消工作、恢復最近的系統下載 |
| FacebookUrl / FacebookPageParser | 網址驗證、影片 ID 比對、實際畫質解析 |
| VideoExtractor | 手機端 OkHttp 請求，支援取消、頁面大小限制與重新導向驗證 |
| VideoDownloader | Android 系統下載、進度、取消與安全檔名 |

使用 AndroidX、Material Components、Kotlin coroutines、OkHttp、Gson 與 jsoup。專案不包含網頁或伺服器模組。

## 權限

- `INTERNET`：取得公開頁面與下載影片。
- `WRITE_EXTERNAL_STORAGE`：僅 Android 7–9（API 24–28）在開始下載時要求。
- Android 10 以上使用系統下載服務，不要求廣泛的儲存／媒體讀取權限，也不讀取使用者的影片庫。

API 依據：[DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)、[公開下載目錄](https://developer.android.com/reference/android/app/DownloadManager.Request#setDestinationInExternalPublicDir(java.lang.String,%20java.lang.String))。

## 驗證與發佈前檢查

單元測試涵蓋網址與主機驗證、簽章參數保留、指定影片選取、巢狀／舊版資料、模糊結果、非支援串流、安全檔名與分享連結重新導向。HTTP 回歸測試確認分享網址及影片頁請求都包含導覽標頭；缺少這些標頭曾導致公開分享連結回傳 HTTP 400。App 不使用登入或瀏覽器 Cookie。

預設會跳過實際連線測試，其餘測試使用合成資料。若要指定公開連結，驗證解析結果與每個畫質的 MP4 檔頭，可執行：

```bash
./gradlew :app:testDebugUnitTest -PvgetLiveUrl="https://www.facebook.com/share/v/你的連結/"
```

這項測試會實際連線，但不涵蓋 Android 系統下載管理員與手機播放器。1.0.1 版（version code 2）包含分享連結 HTTP 400 的修正。

發佈前請在裝置確認：

- API 28 的儲存權限允許／拒絕流程；API 29 以上不出現媒體權限要求。
- 分享公開影片、選擇畫質，並播放下載檔案確認影音。
- 下載時旋轉、切換 App、重新開啟，進度可接回系統工作。
- 取消後清除未完成檔案，先前完成的影片仍保留。
- 私人連結、過期連結、斷網與儲存空間不足的錯誤提示。

建置與單元測試通過不等於已完成 Facebook 實際連線和實機驗證。目前維持 target SDK 34，商店發佈前請確認上架要求。

## 授權與使用

本專案僅供學習和個人使用，請勿用於商業用途。請只下載你擁有或已獲授權的影片，並遵守適用法律及 Facebook 服務條款。開發者不對不當使用負責。

本應用程式與 Facebook 無關，並非官方產品。如有問題或建議，歡迎提出 Issue 或 Pull Request。
