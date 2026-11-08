import Foundation

struct Video: Decodable {
    let title: String
    let sourceUrl: String
    let platform: String
    let qualities: [Quality]
}
struct Quality: Decodable, Identifiable {
    let id: String
    let label: String
    let mp3Size: String?
    let container: String
    let silent: Bool
    let formatSelector: String?
    let audioFormatSelector: String?
    let directCandidates: [String]
    let preview: Preview?
}
struct Preview: Decodable { let video: Stream; let audio: Stream? }
struct Stream: Decodable { let url: String; let headers: [String: String]; let mimeType: String? }
struct AppFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

enum MediaPolicy {
    static func allowed(_ url: URL, platform: String) -> Bool {
        guard url.scheme == "https", url.user == nil, url.password == nil,
              url.port == nil || url.port == 443, let host = url.host?.lowercased() else { return false }
        let domains: [String]
        switch platform {
        case "FACEBOOK": domains = ["fbcdn.net", "facebook.com"]
        case "THREADS": domains = ["fbcdn.net", "cdninstagram.com"]
        case "XIAOHONGSHU": domains = ["xhscdn.com"]
        default: return false
        }
        return domains.contains { host == $0 || host.hasSuffix("." + $0) }
    }

    static func service(_ value: String) throws -> URL {
        guard let url = URL(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", let host = url.host, !host.isEmpty,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.port == nil || url.port == 443 else {
            throw AppFailure(message: "請設定有效的 HTTPS 下載服務網址")
        }
        return url
    }

    static func validateFile(_ url: URL, response: HTTPURLResponse, mp3: Bool) throws {
        guard (200...299).contains(response.statusCode) else {
            throw AppFailure(message: "下載失敗（HTTP \(response.statusCode)），請重新分析或確認服務設定")
        }
        let mime = response.mimeType?.lowercased() ?? ""
        guard !mime.contains("text"), !mime.contains("json") else {
            throw AppFailure(message: "來源回傳了網頁或錯誤訊息，並非媒體檔案")
        }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let bytes = [UInt8](try handle.read(upToCount: 16) ?? Data())
        let valid: Bool
        if mp3 {
            valid = bytes.starts(with: [0x49, 0x44, 0x33]) || (bytes.count >= 2 && bytes[0] == 0xff && bytes[1] & 0xe0 == 0xe0)
        } else {
            valid = (bytes.count >= 8 && Array(bytes[4..<8]) == [0x66, 0x74, 0x79, 0x70]) || bytes.starts(with: [0x1a, 0x45, 0xdf, 0xa3])
        }
        guard valid else { throw AppFailure(message: "下載檔案不完整或不是支援的媒體格式") }
    }
}
