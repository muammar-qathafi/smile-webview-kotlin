/**
 * Status: AKTIF
 * Flow: Root Compose. WebView di bawah, overlay native (diagnosis dan Fake GPS) di atas.
 * Takeout: jangan.
 */
package co.id.multifab.smile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.id.multifab.smile.ui.NativeOverlayHost
import co.id.multifab.smile.ui.WebViewScreen

@Composable
fun App() {
    Box(modifier = Modifier.fillMaxSize()) {
        WebViewScreen(modifier = Modifier.fillMaxSize())
        NativeOverlayHost()
    }
}
