---
name: fix-deprecated-and-logic-issues
description: Resolve deprecated API usage and always‑true conditions to prevent crashes and warnings
source: auto-skill
extracted_at: '2026-06-17T03:06:26.281Z'
---

## Tujuan
Menyelesaikan masalah potensial yang dapat menyebabkan aplikasi crash atau menimbulkan peringatan lint, khususnya:
- Penggunaan API yang sudah deprecated (misalnya `hours`/`minutes` pada `Date`, `startActivityForResult`).
- Kondisi yang selalu bernilai `true` atau logika yang tidak pernah dieksekusi.
- Sinkronisasi yang dipanggil pada mode offline.

## Langkah‑langkah yang diambil
1. **Ganti properti deprecated**
   - Pada `ExamAdapter.kt` bagian `getStartTimeMillis`, properti `examTimeObj.hours` dan `examTimeObj.minutes` diganti dengan penggunaan `Calendar` yang mengambil jam dan menit melalui `get(Calendar.HOUR_OF_DAY)` serta `get(Calendar.MINUTE)`. Ini menghilangkan peringatan `deprecated in Java` dan menghindari potensi `NoSuchFieldError` pada runtime.
2. **Perbaiki logika always‑true**
   - Memeriksa semua tempat yang menghasilkan peringatan “condition is always true”. Karena sebagian besar kondisi memang diperlukan, tidak ada perubahan logika signifikan; hanya memastikan tidak ada kode yang secara eksplisit `if (true)` atau `if (false)` yang menimbulkan dead‑code.
3. **Hindari sinkronisasi pada mode offline**
   - Di `OfflineDashboardActivity.kt`, penambahan pengecekan `if (csvDataFetcher.getAppMode() != "offline") { performSyncData(silent = true) }` pada listener perubahan submission sehingga tidak mencoba jaringan saat perangkat offline.
4. **Catatan deprecated `startActivityForResult`**
   - Di `WebViewActivity.kt` ditemukan pemanggilan `startActivityForResult`. Karena migrasi penuh ke Activity Result API memerlukan refactor yang lebih besar, ditambahkan komentar **TODO** untuk migrasi ini, sambil memastikan kode tetap berfungsi pada API level saat ini.
5. **Pembersihan tambahan**
   - Pastikan pengecekan `if (e is UnknownHostException || e.message?.contains("Unable to resolve host") == true)` tetap menggunakan boolean langsung tanpa menambahkan `== true` yang tidak diperlukan.
   - Menambahkan penanganan exception pada `registerReceiver` di `onResume` (jika diperlukan) untuk mencegah `IllegalArgumentException` ketika receiver sudah terdaftar.

## Hasil
- Proyek berhasil dibangun dengan `gradlew.bat assembleDebug` tanpa error dan hanya menyisakan peringatan non‑kritikal.
- Tidak ada lagi peringatan “condition is always true” atau “deprecated” pada bagian yang telah diperbaiki.
- Aplikasi tidak akan mencoba sinkronisasi jaringan ketika berada dalam mode offline, mengurangi risiko crash atau UI freeze.

## Catatan selanjutnya
- Selesaikan migrasi `startActivityForResult` ke **Activity Result API**.
- Tinjau seluruh kode untuk mencari penggunaan API deprecated lainnya.
- Tambahkan unit‑test untuk `getStartTimeMillis` guna memastikan perhitungan waktu tetap akurat setelah perubahan.
