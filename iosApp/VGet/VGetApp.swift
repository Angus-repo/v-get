import SwiftUI
import AVKit

@main struct VGetApp: App {
    var body: some Scene { WindowGroup { ContentView() } }
}

struct ContentView: View {
    @StateObject private var model = DownloadModel()
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                Form {
                    Section("1. 貼上影片連結") {
                        TextEditor(text: $model.input).frame(minHeight: 90).disabled(model.busy)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                            .onChange(of: model.input) { _ in model.invalidate() }
                        HStack {
                            PasteButton(payloadType: String.self) { values in
                                if let text = values.first { model.input = text }
                            }.disabled(model.busy)
                            Button("分析畫質") { model.inspect() }.disabled(model.busy || model.input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                    Section("2. 選擇畫質並下載影片") {
                        if let video = model.video {
                            Text(video.title).font(.headline)
                            ForEach(video.qualities) { quality in
                                Button { model.selectedId = quality.id } label: {
                                    HStack {
                                        Text(quality.label).foregroundStyle(.primary).multilineTextAlignment(.leading)
                                        Spacer()
                                        if model.selectedId == quality.id { Image(systemName: "checkmark.circle.fill") }
                                    }.padding(.vertical, 8)
                                }.disabled(model.busy)
                            }
                            Button("下載所選畫質") { model.download() }.disabled(model.busy)
                            Button("試播（先下載所選畫質）") { model.download(preview: true) }.disabled(model.busy)
                        } else { Text("分析後會列出來源提供的畫質").foregroundStyle(.secondary) }
                    }.id("quality")
                    Section("3. 下載音訊") {
                        Text(model.selected?.mp3Size.map { "MP3 192 kbps · \($0)" } ?? "MP3 192 kbps · 容量未提供")
                        Button("下載 MP3 音訊") { model.download(mp3: true) }
                            .disabled(model.busy || model.selected == nil || model.selected?.silent == true)
                    }
                    Section("4. 下載進度") {
                        if model.busy {
                            if let progress = model.progress { ProgressView(value: progress) } else { ProgressView() }
                            Button("取消", role: .cancel) { model.cancel() }
                        }
                        if !model.status.isEmpty { Text(model.status).textSelection(.enabled) }
                        if !model.error.isEmpty {
                            Text(model.error).foregroundStyle(.red).textSelection(.enabled)
                            Button("複製錯誤訊息") { UIPasteboard.general.string = model.error + "\nV-Get 2.0.0 · iOS " + UIDevice.current.systemVersion }
                        }
                        if let file = model.saved {
                            ShareLink("分享／儲存下載檔案", item: file)
                            Button("播放上次下載") { model.player = AVPlayer(url: file); model.player?.play() }
                        }
                    }
                }
                .onChange(of: model.scrollTrigger) { _ in withAnimation { proxy.scrollTo("quality", anchor: .top) } }
            }
            .navigationTitle("V-Get")
            .toolbar { NavigationLink { ServiceSettings() } label: { Image(systemName: "gearshape") } }
            .sheet(isPresented: Binding(get: { model.player != nil }, set: { if !$0 { model.stopPreview() } })) {
                if let player = model.player { PlayerScreen(player: player) }
            }
            .onAppear { model.acceptShare() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { model.acceptShare() }
                if phase == .background { model.cancel(); model.stopPreview() }
            }
        }
    }
}

private struct PlayerScreen: View {
    let player: AVPlayer
    @State private var failed = false
    var body: some View {
        VStack {
            VideoPlayer(player: player)
            if failed { Text("iOS 無法播放此編碼。可改選 H.264 畫質，或分享檔案至其他播放器。").padding() }
        }.onReceive(player.currentItem!.publisher(for: \.status)) { failed = $0 == .failed }
    }
}
