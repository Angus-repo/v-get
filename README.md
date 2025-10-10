# V-Get - Facebook 影片下載器

一個簡單易用的 Android 應用程式，專門用於下載 Facebook 影片（包含留言中的影片）。

## 功能特色

- ✨ 簡潔直觀的使用者介面
- 📱 支援 Facebook 影片和留言影片下載
- 📊 即時下載進度顯示
- 💾 自動儲存至 Downloads/V-Get 資料夾
- 🔐 完整的權限管理
- 🌐 支援多種 Facebook URL 格式

## 系統需求

- Android 7.0 (API Level 24) 或更高版本
- 網路連線權限
- 儲存空間權限

## 使用方式

1. **複製影片連結**
   - 在 Facebook 應用程式或網頁中，找到想要下載的影片
   - 點擊分享按鈕，選擇「複製連結」

2. **貼上連結**
   - 開啟 V-Get 應用程式
   - 點擊「貼上」按鈕，自動貼上剛才複製的連結
   - 或手動在輸入框中貼上連結

3. **下載影片**
   - 點擊「下載」按鈕
   - 應用程式會自動分析影片連結
   - 顯示下載進度
   - 下載完成後會顯示儲存位置

4. **查看影片**
   - 下載完成的影片會儲存在：`Downloads/V-Get/` 資料夾
   - 可使用任何影片播放器開啟

## 支援的 URL 格式

- `https://www.facebook.com/watch/?v=xxxxx`
- `https://www.facebook.com/username/videos/xxxxx`
- `https://fb.watch/xxxxx`
- `https://m.facebook.com/...`
- Facebook 留言中的影片連結

## 專案結構

```
app/
├── src/main/
│   ├── java/com/vget/app/
│   │   ├── MainActivity.kt              # 主要活動
│   │   ├── network/
│   │   │   ├── VideoExtractor.kt        # 影片連結提取器
│   │   │   └── VideoDownloader.kt       # 影片下載管理器
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

- Android Studio Arctic Fox 或更新版本
- JDK 8 或更高版本
- Android SDK API Level 34

### 建置步驟

1. **Clone 專案**
   ```bash
   git clone https://github.com/yourusername/v-get.git
   cd v-get
   ```

2. **開啟專案**
   - 使用 Android Studio 開啟專案
   - 等待 Gradle 同步完成

3. **建置應用程式**
   ```bash
   ./gradlew build
   ```

4. **安裝至裝置**
   ```bash
   ./gradlew installDebug
   ```

## 使用的技術與函式庫

- **Kotlin** - 主要開發語言
- **Material Design Components** - UI 元件
- **OkHttp** - HTTP 客戶端
- **Kotlin Coroutines** - 非同步處理
- **ViewBinding** - 視圖綁定
- **AndroidX Libraries** - Android 擴充函式庫

## 權限說明

應用程式需要以下權限：

- `INTERNET` - 下載影片所需
- `READ_EXTERNAL_STORAGE` (Android 10 及以下) - 讀取儲存空間
- `WRITE_EXTERNAL_STORAGE` (Android 9 及以下) - 寫入儲存空間
- `READ_MEDIA_VIDEO` (Android 13+) - 讀取媒體影片

## 注意事項

⚠️ **重要提醒**

1. 請確保您有權下載該影片
2. 尊重版權，僅供個人使用
3. 不要下載受版權保護的內容
4. 某些私人影片或受限影片可能無法下載
5. Facebook 可能會變更其 API，導致部分功能失效

## 已知問題

- 某些直播影片可能無法下載
- 私人帳號的影片需要登入才能下載（目前不支援）
- Facebook Stories 暫不支援

## 未來計劃

- [ ] 支援批次下載
- [ ] 支援選擇影片品質（HD/SD）
- [ ] 加入影片預覽功能
- [ ] 支援 Instagram 影片下載
- [ ] 加入下載歷史記錄
- [ ] 深色模式最佳化

## 疑難排解

### 下載失敗

1. 確認網路連線正常
2. 確認 URL 格式正確
3. 確認已授予儲存空間權限
4. 嘗試重新複製連結

### 找不到影片

- 確認影片是公開的
- 某些影片可能有地區限制
- 嘗試在瀏覽器中開啟連結確認影片存在

### 權限問題

- 進入系統設定 > 應用程式 > V-Get > 權限
- 手動開啟儲存空間權限

## 授權

本專案僅供學習和個人使用。請勿用於商業用途。

## 免責聲明

此應用程式僅供教育和研究目的。使用者應自行承擔使用本應用程式的責任，並確保遵守所有適用的法律和 Facebook 的服務條款。開發者不對任何因使用本應用程式而產生的問題負責。

## 聯絡方式

如有問題或建議，歡迎提出 Issue 或 Pull Request。

---

**注意：** 本應用程式與 Facebook 無關，不代表 Facebook 的官方產品。
