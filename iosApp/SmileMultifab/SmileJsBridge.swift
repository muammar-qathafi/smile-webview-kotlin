import Foundation
import WebKit

/// Bridge native ↔ web (setara SmileJsBridge.kt).
/// Web memanggil: window.webkit.messageHandlers.SmileNative.postMessage({ method, args })
final class SmileJsBridge: NSObject, WKScriptMessageHandler {
    weak var webView: WKWebView?

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == AppConfig.jsBridgeName else { return }
        guard let body = message.body as? [String: Any] else { return }
        let method = (body["method"] as? String) ?? ""
        let args = body["args"] as? [Any] ?? []

        switch method {
        case "isMockLocation", "isFakeGps":
            reply(method: method, result: FakeGpsMonitor.shared.isSimulated)
        case "getPlatform":
            reply(method: method, result: "ios")
        case "getAppVersion":
            let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "2.6.13"
            reply(method: method, result: version)
        default:
            // Stub aman — method Android-only diabaikan dulu.
            NSLog("[SmileJsBridge] unhandled method=%@ args=%@", method, String(describing: args))
        }
    }

    private func reply(method: String, result: Any) {
        guard let webView else { return }
        let payload: [String: Any] = [
            "method": method,
            "result": result,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8) else { return }
        let script = "window.dispatchEvent(new CustomEvent('smile-native-reply', { detail: \(json) }));"
        DispatchQueue.main.async {
            webView.evaluateJavaScript(script, completionHandler: nil)
        }
    }

    /// Inject shim agar web production mengenali shell native iOS.
    static func documentStartScript() -> String {
        """
        (function () {
          window.__SMILE_WEBVIEW__ = true;
          window.__SMILE_PLATFORM__ = 'ios';
          window.__SMILE_DISABLE_WEB_FAKE_GPS_OVERLAY__ = true;
          if (!window.SmileNative) {
            window.SmileNative = new Proxy({}, {
              get: function (_t, prop) {
                return function () {
                  var args = Array.prototype.slice.call(arguments);
                  try {
                    window.webkit.messageHandlers.\(AppConfig.jsBridgeName).postMessage({
                      method: String(prop),
                      args: args
                    });
                  } catch (e) {}
                  return null;
                };
              }
            });
          }
          try {
            document.dispatchEvent(new CustomEvent('smile-native-ready', {
              detail: { platform: 'ios' }
            }));
          } catch (e) {}
        })();
        """
    }
}
