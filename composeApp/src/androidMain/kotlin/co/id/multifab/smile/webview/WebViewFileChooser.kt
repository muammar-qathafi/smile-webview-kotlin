/**
 * Status: PENDUKUNG
 * Flow: Global bridge &lt;input type="file"&gt; WebView → system file picker Android
 *       (izin/cuti, medical, travel, overtime, helpdesk, visit, social, dll.).
 *       Tanpa READ_MEDIA_* — Play policy: pakai pemilih bawaan sistem.
 * Takeout: jangan.
 */
package co.id.multifab.smile.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import co.id.multifab.smile.util.SmileLog

/**
 * Handler global untuk semua file input di halaman web.
 * Dipanggil dari [SmileWebChromeClient.onShowFileChooser] — satu jalur untuk seluruh fitur upload.
 */
object WebViewFileChooser {

    private const val TAG = "WebViewFileChooser"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var launchChooser: ((Intent) -> Unit)? = null

    fun registerLauncher(launcher: (Intent) -> Unit) {
        launchChooser = launcher
    }

    /**
     * @return true jika WebView harus menunggu hasil picker.
     */
    fun onShowFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: WebChromeClient.FileChooserParams?,
    ): Boolean {
        // Batalkan request sebelumnya agar WebView tidak macet menunggu callback.
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = filePathCallback

        if (filePathCallback == null || fileChooserParams == null) {
            clearCallback(null)
            return false
        }

        if (launchChooser == null) {
            SmileLog.w(TAG, "File chooser launcher belum terdaftar di MainActivity")
            clearCallback(null)
            return false
        }

        return launchPicker(fileChooserParams)
    }

    /** Kompat: dulu dipanggil setelah dialog izin media — sekarang no-op. */
    fun onMediaPermissionResult(@Suppress("UNUSED_PARAMETER") granted: Boolean) {
        // Izin media tidak lagi dipakai; upload langsung lewat system picker.
    }

    fun onActivityResult(resultCode: Int, data: Intent?) {
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return

        val uris = parseResultUris(resultCode, data)
        mainHandler.post {
            runCatching { callback.onReceiveValue(uris) }
                .onFailure { err -> SmileLog.e(TAG, "filePathCallback gagal", err) }
        }
    }

    private fun launchPicker(params: WebChromeClient.FileChooserParams): Boolean {
        val launcher = launchChooser
        if (launcher == null) {
            clearCallback(null)
            return false
        }
        return try {
            launcher(params.createIntent())
            true
        } catch (e: Exception) {
            SmileLog.e(TAG, "createIntent gagal, coba fallback GET_CONTENT", e)
            try {
                launcher(buildFallbackIntent(params))
                true
            } catch (e2: Exception) {
                SmileLog.e(TAG, "Fallback file chooser gagal", e2)
                clearCallback(null)
                false
            }
        }
    }

    private fun buildFallbackIntent(params: WebChromeClient.FileChooserParams): Intent {
        val accept = params.acceptTypes
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toTypedArray()
            .orEmpty()

        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                accept.isEmpty() -> "*/*"
                accept.size == 1 && !accept[0].startsWith(".") -> accept[0]
                else -> "*/*"
            }
            if (accept.size > 1 || accept.any { it.startsWith(".") }) {
                val mimeTypes = accept.map { raw ->
                    when {
                        raw.startsWith(".") -> extensionToMime(raw)
                        else -> raw
                    }
                }.distinct().toTypedArray()
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            }
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
            )
        }
    }

    private fun extensionToMime(ext: String): String = when (ext.lowercase()) {
        ".pdf" -> "application/pdf"
        ".jpg", ".jpeg" -> "image/jpeg"
        ".png" -> "image/png"
        ".gif" -> "image/gif"
        ".webp" -> "image/webp"
        ".heic" -> "image/heic"
        else -> "*/*"
    }

    private fun clearCallback(value: Array<Uri>?) {
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return
        mainHandler.post {
            runCatching { callback.onReceiveValue(value) }
        }
    }

    private fun parseResultUris(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != Activity.RESULT_OK || data == null) return null

        data.data?.let { return arrayOf(it) }

        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
        }

        return WebChromeClient.FileChooserParams.parseResult(resultCode, data)
    }
}
