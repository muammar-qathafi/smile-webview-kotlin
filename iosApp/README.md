# Smile Multifab — iOS Shell

Shell native **SwiftUI + WKWebView** yang memuat UI dari `https://smile.multifab.co.id`  
(setara `composeApp` Android di `smile-2.1`).

## Status

| Item | Keterangan |
|------|------------|
| Sumber Swift | Siap diedit di Windows / Mac |
| Generate `.xcodeproj` | **XcodeGen** di Mac (`project.yml`) |
| Compile / Archive / App Store | **Hanya di macOS + Xcode** |
| Fake GPS | Stub `FakeGpsMonitor` (simulasi CLLocation) — UI block penuh fase berikutnya |
| Push (APNs) | Belum — port dari FCM Android terpisah |

> Mesin Windows **tidak bisa** menghasilkan `.ipa`. Di sini Anda hanya menyiapkan sumber + generate project file lewat XcodeGen saat ada Mac (atau CI Mac).

## Struktur

```
iosApp/
  project.yml                 ← definisi Xcode (XcodeGen)
  SmileMultifab/
    SmileMultifabApp.swift
    WebShellView.swift
    SmileWebView.swift        ← WKWebView
    SmileJsBridge.swift       ← window.SmileNative ↔ JS
    FakeGpsMonitor.swift
    AppConfig.swift
    Info.plist
    Assets.xcassets/
    Base.lproj/LaunchScreen.storyboard
```

## Cursor / VS Code (Windows atau Mac)

1. Install rekomendasi ekstensi (prompt otomatis, atau Extensions):
   - **SweetPad** (`sweetpad.sweetpad`) — build/run Xcode dari editor (butuh Mac)
   - **Swift** (`sswg.swift-lang`)
   - **SwiftFormat** / **LLDB** (opsional)
2. Command Palette → **Tasks: Run Task** → **Smile: Generate iOS Xcode Project**
   - Windows: validasi sumber + instruksi Mac
   - Mac + XcodeGen: membuat `SmileMultifab.xcodeproj`

## Generate & build di Mac

```bash
brew install xcodegen
cd smile-2.1/iosApp
xcodegen generate
open SmileMultifab.xcodeproj
```

Atau:

```bash
bash scripts/generate-ios-xcode.sh
```

Di Xcode:

1. Pilih Team signing (Apple Developer)
2. Bundle ID: `co.id.multifab.smile` (ubah di `project.yml` jika perlu)
3. Tambahkan **App Icon** 1024×1024 di `Assets.xcassets/AppIcon`
4. Run di simulator / device
5. Product → Archive untuk TestFlight / App Store

## Bridge web

- User-Agent mengandung `SmileMultifab` + `iOS`
- `window.__SMILE_WEBVIEW__ = true`
- `window.SmileNative.*` → `webkit.messageHandlers.SmileNative`

Kompatibilitas penuh dengan setiap method Android masih bertahap; stub aman untuk method yang belum di-port.

## Langkah berikutnya (opsional)

- Overlay block Fake GPS (setara `FakeGpsBlockScreen`)
- APNs / Firebase Messaging iOS
- Background location / geofence
- Samakan version dengan Android di `project.yml` (`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION`)
