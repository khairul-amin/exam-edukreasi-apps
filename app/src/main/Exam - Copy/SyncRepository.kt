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

    suspend fun syncPendingResults(): Boolean = withContext(Dispatchers.IO) {
        CacheManager.init(context)
        val queue = CacheManager.getResultsQueue()

        if (queue.length() == 0) return@withContext true

        val token = CacheManager.getToken()
        if (token.isNullOrEmpty() || token.startsWith("offline_")) {
            Log.w("SyncRepository", "Sync tertunda: Token belum valid atau mode offline.")
            return@withContext false
        }

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

                // Pastikan payload memiliki key yang sesuai dengan Guide (subject_id dan timestamp)
                val payload = JSONObject(result.toString())
                if (!payload.has("subject_id")) payload.put("subject_id", subjectId)
                if (!payload.has("timestamp")) payload.put("timestamp", payload.optLong("submittedAt", System.currentTimeMillis()))

                val body = payload.toString().toRequestBody(JSON_TYPE)
                val targetUrl = "$baseUrl/student/results" // SESUAI GUIDE: Gunakan Plural 'results'

                Log.d("SyncRepository", "Sync Attempt: $targetUrl | Exam: $subjectId")

                val request = Request.Builder()
                    .url(targetUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseStr = response.peekBody(2048).string()
                    Log.d("SyncRepository", "Response [$subjectId]: ${response.code} - $responseStr")

                    if (response.isSuccessful) {
                        Log.d("SyncRepository", "✅ Berhasil Sinkron: $subjectId")
                        CacheManager.removeResultFromQueue(resultId)
                        CacheManager.saveSubmissionStatus(subjectId, "sent", "Jawaban berhasil dikirim")
                        CacheManager.logSyncEvent(subjectId, "sync_success", true)
                    } else {
                        allSuccess = false
                        Log.e("SyncRepository", "❌ Gagal Sinkron (${response.code})")
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

        val studentToken = CacheManager.getToken() ?: return@withContext false
        val baseUrl = getBaseUrl()
        var allSuccess = true

        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            val token = item.optString("token")

            try {
                val payload = JSONObject().apply {
                    put("type", "proctor_unlock")
                    put("token", token)
                    put("device_id", item.optString("device_id", "UNKNOWN_DEVICE"))
                }.toString().toRequestBody(JSON_TYPE)

                val request = Request.Builder()
                    .url("$baseUrl/student/exam-tokens/validate")
                    .post(payload)
                    .addHeader("Authorization", "Bearer $studentToken")
                    .build()

                client.newCall(request).execute().use { response ->
                    // Kalau berhasil (200) atau Konflik (409) artinya data sudah tercatat di server.
                    // Hapus dari antrean lokal.
                    if (response.isSuccessful || response.code == 409) {
                        CacheManager.removePendingUnlock(token)
                        Log.d("SyncRepository", "✅ Token $token tersinkronisasi (Status: ${response.code})")
                    } else {
                        allSuccess = false
                    }
                }
            } catch (e: Exception) {
                allSuccess = false
                Log.e("SyncRepository", "❌ Gagal sync token $token: ${e.message}")
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