import Foundation

private final class TransferDelegate: NSObject, URLSessionDownloadDelegate {
    let platform: String
    let remote: Bool
    let progress: (Double?) -> Void
    init(platform: String, remote: Bool, progress: @escaping (Double?) -> Void) {
        self.platform = platform; self.remote = remote; self.progress = progress
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        progress(totalBytesExpectedToWrite > 0 ? min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) : nil)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Never forward the service token on redirects. Direct media stays on validated CDN hosts.
        guard !remote, let url = request.url, MediaPolicy.allowed(url, platform: platform) else {
            completionHandler(nil); return
        }
        var redirected = request
        for key in ["User-Agent", "Referer"] {
            redirected.setValue(task.originalRequest?.value(forHTTPHeaderField: key), forHTTPHeaderField: key)
        }
        completionHandler(redirected)
    }
}

struct DownloadClient {
    func fetch(video: Video, quality: Quality, mp3: Bool, serviceURL: String, token: String,
               progress: @escaping (Double?) -> Void) async throws -> (URL, String) {
        let remote = mp3 || quality.formatSelector != nil
        var requests: [URLRequest] = []
        if remote {
            let base = try MediaPolicy.service(serviceURL)
            guard !token.isEmpty, !token.contains("\r"), !token.contains("\n") else {
                throw AppFailure(message: "請設定下載服務的存取權杖")
            }
            var request = URLRequest(url: base.appendingPathComponent("v1/download"))
            request.httpMethod = "POST"
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            var payload: [String: Any] = ["url": video.sourceUrl, "format": mp3 ? "mp3" : "video"]
            payload["selector"] = mp3 ? quality.audioFormatSelector : quality.formatSelector
            if quality.formatSelector == nil { payload["directCandidates"] = quality.directCandidates }
            request.httpBody = try JSONSerialization.data(withJSONObject: payload)
            request.timeoutInterval = 600
            requests = [request]
        } else {
            requests = try quality.directCandidates.map { candidate in
                guard let url = URL(string: candidate), MediaPolicy.allowed(url, platform: video.platform) else {
                    throw AppFailure(message: "影片來源網址不受支援，請重新分析")
                }
                var request = URLRequest(url: url)
                for (key, value) in quality.preview?.video.headers ?? [:] { request.setValue(value, forHTTPHeaderField: key) }
                return request
            }
        }
        guard !requests.isEmpty else { throw AppFailure(message: "所選畫質沒有可下載的來源") }
        let delegate = TransferDelegate(platform: video.platform, remote: remote, progress: progress)
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.timeoutIntervalForRequest = remote ? 600 : 60
        config.timeoutIntervalForResource = 1_800
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var lastError: Error = AppFailure(message: "下載失敗")
        for (index, request) in requests.enumerated() {
            try Task.checkCancellation()
            var temporary: URL?
            do {
                let (file, response) = try await session.download(for: request)
                temporary = file
                guard let http = response as? HTTPURLResponse else { throw AppFailure(message: "無法取得下載回應") }
                // Rate limits and account restrictions must not trigger replica retries.
                if http.statusCode == 401 || http.statusCode == 429 {
                    try? FileManager.default.removeItem(at: file)
                    throw StopRetry(message: "來源限制這次下載（HTTP \(http.statusCode)），請稍後再試")
                }
                try MediaPolicy.validateFile(file, response: http, mp3: mp3)
                try Task.checkCancellation()
                let ext = mp3 ? "mp3" : (remote ? outputExtension(http, fallback: quality.container) : "mp4")
                let staging = FileManager.default.temporaryDirectory.appendingPathComponent("vget-\(UUID().uuidString).\(ext)")
                try FileManager.default.moveItem(at: file, to: staging)
                return (staging, ext)
            } catch {
                if let temporary { try? FileManager.default.removeItem(at: temporary) }
                if Task.isCancelled || (error as? URLError)?.code == .cancelled { throw CancellationError() }
                if let stop = error as? StopRetry { throw AppFailure(message: stop.message) }
                lastError = error
                // Retry only network / source failures, never file-system errors.
                if index == requests.count - 1 || error is CocoaError { throw error }
            }
        }
        throw lastError
    }

    private func outputExtension(_ response: HTTPURLResponse, fallback: String) -> String {
        let ext = response.value(forHTTPHeaderField: "X-VGet-Extension") ?? fallback
        return ["mp4", "webm", "mkv"].contains(ext) ? ext : "mp4"
    }
    private struct StopRetry: Error { let message: String }
}
