# 固定 APK 簽章

V-Get 從 **1.2.1** 起固定使用同一把已備份的簽章金鑰。憑證 SHA-256：

```text
4b69dff09888669e65c5d7a9bd4bc607ff2e902f67c659481d28d696ad0afd8c
```

這是憑證的公開指紋，不是私鑰。實際金鑰的備份檔名為 `V-Get-debug-signing.keystore`，只存放在私人備份中，不可提交至 Git、PR、Actions 產物或日誌。Android 系統產生的其他 `debug.keystore` 不能替代它。

## 本機建置

1. 取回已備份的 `V-Get-debug-signing.keystore`。
2. 複製到專案的 `signing/v-get.keystore`（已加入 `.gitignore`），或將 `VGET_KEYSTORE_FILE` 設為該檔案的絕對路徑。
3. 執行 `./gradlew :app:verifyVGetSigning` 核對憑證與私鑰。
4. 執行 `./gradlew testDebugUnitTest assembleDebug lintDebug`。

這把既有 Android 測試金鑰的 alias 為 `androiddebugkey`，keystore 與 key 密碼均為 Android 的預設值 `android`；這些值不等同私鑰。Gradle 預設沿用這組值，可透過 `VGET_KEY_ALIAS`、`VGET_KEYSTORE_PASSWORD`、`VGET_KEY_PASSWORD` 覆寫。金鑰路徑也可用 Gradle 屬性 `vgetKeystoreFile` 設定。

Debug 與 release 都使用這把金鑰。每次簽署前會核對私鑰與憑證指紋；缺少金鑰、密碼錯誤或憑證不同時，打包會失敗，不會另生金鑰。測試及 lint 不需要金鑰。

後續版本維持 application ID `com.vget.app`，並在發行新版時增加 `versionCode`，即可保留 App 資料進行覆蓋更新。1.2.0 使用另一把金鑰，升至 1.2.1 仍需重新安裝一次。此金鑰仍是測試用途，保留它不代表應用程式已成為正式發行版本。

## GitHub Actions

在 **Angus-repo/v-get → Settings → Secrets and variables → Actions** 新增 repository secret：

| Secret | 內容 |
| --- | --- |
| `VGET_KEYSTORE_BASE64` | 上述同一個 keystore 檔案的 Base64 編碼 |

將 Base64 值直接填入 Secret，不要貼到 issue、PR、聊天或公開檔案。Base64 僅是編碼，不是加密；不可存進程式庫。

Actions 的 pull request 只執行測試與 lint。推送 `main` 或手動觸發時，會由 Secret 還原金鑰、檢查固定憑證後建置 APK，最後清理暫存金鑰。Secret 尚未設定時會明確停止 APK 建置，不會提供不同簽章的安裝檔。

私鑰備份與 GitHub Secret 是不同存放位置；加入此設定不會自動建立 Secret，首次使用 Actions 打包前仍需將既有金鑰設定一次。
