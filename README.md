# Smile Multifab 2.3 — KMP Hybrid Shell

Shell native **Kotlin Multiplatform** yang memuat UI web dari `https://smile.multifab.co.id`.
Perubahan fitur/UI tetap via deploy web (tanpa tunggu Play Store); push notification dan
layanan native ditangani langsung di Android.

## Arsitektur

```
smile-2.1/
  ├── composeApp/     Android KMP shell (WebView + Fake GPS + FCM)
  └── iosApp/         iOS SwiftUI shell (WKWebView) — generate Xcode di Mac
         ↕ SmileNative JS Bridge
  └── Web → smile.multifab.co.id
```

### Paket sumber Android (`composeApp/.../smile/`)

| Folder | Isi |
|--------|-----|
| `location/` | Tracking FGS, shift window, upload, session, plugin JS |
| `notify/` | FCM `smile_notify`, ikon notifikasi, stub pengingat lokal |
| `tts/` | TextToSpeech native (play / FGS media) |
| `fcm/` | FirebaseMessagingService + registrasi token BE |
| `boot/` | Restore tracking setelah reboot / update APK |
| `bridge/` | SmileJsBridge + CapacitorCompatShim |
| `capacitor/` | Compat LocalNotifications / Preferences (web legacy) |
| `nativeconfig/` | Config native dari BE |
| `permission/` | Koordinator izin runtime |
| `fakegps/` | Deteksi & blok fake GPS |
| `diagnosis/` | Layar diagnosis native |
| `ui/` `webview/` | Compose WebView shell |

### iOS (`iosApp/`)

Shell SwiftUI + WKWebView. Lihat [iosApp/README.md](iosApp/README.md).

```bat
REM Windows: validasi sumber + instruksi Mac
scripts\generate-ios-xcode.ps1
```

```bash
# Mac: generate .xcodeproj lalu buka Xcode
brew install xcodegen
bash scripts/generate-ios-xcode.sh
open iosApp/SmileMultifab.xcodeproj
```

Ekstensi Cursor yang disarankan: SweetPad + Swift (lihat `.vscode/extensions.json`).

## Setup Build

### Yang sudah terinstall di mesin ini

| Komponen | Lokasi |
|----------|--------|
| **JDK 17** (Temurin) | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` |
| **Android SDK** | `%LOCALAPPDATA%\Android\Sdk` |
| **local.properties** | Auto-generated di root proyek |

### Set environment variable (permanen, disarankan)

Tambahkan ke **System Environment Variables** di Windows:

```
JAVA_HOME = C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
ANDROID_HOME = %LOCALAPPDATA%\Android\Sdk
```

Tambahkan ke **PATH**:
```
%JAVA_HOME%\bin
%ANDROID_HOME%\platform-tools
```

### Setup ulang SDK (jika perlu)

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup-android-sdk.ps1
```

### Prasyarat tambahan
- `google-services.json` dari Firebase Console → salin ke `composeApp/google-services.json` (untuk FCM)
- `keystore.properties` untuk release signing Play Store (lihat `keystore.properties.example`)

### Build APK / AAB via CLI

```bat
cd smile-2.1
scripts\build.bat :composeApp:assembleDebug      REM APK debug
scripts\build.bat :composeApp:assembleRelease    REM APK release
scripts\build.bat :composeApp:bundleRelease      REM AAB untuk Play Store
```

**Output:**
| Artefak | Path |
|---------|------|
| APK Debug | `composeApp/build/outputs/apk/debug/composeApp-debug.apk` |
| AAB Release | `composeApp/build/outputs/bundle/release/composeApp-release.aab` |

### Build via Android Studio
1. Open project folder `smile-2.1`
2. Tunggu Gradle sync selesai
3. Run → `composeApp` pada device/emulator

## Prinsip: Tanpa Ubah Production Web

**Folder `mfg-datacore-smile` tidak disentuh.** Semua kompatibilitas dengan web production
(`https://smile.multifab.co.id`) ditangani sepenuhnya oleh shell native lewat:

1. **JS injection** saat halaman selesai load (`SmileJsBridge.injectCompatShim`)
2. **Shim Capacitor** — `window.Capacitor.isNativePlatform()`, `Capacitor.Plugins.PushNotifications`
3. **User-Agent** — suffix `SmileMultifab/2.3.0` agar `androidWebViewGate.js` mengenali app
4. **`window.__SMILE_WEBVIEW__`** — flag deteksi WebView di web production
5. **`window.SmileNative`** — bridge native via `@JavascriptInterface`

Web production yang sudah jalan di Capacitor **tidak perlu deploy ulang** untuk shell KMP baru.

## JS Bridge (Native → Web, tanpa ubah web)

Native menyuntikkan shim setelah `onPageFinished`. Web production memakai API yang sama:

| Web production memanggil | Native menyediakan |
|--------------------------|-------------------|
| `Capacitor.isNativePlatform()` | Shim → `true` |
| `Capacitor.Plugins.PushNotifications.*` | Shim → `SmileNative` bridge |
| `Capacitor.Plugins.SmileReminderTts.*` | Shim → `ReminderTtsScheduler` (AlarmManager) |
| `window.SmileNative` | `@JavascriptInterface` |
| `smile-native-push-received` event | FCM service dispatch |
| UA mengandung `SmileMultifab` | `AppConfig.USER_AGENT_SUFFIX` |

Event web (sudah dipakai production):
- `smile-native-ready` — bridge siap
- `smile-fcm-token` — token FCM baru
- `smile-native-push-received` — push di foreground (sama seperti versi Capacitor)

## Perbedaan vs Capacitor (smile-1.1)

| Fitur | Capacitor | KMP Hybrid |
|-------|-----------|------------|
| UI | WebView remote | WebView remote |
| FCM | Plugin Capacitor | Native `SmileFirebaseMessagingService` |
| Background data push | Tidak ada WorkManager | `NotificationWorker` |
| Secure storage | localStorage | `EncryptedSharedPreferences` |
| Update UI | Deploy web | Deploy web |

## Tahap Berikutnya

- Deep link notifikasi → route web
- Geolocation / camera native bridge
- Salin icon launcher dari smile-1.1
- Uji di device fisik (FCM + TTS reminder)
