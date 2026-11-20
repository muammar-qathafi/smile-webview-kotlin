/**
 * Status: AKTIF
 * Flow: Proses aplikasi. Mendaftarkan fitur native, channel notifikasi, membatalkan alarm pengingat lokal yang masih terpasang, lalu memulihkan tracking shift.
 * Takeout: jangan.
 */
package co.id.multifab.smile

import co.id.multifab.smile.util.SmileLog

import android.app.Application
import co.id.multifab.smile.capacitor.LocalNotificationManager
import co.id.multifab.smile.fcm.NotificationHelper
import co.id.multifab.smile.location.LocationGpsMonitor
import co.id.multifab.smile.location.LocationNetworkMonitor
import co.id.multifab.smile.location.LocationSessionStore
import co.id.multifab.smile.location.LocationShiftSync
import co.id.multifab.smile.location.LocationTrackerService
import co.id.multifab.smile.location.LocationTrackingKillSwitch
import co.id.multifab.smile.location.LocationTrackingPlugin
import co.id.multifab.smile.location.NativeLocationController
import co.id.multifab.smile.nativeconfig.NativeConfigPlugin
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.nativefeature.NativeFeatureRegistry
import co.id.multifab.smile.platform.PushNotificationHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.ensureChannel(this)
        registerNativeFeatures()
        LocalNotificationManager.cancelInstalledAlarms()
        if (LocationTrackingKillSwitch.isOff()) {
            // Tracking mati; notifikasi persiapan Diagnosis tetap dipasang via enforceOff.
            LocationTrackingKillSwitch.enforceOff(this)
        } else {
            LocationTrackerService.showPreparationNotification(this)
        }
        LocationGpsMonitor.register(this)
        LocationNetworkMonitor.register(this)
        NativeConfigStore.addListener { config ->
            if (LocationTrackingKillSwitch.isOff()) {
                LocationTrackingKillSwitch.enforceOff(this)
                return@addListener
            }
            val interval = NativeConfigStore.locationIntervalMs(config)
            LocationSessionStore.updateIntervalMs(interval)
            NativeLocationController.applyConfigIntervalIfTracking()
            LocationShiftSync.apply()
        }
        // Refresh native-config (termasuk workShift): sekali per proses (bukan tiap screen).
        NativeConfigStore.refreshOncePerProcessAsync { ok ->
            if (LocationTrackingKillSwitch.isOff()) {
                LocationTrackingKillSwitch.enforceOff(this)
            } else {
                LocationShiftSync.applyCachedOrNormal()
                LocationTrackerService.refreshForegroundNotification(this)
            }
            SmileLog.i("SmileApp", "native-config refreshOnce ok=$ok")
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { PushNotificationHandler.getToken(forceRefresh = false) }
            runCatching {
                if (LocationTrackingKillSwitch.isOff()) {
                    LocationTrackingKillSwitch.enforceOff(this@SmileApplication)
                } else {
                    LocationShiftSync.ensureTrackingForCurrentWindow()
                }
            }
        }
    }

    private fun registerNativeFeatures() {
        NativeFeatureRegistry.register(LocationTrackingPlugin())
        NativeFeatureRegistry.register(NativeConfigPlugin())
    }

    companion object {
        lateinit var instance: SmileApplication
            private set

        @Volatile
        var isInForeground: Boolean = false
            internal set
    }
}
