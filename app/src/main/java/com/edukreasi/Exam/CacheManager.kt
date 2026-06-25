package com.edukreasi.Exam

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object CacheManager {
    private var sharedPrefs: SharedPreferences? = null
    private var gson: Gson? = null
    private var cacheDir: File? = null

    fun init(context: Context) {
        if (sharedPrefs != null) return
        try {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            sharedPrefs = EncryptedSharedPreferences.create(
                context,
                "exam_cache_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            gson = Gson()
            cacheDir = File(context.cacheDir, "exam_data").apply { mkdirs() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureInit() {
        if (sharedPrefs == null || cacheDir == null) throw RuntimeException("CacheManager belum di-init")
    }

    fun getPrefs(): SharedPreferences {
        ensureInit()
        return sharedPrefs!!
    }

    fun notifyDataChanged() {
        ensureInit()
        sharedPrefs!!.edit().putLong("last_cache_update", System.currentTimeMillis()).apply()
    }

    // ==================== AUTH & STUDENT INFO ====================

    fun saveToken(token: String) {
        ensureInit()
        sharedPrefs!!.edit().putString("student_token", token).apply()
    }

    fun getToken(): String? {
        ensureInit()
        return sharedPrefs!!.getString("student_token", null)
    }

    fun saveStudentInfo(nisn: String, name: String, className: String, schoolName: String) {
        ensureInit()
        val json = JSONObject().apply {
            put("nisn", nisn); put("name", name); put("className", className)
            put("schoolName", schoolName); put("savedAt", System.currentTimeMillis())
        }
        sharedPrefs!!.edit().putString("student_info", json.toString()).apply()
        notifyDataChanged()
    }

    fun getStudentInfo(): JSONObject? {
        ensureInit()
        val json = sharedPrefs!!.getString("student_info", null) ?: return null
        return try { JSONObject(json) } catch (e: Exception) { null }
    }

    // ==================== SUBJECTS & RESULTS ====================

    fun saveSubjects(subjects: JSONArray) {
        ensureInit()
        val file = File(cacheDir!!, "subjects_cache.json")
        file.writeText(subjects.toString())
        sharedPrefs!!.edit().putLong("subjects_sync_time", System.currentTimeMillis()).apply()
        notifyDataChanged()
    }

    fun getSubjects(): JSONArray? {
        ensureInit()
        val file = File(cacheDir!!, "subjects_cache.json")
        return if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { null }
        } else null
    }

    fun saveAnswerProgress(examId: String, answers: JSONArray) {
        ensureInit()
        val file = File(cacheDir!!, "answers_$examId.json")
        file.writeText(answers.toString())
        notifyDataChanged()
    }

    fun getAnswerProgress(examId: String): JSONArray? {
        ensureInit()
        val file = File(cacheDir!!, "answers_$examId.json")
        return if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { null }
        } else null
    }

    fun deleteAnswerProgress(examId: String) {
        ensureInit()
        File(cacheDir!!, "answers_$examId.json").delete()
        sharedPrefs?.edit()?.remove("timer_$examId")?.remove("duration_$examId")?.apply()
        notifyDataChanged()
    }

    fun saveSubmissionStatus(examId: String, status: String, message: String = "") {
        ensureInit()
        val submission = JSONObject().apply {
            put("examId", examId); put("status", status); put("message", message); put("lastAttempt", System.currentTimeMillis())
        }
        sharedPrefs!!.edit().putString("submission_status_$examId", submission.toString()).apply()
        notifyDataChanged()
    }

    fun getSubmissionStatusString(examId: String): String {
        ensureInit()
        val json = sharedPrefs!!.getString("submission_status_$examId", null) ?: return "none"
        return try { JSONObject(json).optString("status", "none") } catch (e: Exception) { "none" }
    }

    // ==================== LOCK & PROCTOR SYSTEM ====================

    fun setSessionLocked(examId: String, locked: Boolean) {
        ensureInit()
        sharedPrefs!!.edit().putBoolean("locked_$examId", locked).apply()
        notifyDataChanged()
    }

    fun isSessionLocked(examId: String): Boolean {
        ensureInit()
        return sharedPrefs!!.getBoolean("locked_$examId", false)
    }

    fun setActiveToken(examId: String, token: String) {
        ensureInit()
        sharedPrefs!!.edit().putString("active_token_$examId", token).apply()
        notifyDataChanged()
    }

    fun getActiveToken(examId: String): String? {
        ensureInit()
        return sharedPrefs!!.getString("active_token_$examId", null)
    }

    fun saveExamAttempt(examId: String, token: String, exitReason: String) {
        ensureInit()
        val attemptsJson = sharedPrefs!!.getString("attempts_$examId", "[]")
        val attemptsArray = try { JSONArray(attemptsJson) } catch (e: Exception) { JSONArray() }

        val newAttempt = JSONObject().apply {
            put("attempt_id", "ATTEMPT_" + System.currentTimeMillis())
            put("token", token)
            put("status", "locked")
            put("exit_reason", exitReason)
            put("locked_at", System.currentTimeMillis())
        }

        attemptsArray.put(newAttempt)
        sharedPrefs!!.edit().putString("attempts_$examId", attemptsArray.toString()).apply()
        notifyDataChanged()
    }

    fun getUsedProctorTokens(): Set<String> {
        ensureInit()
        return sharedPrefs!!.getStringSet("used_proctor_tokens", emptySet()) ?: emptySet()
    }

    fun markProctorTokenAsUsed(token: String) {
        ensureInit()
        val used = getUsedProctorTokens().toMutableSet()
        used.add(token)
        sharedPrefs!!.edit().putStringSet("used_proctor_tokens", used).apply()
        notifyDataChanged()
    }

    fun queuePendingUnlock(unlockData: JSONObject) {
        ensureInit()
        val file = File(cacheDir!!, "pending_unlocks.json")
        val queue = if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        } else JSONArray()
        queue.put(unlockData)
        file.writeText(queue.toString())
        notifyDataChanged()
    }

    fun getPendingUnlocks(): JSONArray {
        ensureInit()
        val file = File(cacheDir!!, "pending_unlocks.json")
        return if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        } else JSONArray()
    }

    fun removePendingUnlock(token: String) {
        ensureInit()
        val file = File(cacheDir!!, "pending_unlocks.json")
        if (!file.exists()) return
        try {
            val queue = JSONArray(file.readText())
            val newQueue = JSONArray()
            for (i in 0 until queue.length()) {
                val item = queue.getJSONObject(i)
                if (item.optString("token") != token) {
                    newQueue.put(item)
                }
            }
            file.writeText(newQueue.toString())
            notifyDataChanged()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== UTILS ====================

    fun getPendingResultsCount(): Int {
        ensureInit()
        val file = File(cacheDir!!, "results_queue.json")
        return if (file.exists()) {
            try { JSONArray(file.readText()).length() } catch (e: Exception) { 0 }
        } else 0
    }

    fun queueResult(result: JSONObject) {
        ensureInit()
        val file = File(cacheDir!!, "results_queue.json")
        val queue = if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        } else JSONArray()

        if (!result.has("id")) {
            result.put("id", UUID.randomUUID().toString())
        }

        queue.put(result)
        file.writeText(queue.toString())
        notifyDataChanged()
    }

    fun getResultsQueue(): JSONArray {
        ensureInit()
        val file = File(cacheDir!!, "results_queue.json")
        return if (file.exists()) {
            try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
        } else JSONArray()
    }

    fun removeResultFromQueue(id: String) {
        ensureInit()
        val file = File(cacheDir!!, "results_queue.json")
        if (!file.exists()) return

        try {
            val queue = JSONArray(file.readText())
            val newQueue = JSONArray()
            for (i in 0 until queue.length()) {
                val item = queue.getJSONObject(i)
                if (item.optString("id") != id) {
                    newQueue.put(item)
                }
            }
            file.writeText(newQueue.toString())
            notifyDataChanged()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun logSyncEvent(id: String, status: String, success: Boolean) {
        ensureInit()
        val history = sharedPrefs!!.getString("sync_history", "[]")
        val array = try { JSONArray(history) } catch (e: Exception) { JSONArray() }

        val event = JSONObject().apply {
            put("id", id)
            put("status", status)
            put("success", success)
            put("timestamp", System.currentTimeMillis())
        }

        val newArray = JSONArray()
        newArray.put(event)
        for (i in 0 until Math.min(array.length(), 49)) {
            newArray.put(array.get(i))
        }

        sharedPrefs!!.edit().putString("sync_history", newArray.toString()).apply()
        notifyDataChanged()
    }

    fun saveTimeLeft(examId: String, seconds: Int, totalDurationMinutes: Int) {
        ensureInit()
        sharedPrefs!!.edit().putInt("timer_$examId", seconds).putInt("duration_$examId", totalDurationMinutes).apply()
    }

    fun getTimeLeft(examId: String, currentTotalDuration: Int): Int {
        ensureInit()
        val savedDuration = sharedPrefs!!.getInt("duration_$examId", -1)
        if (savedDuration != currentTotalDuration) return -1
        return sharedPrefs!!.getInt("timer_$examId", -1)
    }

    fun clearAllCache() {
        ensureInit()
        sharedPrefs!!.edit().clear().apply()
        cacheDir?.listFiles()?.forEach { it.delete() }
        notifyDataChanged()
    }

    // ==================== API PROCTOR TOKENS ====================

    fun saveAvailableProctorTokens(tokensArray: JSONArray) {
        ensureInit()
        sharedPrefs!!.edit().putString("api_proctor_tokens", tokensArray.toString()).apply()
        notifyDataChanged()
    }

    fun getAvailableProctorTokens(): List<String> {
        ensureInit()
        val list = mutableListOf<String>()
        val savedStr = sharedPrefs!!.getString("api_proctor_tokens", "[]")
        try {
            val array = JSONArray(savedStr)
            for (i in 0 until array.length()) {
                list.add(array.optString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    // ==================== ARSIP NILAI LOKAL (ANTI HILANG) ====================
    fun saveLocalHistory(examId: String, result: JSONObject) {
        ensureInit()
        sharedPrefs!!.edit().putString("local_history_$examId", result.toString()).apply()
        notifyDataChanged()
    }

    fun getLocalHistory(examId: String): JSONObject? {
        ensureInit()
        val data = sharedPrefs!!.getString("local_history_$examId", null)
        return if (data != null) JSONObject(data) else null
    }

    fun deleteLocalHistory(examId: String) {
        ensureInit()
        sharedPrefs!!.edit().remove("local_history_$examId").apply()
        notifyDataChanged()
    }

}
