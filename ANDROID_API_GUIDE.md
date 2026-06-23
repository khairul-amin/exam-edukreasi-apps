# 📱 Android API Guide - Exam Edu Kreasi

API Bridge untuk Android aplikasi ujian via Cloudflare Worker.

**Base URL:** `https://exam-edukreasi-api.edukreasi.workers.dev/api`

---

## 🔐 Authentication

Student menggunakan **Bearer Token** (JWT) yang valid 24 jam.

Header untuk semua request authenticated:
```
Authorization: Bearer {token}
```

---

## 📋 Endpoint Reference

### 1️⃣ Student Login

**Endpoint:** `POST /api/student/login`

Login siswa menggunakan NISN (Nomor Induk Siswa Nasional).

**Request:**
```json
{
  "nisn": "12345678"
}
```

**Response (Success):**
```json
{
  "status": "success",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "student": {
    "schoolNpsn": "10203001",
    "schoolName": "SMA Negeri 1 Jakarta",
    "studentId": "12345678",
    "studentName": "Budi Santoso",
    "className": "XII IPA 1",
    "exp": 1719864000000
  }
}
```

**Response (Error):**
```json
{
  "status": "error",
  "message": "NISN tidak terdaftar di database."
}
```

**Status Code:** 
- `200` Success
- `400` NISN wajib diisi
- `401` NISN tidak terdaftar atau akun tidak aktif

---

### 2️⃣ Download Soal (Subjects)

**Endpoint:** `GET /api/student/subjects`

Download semua soal ujian yang tersedia untuk siswa berdasarkan class-nya.

**Headers:**
```
Authorization: Bearer {token}
```

**Response (Success):**
```json
{
  "status": "success",
  "subjects": [
    {
      "id": "sub-1719806400000-abc123",
      "teacherId": "guru-001",
      "name": "Matematika Kelas XII",
      "isVisible": true,
      "updatedAt": 1719806400000,
      "questions": [
        {
          "number": 1,
          "text": "Hasil dari 2 + 2 adalah?",
          "options": [
            { "label": "A", "text": "3" },
            { "label": "B", "text": "4" },
            { "label": "C", "text": "5" },
            { "label": "D", "text": "6" }
          ],
          "correctAnswer": "B"
        }
        // ...more questions
      ],
      "createdAt": 1719800000000
    }
    // ...more subjects
  ]
}
```

**Response (Error):**
```json
{
  "status": "error",
  "message": "Sesi siswa tidak valid atau kedaluwarsa."
}
```

**Status Code:**
- `200` Success
- `401` Token tidak valid/expired
- `500` Server error

---

### 3️⃣ Submit Hasil Ujian

**Endpoint:** `POST /api/student/results`

Submit hasil ujian siswa ke server setelah selesai mengerjakan.

**Headers:**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**Request:**
```json
{
  "id": "result-1719806400000-xyz789",
  "subject_id": "sub-1719806400000-abc123",
  "score": 85.5,
  "correct_answers": 17,
  "total_questions": 20,
  "answers": [
    { "questionNumber": 1, "selectedOption": "B", "isCorrect": true },
    { "questionNumber": 2, "selectedOption": "C", "isCorrect": false }
    // ...more answers
  ],
  "question_snapshot": {
    "totalQuestions": 20,
    "duration": 7200000,
    "passScore": 70.0
  },
  "violations": [
    { "type": "tab_switch", "timestamp": 1719806500000 },
    { "type": "window_blur", "timestamp": 1719806600000 }
  ],
  "timestamp": 1719806400000,
  "signature": "hash-signature-untuk-validasi"
}
```

**Response (Success):**
```json
{
  "status": "success",
  "message": "Hasil ujian berhasil disimpan ke server.",
  "resultId": "result-1719806400000-xyz789",
  "synced": true
}
```

**Response (Error):**
```json
{
  "status": "error",
  "message": "ID dan subject_id wajib diisi."
}
```

**Status Code:**
- `200` Success
- `400` Missing required fields
- `401` Token tidak valid/expired
- `500` Server error

---

## 🔄 Offline/Online Flow

### Workflow Ujian Semi-Online

```
1. STARTUP
   ├─ Scan QR Code (mendapat konfigurasi semi-online)
   └─ Validasi koneksi ke server

2. LOGIN
   ├─ Input NISN
   ├─ POST /api/student/login
   ├─ Simpan token di local storage
   └─ Token berlaku 24 jam

3. DOWNLOAD SOAL
   ├─ GET /api/student/subjects (jika online)
   ├─ Simpan semua soal ke local database
   ├─ Simpan timestamp sync terakhir
   └─ Notifikasi "Soal berhasil disimpan"

4. UJIAN (OFFLINE/ONLINE)
   ├─ Akses soal dari local database
   ├─ Siswa jawab soal (real-time capture)
   ├─ Jika online → Auto-sync jawaban setiap 30 detik
   ├─ Jika offline → Cache jawaban di local
   └─ Pantau violations (tab switch, window blur)

5. SELESAI UJIAN
   ├─ Hitung score dari jawaban
   ├─ Jika online → POST /api/student/results (instant)
   ├─ Jika offline → Simpan di queue, retry saat online
   └─ Tampilkan notifikasi status
```

---

## 💾 Local Storage Schema (Android)

**Subjects Table:**
```javascript
{
  id: string,
  name: string,
  data: {
    questions: [
      { number, text, options, correctAnswer }
    ]
  },
  downloadedAt: timestamp
}
```

**Results Queue (untuk offline):**
```javascript
{
  id: string,
  subject_id: string,
  score: number,
  correct_answers: number,
  total_questions: number,
  answers: array,
  question_snapshot: object,
  violations: array,
  timestamp: number,
  signature: string,
  synced: boolean,
  syncAttempts: number
}
```

---

## ⚙️ Implementation Tips

### 1. Token Management
```
- Simpan token di SharedPreferences (Android)
- Check expiry sebelum request
- Re-login otomatis jika expired
```

### 2. Offline Detection
```
- Use ConnectivityManager untuk detect internet
- Cache semua data penting ke SQLite local
- Queue requests saat offline
- Retry saat online dengan exponential backoff
```

### 3. Auto-Sync During Exam
```
- Setiap 30 detik (jika online): 
  POST /api/student/results dengan status intermediate
- Server update waktu submission
- Client konfirmasi sync status
```

### 4. Error Handling
```
- 401 Unauthorized → Re-login
- 400 Bad Request → Validasi data local
- 500 Server Error → Retry dengan exponential backoff
- Timeout → Use local queue
```

---

## 🔒 Security Notes

1. **Token Storage:** Gunakan Keystore/EncryptedSharedPreferences
2. **HTTPS Only:** Semua request via HTTPS ke Cloudflare Worker
3. **Data Validation:** Validasi jawaban di client & server
4. **Signature:** Sign results dengan private key untuk integrity check
5. **NISN Masking:** Jangan log NISN penuh di client logs

---

## 📝 Example Implementation (Kotlin)

```kotlin
// Login
val loginRequest = mapOf("nisn" to "12345678")
val response = apiClient.post("/student/login", loginRequest)
val token = response.getString("token")
SharedPreferences.save("student_token", token)

// Download Subjects
val subjects = apiClient.get(
  "/student/subjects",
  headers = mapOf("Authorization" to "Bearer $token")
)
localDb.insertSubjects(subjects)

// Submit Result
val resultData = mapOf(
  "id" to resultId,
  "subject_id" to subjectId,
  "score" to score,
  "correct_answers" to correct,
  "total_questions" to total,
  "answers" to answersList,
  "question_snapshot" to snapshot,
  "violations" to violationsList,
  "timestamp" to System.currentTimeMillis(),
  "signature" to generateSignature()
)
val result = apiClient.post(
  "/student/results",
  resultData,
  headers = mapOf("Authorization" to "Bearer $token")
)
```

---

## 🐛 Troubleshooting

| Issue | Solution |
|-------|----------|
| Token expired | Re-login dengan NISN baru |
| Soal tidak download | Check internet, retry dengan exponential backoff |
| Hasil tidak tersubmit | Check queue di local DB, retry saat online |
| 401 Unauthorized | Validasi NISN & token format |
| 500 Server Error | Check Worker logs, contact admin |

---

## 📞 Support

Untuk bantuan:
- Check Worker logs: Cloudflare Dashboard → Worker → Logs
- Database query: TiDB Console
- Client debug: Logcat di Android Studio

