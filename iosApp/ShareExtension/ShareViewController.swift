import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private var handled = false
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !handled else { return }; handled = true
        let attachments = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        guard let provider = attachments.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.url.identifier) || $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) }) else {
            finish("找不到影片連結，請複製連結後貼入 V-Get。"); return
        }
        let type = provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) ? UTType.url.identifier : UTType.plainText.identifier
        provider.loadItem(forTypeIdentifier: type, options: nil) { [weak self] item, _ in
            let text = (item as? URL)?.absoluteString ?? item as? String
            DispatchQueue.main.async {
                guard let self else { return }
                if let text, let defaults = UserDefaults(suiteName: "group.com.vget.app") {
                    defaults.set(text, forKey: "pendingURL")
                    self.finish("連結已儲存。請開啟 V-Get，即可分析畫質並下載。")
                } else { self.finish("無法讀取連結，請複製後貼入 V-Get。") }
            }
        }
    }
    private func finish(_ message: String) {
        let alert = UIAlertController(title: "V-Get", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "完成", style: .default) { [weak self] _ in self?.extensionContext?.completeRequest(returningItems: nil) })
        present(alert, animated: true)
    }
}
