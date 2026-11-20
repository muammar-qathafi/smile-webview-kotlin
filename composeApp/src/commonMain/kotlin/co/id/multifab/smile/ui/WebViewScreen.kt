/**
 * Status: AKTIF
 * Flow: Deklarasi WebView dan kontrolnya (reload, back, JS, loadUrl). Implementasi ada di WebViewScreen.android.kt.
 * Takeout: jangan.
 */
package co.id.multifab.smile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

expect class WebViewController {
    fun reload()
    fun canGoBack(): Boolean
    fun goBack()
    fun evaluateJavascript(script: String, onResult: ((String) -> Unit)? = null)
    fun loadUrl(url: String)
}

@Composable
expect fun WebViewScreen(modifier: Modifier = Modifier)
