/**
 * Status: PENDUKUNG
 * Flow: Menjembatani izin runtime Android ke PermissionRequest WebView.
 * Takeout: jangan.
 */
package co.id.multifab.smile.webview

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat

/**
 * Menjembatani izin runtime Android ↔ [PermissionRequest] WebView
 * (getUserMedia untuk QR scanner, dll).
 */
object WebViewPermissionHandler {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingRequest: PermissionRequest? = null
    private var requestCameraPermission: (() -> Unit)? = null
    private var requestLocationPermission: (() -> Unit)? = null
    private var pendingGeoCallback: ((Boolean) -> Unit)? = null

    /** True jika launcher lokasi berikutnya dari NativePermissionCoordinator (bukan peta WebView). */
    @Volatile
    private var coordinatorLocationRequestPending = false

    fun markCoordinatorLocationRequest() {
        coordinatorLocationRequestPending = true
    }

    fun consumeCoordinatorLocationRequest(): Boolean {
        val pending = coordinatorLocationRequestPending
        coordinatorLocationRequestPending = false
        return pending
    }

    fun registerCameraPermissionRequester(requester: () -> Unit) {
        requestCameraPermission = requester
    }

    fun registerLocationPermissionRequester(requester: () -> Unit) {
        requestLocationPermission = requester
    }

    fun requestLocationPermission(onResult: (Boolean) -> Unit) {
        if (hasLocationPermission(activity = null)) {
            onResult(true)
            return
        }
        pendingGeoCallback = onResult
        requestLocationPermission?.invoke() ?: onResult(false)
    }

    fun onLocationPermissionResult(granted: Boolean) {
        val callback = pendingGeoCallback
        pendingGeoCallback = null
        if (callback == null) return
        mainHandler.post {
            runCatching { callback(granted) }
        }
    }

    private fun hasLocationPermission(activity: android.app.Activity?): Boolean {
        val ctx = activity ?: co.id.multifab.smile.SmileApplication.instance
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun handlePermissionRequest(activity: Activity, request: PermissionRequest) {
        val resources = request.resources
        val needsCamera = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

        if (!needsCamera) {
            activity.runOnUiThread { request.grant(resources) }
            return
        }

        if (hasCameraPermission(activity)) {
            activity.runOnUiThread { request.grant(resources) }
            return
        }

        pendingRequest = request
        requestCameraPermission?.invoke() ?: run {
            activity.runOnUiThread { request.deny() }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        val request = pendingRequest ?: return
        pendingRequest = null
        if (granted) {
            request.grant(request.resources)
        } else {
            request.deny()
        }
    }

    fun hasCameraPermission(activity: Activity): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}
