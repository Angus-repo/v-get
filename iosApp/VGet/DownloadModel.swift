import SwiftUI
import AVKit
import VGetShared

@MainActor final class DownloadModel: ObservableObject {
    @Published var input = ""
    @Published var video: Video?
    @Published var selectedId = ""
    @Published var busy = false
    @Published var progress: Double?
    @Published var status = ""
    @Published var error = ""
    @Published var saved: URL?
    @Published var player: AVPlayer?
    @Published var scrollTrigger = UUID()
    private let bridge = IosBridge()
    private var request: IosRequest?
    private var task: Task<Void, Never>?
    private var generation = UUID()
    private var previewFile: URL?
    var selected: Quality? { video?.qualities.first { $0.id == selectedId } }

    func invalidate() { video = nil; selectedId = ""; error = "" }
    func inspect() {
        guard !busy else { return }
        invalidate(); busy = true; status = "正在分析畫質…"; progress = nil
        let id = UUID(); generation = id
        request = bridge.inspect(input: input, serviceUrl: UserDefaults.standard.string(forKey: "serviceURL") ?? "",
            serviceToken: Keychain.read(), success: { [weak self] json in
                guard let self, self.generation == id else { return }
                defer { self.busy = false; self.request = nil }
                do {
                    let result = try JSONDecoder().decode(Video.self, from: Data(json.utf8))
                    self.video = result; self.selectedId = result.qualities.first?.id ?? ""
                    self.status = "請選擇畫質"; self.scrollTrigger = UUID()
                } catch { self.error = "無法讀取影片資料，請重新分析" }
            }, failure: { [weak self] message in
                guard let self, self.generation == id else { return }
                self.error = message; self.status = "分析失敗"; self.busy = false; self.request = nil
            })
    }

    func download(mp3: Bool = false, preview: Bool = false) {
        guard !busy, let video, let quality = selected else { return }
        if mp3 && quality.silent { error = "這支影片沒有音軌，無法轉為 MP3"; return }
        busy = true; error = ""; progress = nil; scrollTrigger = UUID()
        status = preview ? "正在準備所選畫質的預覽…" : "正在下載…"
        let id = UUID(); generation = id
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let (file, ext) = try await DownloadClient().fetch(video: video, quality: quality, mp3: mp3,
                    serviceURL: UserDefaults.standard.string(forKey: "serviceURL") ?? "", token: Keychain.read()) { [weak self] value in
                        Task { @MainActor in
                            guard let self, self.generation == id, self.busy else { return }
                            self.progress = value
                        }
                    }
                defer { try? FileManager.default.removeItem(at: file) }
                try Task.checkCancellation()
                guard self.generation == id else { return }
                if preview {
                    self.stopPreview()
                    let destination = FileManager.default.temporaryDirectory.appendingPathComponent("preview-\(UUID().uuidString).\(ext)")
                    try FileManager.default.moveItem(at: file, to: destination)
                    self.previewFile = destination; self.player = AVPlayer(url: destination); self.player?.play()
                    self.status = "預覽已就緒"
                } else {
                    self.saved = try self.publish(file, title: video.title, ext: ext)
                    self.status = "已儲存：\(self.saved!.lastPathComponent)"
                }
            } catch is CancellationError {
                if self.generation == id { self.status = "已取消" }
            } catch {
                if self.generation == id {
                    self.error = (error as? AppFailure)?.message ?? "下載或儲存失敗，請確認網路與可用空間後再試"
                    self.status = "下載失敗"
                }
            }
            if self.generation == id { self.busy = false; self.task = nil }
        }
    }

    private func publish(_ file: URL, title: String, ext: String) throws -> URL {
        let manager = FileManager.default
        let directory = try manager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("V-Get", isDirectory: true)
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        for number in 0..<10_000 {
            let name = bridge.fileName(title: title, extension: ext, copyNumber: Int32(number))
            let destination = directory.appendingPathComponent(name)
            if manager.fileExists(atPath: destination.path) { continue }
            do { try manager.moveItem(at: file, to: destination); return destination }
            catch let error as CocoaError where error.code == .fileWriteFileExists { continue }
        }
        throw AppFailure(message: "同名檔案過多，請先整理已下載的檔案")
    }

    func cancel() {
        generation = UUID(); request?.cancel(); request = nil; task?.cancel(); task = nil
        busy = false; status = "已取消"; progress = nil
    }
    func stopPreview() {
        player?.pause(); player = nil
        if let previewFile { try? FileManager.default.removeItem(at: previewFile) }
        previewFile = nil
    }
    func acceptShare() {
        guard !busy, let defaults = UserDefaults(suiteName: "group.com.vget.app"),
              let text = defaults.string(forKey: "pendingURL") else { return }
        defaults.removeObject(forKey: "pendingURL")
        input = text; invalidate()
    }
}
