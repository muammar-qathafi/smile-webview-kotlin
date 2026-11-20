/**
 * Status: AKTIF
 * Flow: Izin notifikasi, ambil token Firebase, teruskan pesan ke WebView, buka pengaturan notifikasi.
 * Takeout: jangan.
 */
package co.id.multifab.smile.platform

import co.id.multifab.smile.util.SmileLog

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.fcm.FcmTokenStore
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Izin POST_NOTIFICATIONS.
 *
 * Penting: dialog sistem HARUS ditampilkan saat user mengetuk toggle.
 * Jangan loncat ke App Settings sebelum dialog (bug Honor: rationale sering false).
 */
actual object PushNotificationHandler {
    private const val TAG = "PushNotificationHandler"
    private const val PREFS = "smile_push_prefs"
    private const val KEY_ASKED = "post_notifications_asked_v3"
    private const val KEY_PERMANENT_DENY = "post_notifications_permanent_deny_v3"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val messageListeners = CopyOnWriteArrayList<(PushPayload) -> Unit>()

    @Volatile
    private var cachedPermission: String = "prompt"

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var requestPermissionAction: (() -> Unit)? = null

    @Volatile
    private var pendingResult: CompletableDeferred<String>? = null

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    fun registerPermissionRequester(requester: () -> Unit) {
        requestPermissionAction = requester
    }

    fun onPermissionResult(granted: Boolean) {
        val value = if (granted) "granted" else "denied"
        cachedPermission = value
        val askedBefore = hasAskedBefore()
        markAsked()
        if (granted) {
            clearPermanentDeny()
        } else {
            val activity = activityRef?.get()
            val showRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } ?: false
            // Permanent deny hanya jika dialog sudah pernah muncul sebelumnya
            // dan rationale false (Don't ask again). Honor sering rationale=false
            // pada deny pertama — jangan jebak user.
            if (askedBefore && !showRationale) {
                markPermanentDeny()
            }
        }
        val deferred = pendingResult
        pendingResult = null
        runCatching {
            deferred?.complete(value)
        }.onFailure {
            SmileLog.w("PushNotificationHandler", "complete permission deferred failed: ${it.message}")
        }
    }

    actual suspend fun requestPermission(): String {
        val context = SmileApplication.instance
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            cachedPermission = if (enabled) "granted" else "denied"
            // Jangan auto-buka Settings — itu membuat app "keluar" (Honor/launcher).
            // UI web menampilkan pesan; user buka Settings lewat aksi eksplisit.
            return cachedPermission
        }

        if (hasPostNotificationsPermission()) {
            clearPermanentDeny()
            cachedPermission = "granted"
            return "granted"
        }

        val requester = requestPermissionAction
        if (requester == null) {
            cachedPermission = currentPermissionStatus()
            return cachedPermission
        }

        // Deny permanen: tetap coba dialog sekali (sistem no-op jika tidak bisa).
        // JANGAN startActivity(Settings) otomatis — di Honor sering melempar ke home.
        val deferred = CompletableDeferred<String>()
        pendingResult?.cancel()
        pendingResult = deferred

        // Post ke Main — hindari deadlock jika JS bridge jalan di Main + runBlocking.
        // markAsked hanya di onPermissionResult (setelah dialog selesai).
        mainHandler.post {
            try {
                requester.invoke()
            } catch (_: Exception) {
                pendingResult?.complete(currentPermissionStatus())
                pendingResult = null
            }
        }

        val result = withTimeoutOrNull(120_000L) { deferred.await() }
            ?: currentPermissionStatus()
        cachedPermission = result
        return result
    }

    actual fun getPermission(): String {
        cachedPermission = currentPermissionStatus()
        return cachedPermission
    }

    actual suspend fun getToken(forceRefresh: Boolean): String = withContext(Dispatchers.IO) {
        val context = SmileApplication.instance
        if (!forceRefresh) {
            FcmTokenStore.get(context)?.let { return@withContext it }
        }
        // Tanpa google-services.json: JANGAN panggil FirebaseMessaging sama sekali.
        // Di Honor, getInstance()/token bisa native-crash meski dibungkus runCatching.
        if (!isFirebaseConfigured()) {
            return@withContext FcmTokenStore.get(context).orEmpty()
        }
        runCatching {
            Tasks.await(FirebaseMessaging.getInstance().token, 20, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure {
            SmileLog.w(TAG, "getToken failed: ${it.message}")
        }.getOrNull()?.also { token ->
            if (token.isNotBlank()) {
                FcmTokenStore.set(context, token)
                notifyTokenRefresh(token)
            }
        }.orEmpty()
    }

    /** Cached token only — aman dipanggil dari JavascriptInterface tanpa runBlocking. */
    fun getCachedToken(): String = FcmTokenStore.get(SmileApplication.instance).orEmpty()

    fun isFirebaseConfigured(): Boolean {
        if (!co.id.multifab.BuildConfig.SMILE_FCM_ENABLED) return false
        val ctx = SmileApplication.instance
        val resId = ctx.resources.getIdentifier("google_app_id", "string", ctx.packageName)
        if (resId == 0) return false
        val appId = runCatching { ctx.getString(resId) }.getOrNull().orEmpty()
        if (appId.isBlank()) return false
        return runCatching {
            com.google.firebase.FirebaseApp.getInstance()
            true
        }.getOrDefault(false)
    }

    actual fun getDiagnostics(requestPermission: Boolean): PushDiagnostics {
        // Jangan runBlocking requestPermission dari diagnostics — risiko ANR/crash di bridge.
        val permission = getPermission()
        val token = getCachedToken()
        val status = when {
            permission != "granted" -> if (permission == "denied") "permission_denied" else "permission_required"
            !isFirebaseConfigured() -> "no_token"
            token.isBlank() -> "no_token"
            else -> "ready"
        }
        val message = when (status) {
            "ready" -> "FCM native aktif."
            "no_token" ->
                if (!isFirebaseConfigured()) {
                    "Izin notifikasi OK. FCM belum dikonfigurasi (google-services.json belum ada)."
                } else {
                    "Token FCM belum tersedia. Pastikan google-services.json sudah benar."
                }
            "permission_denied" -> "Izin notifikasi ditolak. Aktifkan di Pengaturan → Apps → Smile Multifab → Notifikasi."
            else -> "Izin notifikasi belum diberikan."
        }
        return PushDiagnostics(
            supported = true,
            permission = permission,
            token = token,
            status = status,
            message = message,
            platform = "android",
        )
    }

    actual fun onTokenRefresh(listener: (String) -> Unit) {
        tokenListeners.add(listener)
    }

    actual fun onMessageReceived(listener: (PushPayload) -> Unit) {
        messageListeners.add(listener)
    }

    fun notifyTokenRefresh(token: String) {
        tokenListeners.forEach { it(token) }
    }

    fun notifyMessageReceived(payload: PushPayload) {
        messageListeners.forEach { it(payload) }
    }

    /**
     * Buka halaman notifikasi app di sistem — HANYA dari aksi user eksplisit.
     * Prefer Activity context agar task stack Honor tidak “jatuh” ke launcher.
     */
    actual fun openNotificationSettings() {
        val activity = activityRef?.get()
        val context = activity ?: SmileApplication.instance
        val pkg = context.packageName
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        putExtra("app_package", pkg)
                        putExtra("app_uid", context.applicationInfo.uid)
                    },
                )
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", pkg, null)
                },
            )
        }
        for (intent in candidates) {
            if (activity == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return
        }
    }

    /** Reset flag deny permanen — berguna setelah user ubah izin di Settings. */
    actual fun refreshPermissionCache() {
        if (hasPostNotificationsPermission()) {
            clearPermanentDeny()
            cachedPermission = "granted"
        } else {
            cachedPermission = currentPermissionStatus()
        }
    }

    private fun hasPostNotificationsPermission(): Boolean {
        val context = SmileApplication.instance
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentPermissionStatus(): String {
        val context = SmileApplication.instance
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                "granted"
            } else {
                "denied"
            }
        }
        if (hasPostNotificationsPermission()) return "granted"
        if (isPermanentDeny()) return "denied"

        val activity = activityRef?.get()
        val asked = hasAskedBefore()
        val showRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } ?: false

        return when {
            !asked -> "prompt"
            showRationale -> "prompt-with-rationale"
            else -> "prompt" // masih coba dialog; jangan premature "denied"
        }
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun hasAskedBefore(): Boolean = prefs().getBoolean(KEY_ASKED, false)

    private fun markAsked() {
        prefs().edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun isPermanentDeny(): Boolean = prefs().getBoolean(KEY_PERMANENT_DENY, false)

    private fun markPermanentDeny() {
        prefs().edit().putBoolean(KEY_PERMANENT_DENY, true).apply()
    }

    private fun clearPermanentDeny() {
        prefs().edit().putBoolean(KEY_PERMANENT_DENY, false).apply()
    }
}
