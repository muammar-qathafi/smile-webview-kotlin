/**
 * Status: AKTIF
 * Flow: Layar diagnosis kesiapan push dan lokasi. Dibuka dari notifikasi tracking atau state overlay.
 * Takeout: jangan.
 */
package co.id.multifab.smile.diagnosis

import android.os.Build
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.fcm.FcmBackendSync
import co.id.multifab.smile.location.LocationSessionStore
import co.id.multifab.smile.location.LocationTrackerService
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.platform.DeviceInfo
import co.id.multifab.smile.platform.PushNotificationHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private object DiagnosisColors {
    val pageBg = Color(0xFFF7F3F1)
    val cardBg = Color.White
    val title = Color(0xFF1F2937)
    val muted = Color(0xFF6B7280)
    val border = Color(0xFFE7E0DC)
    val orange = Color(0xFFFF7A3A)
    val ok = Color(0xFF047857)
    val fail = Color(0xFFDC2626)
    val lampNeutral = Color(0xFF9CA3AF)
    val lampOk = Color(0xFF22C55E)
    val lampFail = Color(0xFFEF4444)
}

private fun formatBatchAttemptJakarta(atMs: Long): String {
    if (atMs <= 0L) return "—"
    val sdf = SimpleDateFormat("HH:mm:ss", Locale("id", "ID"))
    sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    return sdf.format(Date(atMs))
}

private fun batchLampColor(lamp: LocationSessionStore.BatchLamp): Color = when (lamp) {
    LocationSessionStore.BatchLamp.Ok -> DiagnosisColors.lampOk
    LocationSessionStore.BatchLamp.Fail -> DiagnosisColors.lampFail
    LocationSessionStore.BatchLamp.Neutral -> DiagnosisColors.lampNeutral
}

private fun batchLampLabel(lamp: LocationSessionStore.BatchLamp): String = when (lamp) {
    LocationSessionStore.BatchLamp.Ok -> "Batch terakhir terkirim"
    LocationSessionStore.BatchLamp.Fail -> "Pengiriman terakhir gagal"
    LocationSessionStore.BatchLamp.Neutral -> "Belum ada pengiriman batch"
}

/** Dialog edge-to-edge sering tidak meneruskan Compose insets — baca dari View + fallback gesture bar. */
@Composable
private fun rememberSafeSystemPaddings(
    minTop: Dp = 12.dp,
    minBottom: Dp = 36.dp,
): Pair<Dp, Dp> {
    val view = LocalView.current
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }

    DisposableEffect(view) {
        fun applyFrom(insets: WindowInsetsCompat) {
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            topPx = bars.top
            bottomPx = bars.bottom
        }

        fun readCurrent(target: View) {
            ViewCompat.getRootWindowInsets(target)?.let(::applyFrom)
        }

        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            applyFrom(insets)
            insets
        }
        readCurrent(view)
        ViewCompat.requestApplyInsets(view)
        view.post { readCurrent(view) }

        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
        }
    }

    val top = with(density) { topPx.toDp() }.let { if (it > 0.dp) it else minTop }
    val bottom = with(density) { bottomPx.toDp() }.let { if (it > 0.dp) it else minBottom }
    return top to bottom
}

@Composable
fun DiagnosisScreen(onDismiss: () -> Unit) {
    BackHandler { onDismiss() }

    var report by remember { mutableStateOf(DiagnosisEvaluator.currentReport()) }
    var batchLamp by remember { mutableStateOf(LocationSessionStore.batchLamp()) }
    var batchAttemptAt by remember { mutableStateOf(LocationSessionStore.getLastBatchAttemptAt()) }
    val config = remember { NativeConfigStore.get().diagnosis }
    val scope = rememberCoroutineScope()
    val cardShape = RoundedCornerShape(12.dp)
    val (topSafe, bottomSafe) = rememberSafeSystemPaddings()
    val appVersionLabel = remember {
        val name = DeviceInfo.getAppVersion()
        val code = runCatching {
            val ctx = SmileApplication.instance
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
        }.getOrDefault("—")
        "Versi $name ($code)"
    }

    fun refresh() {
        report = DiagnosisEvaluator.currentReport()
        batchLamp = LocationSessionStore.batchLamp()
        batchAttemptAt = LocationSessionStore.getLastBatchAttemptAt()
        LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
    }

    suspend fun refreshReady() {
        withContext(Dispatchers.IO) {
            runCatching { FcmBackendSync.refreshWebSessionCache() }
            if (PushNotificationHandler.isFirebaseConfigured()) {
                val cached = PushNotificationHandler.getCachedToken()
                if (cached.isBlank()) {
                    runCatching { PushNotificationHandler.getToken(forceRefresh = false) }
                }
            }
            runCatching { FcmBackendSync.ensureRegistered() }
        }
        refresh()
    }

    LaunchedEffect(Unit) {
        refreshReady()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DiagnosisColors.pageBg)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = topSafe + 8.dp,
                bottom = bottomSafe + 12.dp,
            ),
    ) {
        Text(
            text = config.title,
            color = DiagnosisColors.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 22.sp,
        )
        if (config.subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = config.subtitle,
                color = DiagnosisColors.muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(DiagnosisColors.cardBg)
                .border(1.dp, DiagnosisColors.border, cardShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(batchLampColor(batchLamp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = batchLampLabel(batchLamp),
                    color = DiagnosisColors.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Antrian batch lokasi",
                    color = DiagnosisColors.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatBatchAttemptJakarta(batchAttemptAt),
                color = DiagnosisColors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = true)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(report.items, key = { it.config.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(DiagnosisColors.cardBg)
                        .border(1.dp, DiagnosisColors.border, cardShape)
                        .clickable(enabled = item.config.action != null && !item.passed) {
                            val action = item.config.action ?: return@clickable
                            if (action == "sync_fcm_to_backend" || action == "register_push" || action == "retry") {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        when (action) {
                                            "sync_fcm_to_backend" -> {
                                                runCatching { FcmBackendSync.ensureRegistered(forceSync = true) }
                                                Unit
                                            }
                                            "register_push" -> {
                                                if (PushNotificationHandler.isFirebaseConfigured()) {
                                                    val cached = PushNotificationHandler.getCachedToken()
                                                    if (cached.isBlank()) {
                                                        runCatching {
                                                            PushNotificationHandler.getToken(forceRefresh = false)
                                                        }
                                                    }
                                                    runCatching { FcmBackendSync.ensureRegistered() }
                                                }
                                                Unit
                                            }
                                            else -> {
                                                runCatching { FcmBackendSync.refreshWebSessionCache() }
                                                Unit
                                            }
                                        }
                                    }
                                    refresh()
                                }
                            } else {
                                DiagnosisActions.runAction(action)
                                refresh()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.config.title,
                            color = DiagnosisColors.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val secondary = when {
                            item.config.description.isNotBlank() ->
                                "${item.detail} · ${item.config.description}"
                            else -> item.detail
                        }
                        if (secondary.isNotBlank()) {
                            Text(
                                text = secondary,
                                color = DiagnosisColors.muted,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        text = if (item.passed) "OK" else "—",
                        color = if (item.passed) DiagnosisColors.ok else DiagnosisColors.fail,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = {
                scope.launch {
                    refreshReady()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DiagnosisColors.orange,
                contentColor = Color.White,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        ) {
            Text("Perbarui diagnosis", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = DiagnosisColors.title,
            ),
            border = BorderStroke(1.dp, DiagnosisColors.border),
        ) {
            Text("Tutup", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = appVersionLabel,
            color = DiagnosisColors.muted,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}
