package com.edukreasi.Exam

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ExamHistoryManager(private val context: Context) {
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("exam_history_prefs", Context.MODE_PRIVATE)
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    companion object {
        private const val TAG = "ExamHistoryManager"
        private const val PREF_HISTORY_CACHE = "exam_history_cache"
        private const val PREF_HISTORY_SYNC_TIME = "history_sync_time"
        private const val CACHE_VALID_HOURS = 24
    }
    
    suspend fun fetchExamHistory(): JSONObject = withContext(Dispatchers.IO) {
        try {
            val token = CacheManager.getToken() 
                ?: return@withContext JSONObject().apply { 
                    put("status", "error")
                    put("message", "Token tidak tersedia")
                }
            
            val url = "https://exam-edukreasi-api.edukreasi.workers.dev/api/student/results"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)
                
                if (response.isSuccessful && json.optString("status") == "success") {
                    cacheExamHistory(json)
                    return@withContext json
                } else {
                    return@withContext JSONObject().apply {
                        put("status", "error")
                        put("message", json.optString("message", "Gagal ambil riwayat"))
                    }
                }
            }
        } catch (e: Exception) {
            return@withContext JSONObject().apply {
                put("status", "error")
                put("message", "Kesalahan koneksi: ${e.message}")
            }
        }
    }
    
    private fun cacheExamHistory(history: JSONObject) {
        try {
            val data = history.optJSONArray("data") ?: JSONArray()
            prefs.edit().apply {
                putString(PREF_HISTORY_CACHE, data.toString())
                putLong(PREF_HISTORY_SYNC_TIME, System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) { }
    }
    
    fun getCachedExamHistory(): JSONObject? {
        try {
            val cached = prefs.getString(PREF_HISTORY_CACHE, null) ?: return null
            return JSONObject().apply {
                put("status", "success")
                put("data", JSONArray(cached))
            }
        } catch (e: Exception) { return null }
    }

    fun isCacheValid(): Boolean {
        val syncTime = prefs.getLong(PREF_HISTORY_SYNC_TIME, 0)
        val ageHours = (System.currentTimeMillis() - syncTime) / (1000 * 60 * 60)
        return ageHours < CACHE_VALID_HOURS && prefs.getString(PREF_HISTORY_CACHE, null) != null
    }
    
    fun mergeHistoryWithSubjects(subjects: JSONArray): JSONArray {
        try {
            val historyResult = getCachedExamHistory()
            if (historyResult?.optString("status") != "success") return subjects
            
            val cachedHistory = historyResult.optJSONArray("data") ?: JSONArray()
            val historyMap = mutableMapOf<String, JSONObject>()
            for (i in 0 until cachedHistory.length()) {
                val result = cachedHistory.optJSONObject(i) ?: continue
                val subjectId = result.optString("subjectId").ifEmpty { result.optString("subject_id") }
                if (subjectId.isNotEmpty()) historyMap[subjectId] = result
            }
            
            for (i in 0 until subjects.length()) {
                val subject = subjects.optJSONObject(i) ?: continue
                val subjectId = subject.optString("id")
                val historyEntry = historyMap[subjectId]
                
                if (historyEntry != null) {
                    subject.put("_has_history", true)
                    subject.put("_history_score", historyEntry.optDouble("score", -1.0))
                    subject.put("_correct_answers", historyEntry.optInt("correctAnswers", 0))
                    subject.put("_total_questions", historyEntry.optInt("totalQuestions", 0))
                    subject.put("_submission_time", historyEntry.optLong("timestamp", 0))
                } else {
                    // JIKA TIDAK ADA DI SERVER: Pastikan semua flag history di-reset
                    subject.put("_has_history", false)
                    subject.remove("_history_score")
                    subject.remove("_correct_answers")
                    subject.remove("_total_questions")
                    subject.remove("_submission_time")

                    // PEMBERSIHAN TOTAL: Jika status lokal bukan 'none', reset ke awal
                    val localStatus = CacheManager.getSubmissionStatusString(subjectId)
                    val localHistory = CacheManager.getLocalHistory(subjectId)
                    
                    if (localStatus != "none" || localHistory != null) {
                        Log.d(TAG, "Cleaning up local data for $subjectId (deleted on server)")
                        CacheManager.saveSubmissionStatus(subjectId, "none", "")
                        CacheManager.deleteLocalHistory(subjectId) 
                        CacheManager.deleteAnswerProgress(subjectId)
                        CacheManager.setSessionLocked(subjectId, false)
                    }
                }
            }
            return subjects
        } catch (e: Exception) { return subjects }
    }
    
    fun getExamResultFromHistory(subjectId: String): JSONObject? {
        val history = getCachedExamHistory()?.optJSONArray("data") ?: JSONArray()
        for (i in 0 until history.length()) {
            val result = history.optJSONObject(i) ?: continue
            val id = result.optString("subjectId").ifEmpty { result.optString("subject_id") }
            if (id == subjectId) return result
        }
        return null
    }
}
