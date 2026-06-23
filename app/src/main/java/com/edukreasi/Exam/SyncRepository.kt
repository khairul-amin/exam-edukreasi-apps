package com.edukreasi.Exam

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun getBaseUrl(): String {
        return "https://exam-edukreasi-api.edukreasi.workers.dev/api"
    }

    // Fungsi cerdas untuk memastikan token valid sebelum mengirim data
    private suspend fun ensureValidToken(): String? {
        var token = CacheManager.getToken()

        // Jika token ada dan BUKAN token offline, langsung gunakan
        if (!token.isNullOrEmpty() && !token.startsWith("offline_")) {
            return token
        }

        // Jika kosong atau offline, lakukan Auto-Login Background
        Log.d("SyncRepository", "Token tidak valid/offline. Mencoba Auto-Login Background...")
        val studentInfo = CacheManager.getStudentInfo()
        val studentId = studentInfo?.optString("nisn", "")?.ifEmpty { studentInfo.optString("id", "") } ?: ""

        if (studentId.isNotEmpty()) {
            val csvFetcher = CsvDataFetcher(context)
            val loginResult = csvFetcher.loginStudent(studentId) // Tembak API Login

            if (loginResult.optString("status") == "success") {
                token = loginResult.optString("token", "")
                CacheManager.saveToken(token) // Simpan token asli yang baru
                Log.d("SyncRepository", "✅ Auto-Login Background sukses!")
                return token
            } else {
                Log.w("SyncRepository", "❌ Auto-Login Background gagal. Sync ditunda.")
            }
        }
        return null
    }

    suspend fun syncPendingResults(): Boolean = withContext(Dispatchers.IO) {
        CacheManager.init(context)
        val queue = CacheManager.getResultsQueue()

        if (queue.length() == 0) return@withContext true

        // Panggil fungsi pengaman token
        val token = ensureValidToken() ?: return@withContext false

        var allSuccess = true
        val baseUrl = getBaseUrl()

        for (i in 0 until queue.length()) {
            val result = queue.optJSONObject(i) ?: continue
            val resultId = result.optString("id") // ID antrean lokal

            // Ambil ID Ujian untuk update status UI
            val subjectId = result.optString("subject_id")
                .ifEmpty { result.optString("subjectId") }
                .ifEmpty { result.optString("examId") }

            try {
                CacheManager.saveSubmissionStatus(subjectId, "sending", "Mengirim jawaban...")

                // Pastikan payload memiliki key yang sesuai dengan Guide
                val payload = JSONObject(result.toString())
                if (!payload.has("subject_id")) payload.put("subject_id", subjectId)
                if (!payload.has("timestamp")) payload.put("timestamp", payload.optLong("submittedAt", System.currentTimeMillis()))

                val body = payload.toString().toRequestBody(JSON_TYPE)
                val targetUrl = "$baseUrl/student/results" // SESUAI GUIDE

                Log.d("SyncRepository", "Sync Attempt: $targetUrl | Exam: $subjectId")

                val request = Request.Builder()
                    .url(targetUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseStr = response.peekBody(2048).string()
                    Log.d("SyncRepository", "Response [$subjectId]: ${response.code} - $responseStr")

                    if (response.isSuccessful || response.code == 409) {
                        Log.d("SyncRepository", "✅ Berhasil Sinkron: $subjectId")
                        CacheManager.removeResultFromQueue(resultId)
                        CacheManager.saveSubmissionStatus(subjectId, "sent", "Jawaban berhasil dikirim")
                        CacheManager.logSyncEvent(subjectId, "sync_success", true)
                    } else {
                        allSuccess = false
                        Log.e("SyncRepository", "❌ Gagal Sinkron (${response.code})")

                        // 👇 DETEKSI 401: Hancurkan token agar Worker melakukan Auto-Login di percobaan selanjutnya
                        if (response.code == 401) {
                            Log.e("SyncRepository", "Token basi (401)! Menghapus token agar memicu Auto-Login.")
                            CacheManager.saveToken("")
                        }

                        CacheManager.saveSubmissionStatus(subjectId, "failed", "Error Server (${response.code})")
                        CacheManager.logSyncEvent(subjectId, "sync_fail_${response.code}", false)
                    }
                }
            } catch (e: Exception) {
                allSuccess = false
                Log.e("SyncRepository", "❌ Network Error: ${e.message}")
                CacheManager.saveSubmissionStatus(subjectId, "failed", "Koneksi Bermasalah")
            }
        }
        return@withContext allSuccess
    }

    suspend fun syncPendingTokens(): Boolean = withContext(Dispatchers.IO) {
        CacheManager.init(context)
        val queue = CacheManager.getPendingUnlocks()

        if (queue.length() == 0) return@withContext true

        // Panggil fungsi pengaman token
        val token = ensureValidToken() ?: return@withContext false

        var allSuccess = true
        val baseUrl = getBaseUrl()

        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            val proctorToken = item.optString("token")

            try {
                val payload = JSONObject().apply {
                    put("type", "proctor_unlock")
                    put("token", proctorToken)
                    put("device_id", item.optString("device_id", "UNKNOWN_DEVICE"))
                }.toString().toRequestBody(JSON_TYPE)

                val request = Request.Builder()
                    .url("$baseUrl/student/exam-tokens/validate")
                    .post(payload)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 409) {
                        CacheManager.removePendingUnlock(proctorToken)
                        Log.d("SyncRepository", "✅ Token $proctorToken tersinkronisasi (Status: ${response.code})")
                    } else {
                        allSuccess = false
                        // 👇 DETEKSI 401 JUGA DI SINI
                        if (response.code == 401) {
                            Log.e("SyncRepository", "Token API basi (401) saat sync unlock token! Menghapus token.")
                            CacheManager.saveToken("")
                        }
                    }
                }
            } catch (e: Exception) {
                allSuccess = false
                Log.e("SyncRepository", "❌ Gagal sync token $proctorToken: ${e.message}")
            }
        }
        return@withContext allSuccess
    }

    suspend fun syncAll(): Boolean {
        val tokensSuccess = syncPendingTokens()
        val resultsSuccess = syncPendingResults()
        return tokensSuccess && resultsSuccess
    }
}