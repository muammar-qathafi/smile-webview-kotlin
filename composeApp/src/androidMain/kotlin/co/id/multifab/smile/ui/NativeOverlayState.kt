/**
 * Status: AKTIF
 * Flow: State buka/tutup overlay diagnosis dan Fake GPS, plus alasan blokir.
 * Takeout: jangan.
 */
package co.id.multifab.smile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object NativeOverlayState {
    val showDiagnosisState = mutableStateOf(false)
    val showFakeGpsState = mutableStateOf(false)
    val fakeGpsReasonState = mutableStateOf("")

    var showDiagnosis: Boolean
        get() = showDiagnosisState.value
        private set(value) {
            showDiagnosisState.value = value
        }

    var showFakeGps: Boolean
        get() = showFakeGpsState.value
        private set(value) {
            showFakeGpsState.value = value
        }

    var fakeGpsReason: String
        get() = fakeGpsReasonState.value
        private set(value) {
            fakeGpsReasonState.value = value
        }

    fun openDiagnosis() {
        showDiagnosis = true
    }

    fun closeDiagnosis() {
        showDiagnosis = false
    }

    fun openFakeGps(reason: String) {
        fakeGpsReason = reason
        showFakeGps = true
    }

    fun closeFakeGps() {
        showFakeGps = false
        fakeGpsReason = ""
    }
}
