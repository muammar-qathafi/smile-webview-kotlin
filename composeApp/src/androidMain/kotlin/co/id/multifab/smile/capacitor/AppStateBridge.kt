/**
 * Status: AKTIF
 * Flow: Mengirim event smile-app-state ke web saat activity foreground atau background (MainActivity onStart/onStop).
 * Takeout: jangan.
 */
package co.id.multifab.smile.capacitor

import co.id.multifab.smile.bridge.WebViewBridgeRegistry

object AppStateBridge {

    fun dispatch(isActive: Boolean) {
        val controller = WebViewBridgeRegistry.get() ?: return
        controller.evaluateJavascript(
            """
            document.dispatchEvent(new CustomEvent('smile-app-state', {
              detail: { isActive: $isActive }
            }));
            """.trimIndent(),
        )
    }
}
