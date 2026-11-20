# Setup FCM server → Android (Smile)

Agar push check-in/out dari **BE** sampai ke tray Android, tiga hal wajib ada.

## 1. Android: `google-services.json`

1. Buka [Firebase Console](https://console.firebase.google.com/) → project **smile-multifab-v2**
2. Project settings → Your apps → Android app `co.id.multifab`  
   (tambah app Android bila belum ada, package: `co.id.multifab`)
3. Unduh `google-services.json`
4. Simpan sebagai:

```
mfg-datacore-webview/smile-2.1/composeApp/google-services.json
```

Template placeholder: [`google-services.json.example`](./google-services.json.example)  
Jangan commit file asli jika berisi secret tim (boleh di `.gitignore`).

5. Rebuild APK (`assembleDebug` / release).  
   Build otomatis set `SMILE_FCM_ENABLED=true` hanya jika file ini ada & tidak kosong.

## 2. Backend: service account FCM HTTP v1

1. Firebase Console → Project settings → Service accounts → Generate new private key
2. Simpan di BE, misalnya:

```
mfg-datacore-v2-be/storage/app/fcm/service-account.json
```

3. Di `.env` BE:

```
FCM_PROJECT_ID=smile-multifab-v2
FCM_SERVICE_ACCOUNT_PATH=storage/app/fcm/service-account.json
```

4. Cek status: endpoint auth `GET /api/v1/fcm/status` (atau helper `fcm_configuration_status()`).

## 3. App: izin + sync token

1. Login Smile → Settings → aktifkan notifikasi (izin OS)
2. Token native di-sync ke `POST /api/v1/profile/fcm-token` → tabel `employee_fcms`
3. Record time (check-in/out) → BE `sendNowToBadgeNumbers` (afterResponse) → FCM → tray Android

## Verifikasi cepat

| Langkah | Hasil diharapkan |
|---------|------------------|
| Settings → notifikasi ON | Token tersimpan di `employee_fcms` untuk badge Anda |
| Record time | Tray native segera (bridge) + FCM server ke semua device badge |
| App diminimize, record dari device lain | Device ini tetap dapat FCM tray |

## Catatan

- Tanpa `google-services.json`, token Firebase Android **tidak** bisa dibuat → FCM server tidak punya tujuan.
- Tanpa service account BE, kiriman FCM gagal (absensi tetap sukses).
- Channel Android: `smile_fcm_default` (selaras `AppConfig` + payload BE `android.notification.channel_id`).
