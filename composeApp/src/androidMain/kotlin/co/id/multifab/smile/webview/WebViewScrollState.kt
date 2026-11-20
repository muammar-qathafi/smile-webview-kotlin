/**
 * Status: PENDUKUNG
 * Flow: Menyimpan apakah konten web sudah di paling atas, supaya pull-to-refresh tidak berebut dengan scroll.
 * Takeout: jangan selama pull-to-refresh dipakai.
 */
package co.id.multifab.smile.webview

/**
 * Status scroll konten web (SPA sering scroll di dalam div, bukan WebView).
 * Di-update dari JS → SmileNative.setContentAtTop.
 */
object WebViewScrollState {
    @Volatile
    var contentAtTop: Boolean = true
        private set

    fun setContentAtTop(atTop: Boolean) {
        contentAtTop = atTop
    }
}
