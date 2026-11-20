/**
 * Status: AKTIF
 * Flow: Menyuntik skrip shell (keyboard, hook absen masuk → native-config, UX, Fake GPS web, pull-to-refresh).
 * Takeout: jangan.
 */
package co.id.multifab.smile.bridge

import android.webkit.WebView
import java.io.IOException

/**
 * Menyuntikkan script shell (keyboard-inject.js) ke WebView production —
 * port dari smile-1.1 MainActivity.injectShellScripts.
 */
object ShellScriptInjector {

    /** Shell berat cukup sekali per WebView; SPA route tidak perlu injek ulang penuh. */
    private val shellInjected = java.util.WeakHashMap<WebView, Boolean>()

    private var keyboardScript: String? = null
    private var mapDarkFixScript: String? = null
    private var productionUxScript: String? = null
    private var suppressWebFakeGpsScript: String? = null
    private var pullToRefreshScrollScript: String? = null
    private var attendanceCheckHookScript: String? = null

    fun documentStartScripts(context: android.content.Context): String {
        val suppress = try {
            context.assets
                .open("shell/suppress-web-fake-gps.js")
                .bufferedReader()
                .use { it.readText() }
                .also { suppressWebFakeGpsScript = it }
        } catch (_: IOException) {
            ""
        }
        val attendanceHook = try {
            context.assets
                .open("shell/attendance-check-hook.js")
                .bufferedReader()
                .use { it.readText() }
                .also { attendanceCheckHookScript = it }
        } catch (_: IOException) {
            ""
        }
        return listOf(
            CapacitorCompatShim.documentStartScript,
            suppress,
            attendanceHook,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    fun injectShellScripts(webView: WebView, bridge: SmileJsBridge) {
        if (shellInjected[webView] == true) {
            // SPA route: jangan evaluateJavascript ulang skrip besar (mahal di HP lemot).
            webView.evaluateJavascript(
                """
                (function() {
                  try {
                    window.dispatchEvent(new CustomEvent('smile-shell-route', {
                      detail: { path: location.pathname + location.hash }
                    }));
                    if (window.__smileScanWebFakeGps) window.__smileScanWebFakeGps(document);
                  } catch (e) {}
                })();
                """.trimIndent(),
                null,
            )
            return
        }
        shellInjected[webView] = true
        bridge.injectCompatShim(webView)
        injectAssetScript(webView, "shell/suppress-web-fake-gps.js", repeat = 1) { suppressWebFakeGpsScript = it }
        injectAssetScript(webView, "shell/attendance-check-hook.js", repeat = 1) { attendanceCheckHookScript = it }
        injectAssetScript(webView, "shell/production-ux.js", repeat = 1) { productionUxScript = it }
        injectAssetScript(webView, "shell/map-dark-fix.js", repeat = 1) { mapDarkFixScript = it }
        injectAssetScript(webView, "shell/keyboard-inject.js", repeat = 1) { keyboardScript = it }
        injectAssetScript(webView, "shell/pull-to-refresh-scroll.js", repeat = 1) { pullToRefreshScrollScript = it }
    }

    private fun injectAssetScript(
        webView: WebView,
        assetPath: String,
        repeat: Int = 1,
        cache: (String) -> Unit,
    ) {
        val script = readAssetScript(webView, assetPath, cache) ?: return
        webView.evaluateJavascript(script, null)
        if (repeat >= 2) {
            webView.postDelayed({ webView.evaluateJavascript(script, null) }, 400)
        }
        if (repeat >= 3) {
            webView.postDelayed({ webView.evaluateJavascript(script, null) }, 1500)
        }
    }

    private fun readAssetScript(
        webView: WebView,
        assetPath: String,
        cache: (String) -> Unit,
    ): String? {
        val cached = when (assetPath) {
            "shell/keyboard-inject.js" -> keyboardScript
            "shell/map-dark-fix.js" -> mapDarkFixScript
            "shell/production-ux.js" -> productionUxScript
            "shell/suppress-web-fake-gps.js" -> suppressWebFakeGpsScript
            "shell/pull-to-refresh-scroll.js" -> pullToRefreshScrollScript
            "shell/attendance-check-hook.js" -> attendanceCheckHookScript
            else -> null
        }
        cached?.let { return it }
        return try {
            webView.context.assets
                .open(assetPath)
                .bufferedReader()
                .use { it.readText() }
                .also { cache(it) }
        } catch (_: IOException) {
            null
        }
    }
}
