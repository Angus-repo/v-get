import SwiftUI
import VGetShared

struct AnalysisHistoryView: View {
    @AppStorage(AnalysisHistory.shared.storageKey) private var stored = ""

    var body: some View {
        let entries = AnalysisHistory.shared.entries(stored: stored)
        Group {
            if entries.isEmpty {
                VStack(spacing: 14) {
                    Image(systemName: "clock.arrow.circlepath")
                        .font(.title).foregroundStyle(Color.accentColor)
                        .frame(width: 64, height: 64)
                        .background(Color.accentColor.opacity(0.12), in: Circle())
                        .accessibilityHidden(true)
                    Text("尚無分析紀錄").font(.headline)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        Text("最近 \(entries.count) 筆")
                            .font(.subheadline).foregroundStyle(.secondary)
                        ForEach(entries.indices, id: \.self) { index in
                            AnalysisHistoryRow(entry: entries[index])
                        }
                    }
                    .frame(maxWidth: 680)
                    .padding(.horizontal, 16).padding(.bottom, 20)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("分析紀錄")
    }
}

private struct AnalysisHistoryRow: View {
    let entry: AnalysisEntry
    @State private var copyFeedback: UUID?

    var body: some View {
        let title = entry.displayTitle
        HStack(spacing: 10) {
            if let url = URL(string: entry.pageUrl) {
                Link(destination: url) {
                    VStack(alignment: .leading, spacing: 5) {
                        Label(entry.sourceLabel, systemImage: "arrow.up.right.square")
                            .font(.caption.weight(.semibold)).foregroundStyle(Color.accentColor)
                        Text(title).font(.headline).foregroundStyle(.primary)
                            .multilineTextAlignment(.leading).lineLimit(3)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 14).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint("開啟原影片")
            }
            Button {
                UIPasteboard.general.string = entry.pageUrl
                copyFeedback = UUID()
                UIAccessibility.post(notification: .announcement, argument: "已複製連結")
            } label: {
                Image(systemName: copyFeedback == nil ? "doc.on.doc" : "checkmark")
                    .font(.body.weight(.semibold)).foregroundStyle(Color.accentColor)
                    .frame(width: 44, height: 44)
                    .background(Color.accentColor.opacity(0.12), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("複製連結：\(title)")
            .task(id: copyFeedback) {
                guard copyFeedback != nil else { return }
                do {
                    try await Task.sleep(nanoseconds: 1_500_000_000)
                    copyFeedback = nil
                } catch { /* Another tap or a disappearing row cancels this reset. */ }
            }
        }
        .padding(.leading, 16).padding(.trailing, 12)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}
