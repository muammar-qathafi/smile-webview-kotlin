/**
 * Status: AKTIF
 * Flow: Layar kunci saat FakeGpsLock aktif. Tertutup hanya setelah Lock unlock (fix fused non-mock).
 * Takeout: jangan.
 */
package co.id.multifab.smile.fakegps

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import co.id.multifab.smile.bridge.WebViewBridgeRegistry
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.ui.NativeOverlayState
import kotlinx.coroutines.delay

private object FakeGpsColors {
    val pageBg = Color.White
    val header = Color(0xFFFF7A3A)
    val title = Color(0xFF1F2937)
    val muted = Color(0xFF6B7280)
    val border = Color(0xFFE7E0DC)
    val orange = Color(0xFFFF7A3A)
    val warningBg = Color(0xFFFFF7ED)
    val warningBorder = Color(0xFFFDE68A)
    val warningText = Color(0xFF92400E)
}

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
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.setDimAmount(1f)
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            applyFrom(insets)
            insets
        }
        ViewCompat.getRootWindowInsets(view)?.let(::applyFrom)
        ViewCompat.requestApplyInsets(view)
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(view, null) }
    }

    val top = with(density) { topPx.toDp() }.let { if (it > 0.dp) it else minTop }
    val bottom = with(density) { bottomPx.toDp() }.let { if (it > 0.dp) it else minBottom }
    return top to bottom
}

@Composable
fun FakeGpsBlockScreen(
    reason: String,
    onDismiss: () -> Unit,
) {
    val config = remember { NativeConfigStore.get().fakeGps }
    var checking by remember { mutableStateOf(false) }
    val (topSafe, bottomSafe) = rememberSafeSystemPaddings()
    val consumeClicks = remember { MutableInteractionSource() }

    fun requestRecheck() {
        FakeGpsMonitor.start()
        FakeGpsMonitor.requestFreshFixNow()
        // Tutup hanya jika Lock sudah unlock (oleh FakeGpsGuard dari fix fresh).
        if (!FakeGpsLock.isLocked()) {
            onDismiss()
            NativeOverlayState.closeFakeGps()
        }
    }

    LaunchedEffect(Unit) {
        FakeGpsMonitor.start()
        while (FakeGpsLock.isLocked()) {
            delay(1_000L)
        }
        onDismiss()
        NativeOverlayState.closeFakeGps()
    }

    BackHandler {
        // Back tidak melepas lock; hanya picu recheck.
        requestRecheck()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FakeGpsColors.pageBg)
            .clickable(
                interactionSource = consumeClicks,
                indication = null,
                onClick = {},
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(FakeGpsColors.header)
                .padding(top = topSafe + 20.dp, bottom = 28.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "!",
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = config.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (config.subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = config.subtitle,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                text = "Aplikasi terkunci",
                color = FakeGpsColors.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = config.body,
                color = FakeGpsColors.title,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = config.warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(FakeGpsColors.warningBg)
                    .border(1.dp, FakeGpsColors.warningBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                color = FakeGpsColors.warningText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            config.steps.forEach { step ->
                Text(
                    text = "• $step",
                    color = FakeGpsColors.muted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
            if (reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Detail: $reason",
                    color = FakeGpsColors.muted,
                    fontSize = 11.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(FakeGpsColors.pageBg)
                .padding(start = 24.dp, end = 24.dp, bottom = bottomSafe + 12.dp, top = 8.dp),
        ) {
            Button(
                onClick = {
                    checking = true
                    requestRecheck()
                    checking = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !checking,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FakeGpsColors.orange,
                    contentColor = Color.White,
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) {
                Text(
                    text = if (checking) "Memeriksa…" else config.retryLabel,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    WebViewBridgeRegistry.get()?.loadUrl(AppConfig.webUrl)
                    // Beranda tidak melepas lock — overlay tetap sampai unlock sah.
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = FakeGpsColors.title),
                border = BorderStroke(1.dp, FakeGpsColors.border),
            ) {
                Text(config.homeLabel, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}
