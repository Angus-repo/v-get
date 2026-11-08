import SwiftUI
import Security

struct ServiceSettings: View {
    @AppStorage("serviceURL") private var endpoint = ""
    @State private var token = Keychain.read()
    @State private var message = ""
    var body: some View {
        Form {
            Section("選用的自架下載服務") {
                Text("YouTube、Instagram 及 MP3 需要下載服務。設定後，使用這些功能時會將影片連結傳送至你指定的服務。Facebook、Threads 與小紅書的公開直連影片會在手機解析與下載。")
                    .font(.footnote)
                TextField("https://你的服務網址", text: $endpoint).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("存取權杖", text: $token)
                Button("儲存權杖") {
                    message = Keychain.save(token) ? "已儲存" : "儲存失敗，請再試一次"
                }
                if !message.isEmpty { Text(message) }
            }
            Section("使用說明") {
                Text("下載時請保持 V-Get 在前景。影片存於「檔案」App → 我的 iPhone → V-Get → V-Get。")
                Text("預覽會先下載所選畫質；播放能力取決於 iOS 支援的編碼。僅支援免登入的公開影片。")
                Text("V-Get 2.0.0 · Kotlin Multiplatform")
            }
        }.navigationTitle("設定")
    }
}

enum Keychain {
    private static let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "com.vget.app.engine", kSecAttrAccount as String: "token"]
    static func read() -> String {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }
    static func save(_ value: String) -> Bool {
        if value.isEmpty { let status = SecItemDelete(query as CFDictionary); return status == errSecSuccess || status == errSecItemNotFound }
        let attrs: [String: Any] = [kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let result = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if result == errSecSuccess { return true }
        guard result == errSecItemNotFound else { return false }
        return SecItemAdd(query.merging(attrs) { _, new in new } as CFDictionary, nil) == errSecSuccess
    }
}
