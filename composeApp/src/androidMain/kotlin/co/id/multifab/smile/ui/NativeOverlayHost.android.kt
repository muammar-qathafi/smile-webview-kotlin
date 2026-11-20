/**
 * Status: AKTIF
 * Flow: Dialog di atas WebView untuk layar diagnosis dan layar kunci Fake GPS.
 * Takeout: jangan.
 */
package co.id.multifab.smile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import co.id.multifab.smile.diagnosis.DiagnosisScreen
import co.id.multifab.smile.fakegps.FakeGpsBlockScreen
import co.id.multifab.smile.fakegps.FakeGpsLock

@Composable
actual fun NativeOverlayHost() {
    val showDiagnosis by NativeOverlayState.showDiagnosisState
    val showFakeGps by NativeOverlayState.showFakeGpsState
    val fakeGpsReason by NativeOverlayState.fakeGpsReasonState

    // Dialog window di atas AndroidView/WebView — sibling Compose di Box sering tertutup WebView.
    if (showDiagnosis) {
        Dialog(
            onDismissRequest = { NativeOverlayState.closeDiagnosis() },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true,
            ),
        ) {
            DiagnosisScreen(onDismiss = { NativeOverlayState.closeDiagnosis() })
        }
    }

    if (showFakeGps) {
        Dialog(
            onDismissRequest = {
                // Hanya tutup jika sticky lock sudah lepas.
                if (!FakeGpsLock.isLocked()) {
                    NativeOverlayState.closeFakeGps()
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            FakeGpsBlockScreen(
                reason = fakeGpsReason.ifBlank { FakeGpsLock.reason() },
                onDismiss = {
                    if (!FakeGpsLock.isLocked()) {
                        NativeOverlayState.closeFakeGps()
                    }
                },
            )
        }
    }
}
