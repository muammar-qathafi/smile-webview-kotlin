# Upload Smile ke Play Store agar pengguna dapat notifikasi update

AAB ini untuk aplikasi yang **sudah produksi**:

| | |
|---|---|
| Nama di Play Console | **SMILE MULTIFAB MOBILE** |
| Package | `co.id.multifab` |
| Versi | **2.6.8** (`versionCode` **2046**) |
| File | `composeApp\build\outputs\bundle\release\composeApp-release.aab` |

Jangan unggah ke draf **Smile Multifab - Employee**. Package-nya berbeda, jadi 73 pengguna produksi tidak akan mendapat update.

## Kenapa notifikasi update muncul

Play hanya menawari update ke orang yang menginstal **SMILE MULTIFAB MOBILE** dari Play Store, dan hanya jika rilis baru punya `versionCode` **lebih besar** dari yang terpasang di HP.

Setelah rilis ini tayang di jalur Produksi:

- Play menampilkan aplikasi di daftar **Perlu diupdate**.
- Perangkat yang update-otomatisnya aktif mengunduh versi baru.
- Perangkat yang update-otomatisnya mati mendapat notifikasi Play bahwa ada pembaruan.

Pengguna yang memasang APK manual (di luar Play) **tidak** masuk antrian notifikasi ini. Mereka harus sekali menginstal dari Play Store (package yang sama, `co.id.multifab`).

Banner di Beranda Play Console (“Daftarkan nama paket atau kunci…”, batas 30 September 2026) adalah verifikasi distribusi **di luar** Play. Itu tidak menggantikan rilis Produksi di atas.

## Sebelum unggah

1. Buka [Play Console](https://play.google.com/console) akun **Multi Fabrindo Gemilang**.
2. Masuk ke **SMILE MULTIFAB MOBILE**.
3. Cek rilis Produksi yang sedang tayang. Jika `versionCode`-nya sudah **2042** atau lebih, Play menolak AAB ini. Naikkan dulu di `composeApp/build.gradle.kts`:

```kotlin
versionCode = 2043
versionName = "2.6.5"
```

Lalu bangun ulang:

```bat
cd smile-2.1
gradlew.bat :composeApp:bundleRelease
```

Setiap rilis berikutnya yang ingin memunculkan notifikasi update harus menaikkan `versionCode` lagi (2043, 2044, …). `versionName` yang terlihat pengguna boleh naik mengikuti, misalnya 2.6.5.

Tanda tangan harus kunci upload yang sama dengan rilis Play sebelumnya (`keystore.properties` → `smile-release.keystore`). Kunci berbeda ditolak Play App Signing.

## Langkah unggah

1. Di aplikasi **SMILE MULTIFAB MOBILE**, buka **Uji dan rilis** → **Produksi**.
2. Pilih **Buat rilis baru**.
3. Pada **App bundle**, unggah:

`c:\Project\multifab\mfg-datacore-webview\smile-2.1\composeApp\build\outputs\bundle\release\composeApp-release.aab`

4. **Nama rilis:** `2.6.4 (2042)` — sesuaikan jika `versionCode` sudah dinaikkan.
5. **Catatan rilis** (bahasa Indonesia), contoh:

```text
Pembaruan pelacakan lokasi selama jam shift.
Sisa titik di akhir shift dikirim sekali sebelum pelacakan berhenti.
```

6. Lanjutkan sampai **Tinjau rilis**.
7. Peluncuran: pilih **100%** jika semua pengguna produksi harus segera melihat update. Angka di bawah 100% hanya menjangkau sebagian pengguna, sehingga tidak semua HP mendapat notifikasi.
8. Kirim rilis. Setelah status **Tersedia di Google Play**, Play mulai menawari update. Notifikasi tidak selalu muncul detik itu; perangkat menyelaraskan ke Play dalam beberapa jam.

## Cek bahwa notifikasi bisa sampai

- Di HP yang menginstal dari Play, buka Play Store → **Kelola aplikasi dan perangkat** → **Kelola** → **Pembaruan tersedia**. SMILE harus muncul di sana setelah rilis tayang.
- Versi di Pengaturan aplikasi menjadi **2.6.4**.
- HP yang hanya punya APK sideload tidak akan muncul di daftar itu sampai aplikasi diganti dengan instal dari Play.

Rilis uji internal/tertutup tidak mengirim notifikasi ke 73 pengguna produksi. Notifikasi update untuk mereka hanya dari jalur **Produksi**.
