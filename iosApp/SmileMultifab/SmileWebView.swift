import SwiftUI
import WebKit

struct SmileWebViewRepresentable: UIViewRepresentable {
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.websiteDataStore = .default()

        let uc = config.userContentController
        let bridge = context.coordinator.bridge
        uc.add(bridge, name: AppConfig.jsBridgeName)

        let startScript = WKUserScript(
            source: SmileJsBridge.documentStartScript(),
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        )
        uc.addUserScript(startScript)

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.scrollView.bounces = true
        webView.allowsBackForwardNavigationGestures = true
        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }

        bridge.webView = webView
        context.coordinator.applyUserAgent(webView)

        webView.load(URLRequest(url: AppConfig.webURL))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let bridge = SmileJsBridge()

        func applyUserAgent(_ webView: WKWebView) {
            webView.evaluateJavaScript("navigator.userAgent") { result, _ in
                let base = (result as? String) ?? ""
                if !base.contains("SmileMultifab") {
                    webView.customUserAgent = base + AppConfig.userAgentSuffix
                }
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            if url.scheme == "tel" || url.scheme == "mailto" {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        func webView(
            _ webView: WKWebView,
            requestMediaCapturePermissionFor origin: WKSecurityOrigin,
            initiatedByFrame frame: WKFrameInfo,
            type: WKMediaCaptureType,
            decisionHandler: @escaping (WKPermissionDecision) -> Void
        ) {
            decisionHandler(.grant)
        }
    }
}
