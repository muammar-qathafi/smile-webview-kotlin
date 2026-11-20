/**
 * Status: PENDUKUNG
 * Flow: Izin geolokasi, getUserMedia, dan file chooser global (&lt;input type="file"&gt;) di WebView.
 * Takeout: jangan.
 */
package co.id.multifab.smile.webview

import android.app.Activity
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import co.id.multifab.smile.location.NativeLocationController

class SmileWebChromeClient(
    private val activity: Activity,
) : WebChromeClient() {

    override fun onPermissionRequest(request: PermissionRequest) {
        WebViewPermissionHandler.handlePermissionRequest(activity, request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        if (NativeLocationController.hasLocationPermission(activity)) {
            callback.invoke(origin, true, false)
        } else {
            WebViewPermissionHandler.requestLocationPermission { granted ->
                activity.runOnUiThread {
                    val ok = granted && NativeLocationController.hasLocationPermission(activity)
                    callback.invoke(origin, ok, false)
                }
            }
        }
    }

    /**
     * Global: semua fitur web yang memakai &lt;input type="file"&gt;
     * (cuti/izin, medical, travel, overtime, helpdesk, visit, social, dll.).
     */
    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        return WebViewFileChooser.onShowFileChooser(filePathCallback, fileChooserParams)
    }
}
