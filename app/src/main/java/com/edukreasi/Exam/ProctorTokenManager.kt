package com.edukreasi.Exam

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manager untuk validasi Token Pengawas (Proctor Token)
 */
object ProctorTokenManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Memvalidasi token untuk membuka kunci ujian.
     */
    suspend fun validateToken(context: Context, inputToken: String): Boolean {
        val trimmedToken = inputToken.trim()
        if (trimmedToken.isEmpty()) {

            return false
        }

        // Master Bypass (case‑insensitive)
        if (trimmedToken.equals("EDUKREASI-ADMIN", ignoreCase = true)) {
                 return true
        }

        // Normalisasi token ke huruf besar untuk perbandingan konsisten
        val tokenUpper = trimmedToken.uppercase()
        // Ekstra Aman: Buang SEMUA karakter selain Huruf, Angka, dan Strip (-)
        val cleanInputToken = tokenUpper.replace(Regex("[^A-Z0-9-]"), "")
        val deviceId = getDeviceId(context)
        val studentToken = CacheManager.getToken() ?: ""

        // Aturan 3: Cek apakah token sudah PERNAH dipakai oleh siswa ini di lokal.
        val usedTokensRaw = CacheManager.getUsedProctorTokens()
        val usedTokens = usedTokensRaw.map { it.replace(Regex("[^A-Z0-9-]"), "").uppercase() }

        if (usedTokens.contains(cleanInputToken)) {

            return false
        }

        val apiBaseUrl = "https://exam-edukreasi-api.edukreasi.workers.dev/api"

        // 1. PRIORITAS: Validasi Online (Worker)
        if (isNetworkAvailable(context)) {
            Log.d("ProctorTokenDebug", "Kondisi: ONLINE. Mencoba lempar token ke Server...")
            val onlineResult = validateOnline(apiBaseUrl, cleanInputToken, studentToken, deviceId)
            when (onlineResult) {
                "success" -> {
                    Log.d("ProctorTokenDebug", "✅ Server membalas VALID.")
                    Log.d("ProctorTokenDebug", "[RESULT] true (online success)")
                    CacheManager.markProctorTokenAsUsed(cleanInputToken)
                    return true
                }
                "conflict" -> {
                    Log.w("ProctorTokenDebug", "❌ Server membalas CONFLICT (Sudah Dipakai).")
                    Log.d("ProctorTokenDebug", "[RESULT] false (online conflict)")
                    CacheManager.markProctorTokenAsUsed(cleanInputToken)
                    return false
                }
                "invalid" -> {
                    Log.w("ProctorTokenDebug", "❌ Server membalas INVALID.")
                    Log.d("ProctorTokenDebug", "[RESULT] false (online invalid)")
                    return false
                }
                else -> {
                    Log.e("ProctorTokenDebug", "⚠️ Server Error. Pindah ke Fallback Lokal (Offline Mode).")
                }
            }
        } else {
            Log.d("ProctorTokenDebug", "Kondisi: OFFLINE. Menggunakan verifikasi lokal/cache.")
        }

        // 2. FALLBACK: Validasi Offline (Pool Token dari Cache API)
        val cachedApiTokens = CacheManager.getAvailableProctorTokens()
        Log.d("ProctorTokenDebug", "3. Master Token dari Cache API Lokal: $cachedApiTokens")

        val csvFetcher = CsvDataFetcher(context)
        val rawSchoolTokens = if (cachedApiTokens.isNotEmpty()) cachedApiTokens else csvFetcher.getAvailableTokens()

        // Bersihkan token dari tanda kutip ("), kurung siku ([]), atau spasi
        val schoolTokens = rawSchoolTokens.map { it.replace(Regex("[^A-Z0-9-]"), "").uppercase() }
        Log.d("ProctorTokenDebug", "4. Master Token Bersih (schoolTokens): $schoolTokens")

        if (schoolTokens.contains(cleanInputToken)) {
            Log.d("ProctorTokenDebug", "✅ COCOK! Token ada di dalam daftar lokal. Memasukkan ke antrean Sync.")
            Log.d("ProctorTokenDebug", "[RESULT] true (offline match)")
            val pendingSync = JSONObject().apply {
                put("type", "proctor_unlock")
                put("token", cleanInputToken)
                put("device_id", deviceId)
                put("used_at", System.currentTimeMillis())
                put("sync_status", "pending")
            }
            CacheManager.queuePendingUnlock(pendingSync)
            return true
        }

        Log.d("ProctorTokenDebug", "❌ TIDAK ADA KECOCOKAN. Token dinyatakan SALAH.")
        Log.d("ProctorTokenDebug", "[RESULT] false (no match)")
        return false
    }

    private suspend fun validateOnline(apiBaseUrl: String, proctorToken: String, studentToken: String, deviceId: String): String = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("type", "proctor_unlock")
                put("token", proctorToken)
                put("device_id", deviceId)
            }

            val request = Request.Builder()
                .url("$apiBaseUrl/student/exam-tokens/validate")
                .addHeader("Authorization", "Bearer $studentToken")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val respBody = JSONObject(response.body?.string() ?: "{}")
                        if (respBody.optString("status") == "success" && respBody.optBoolean("valid", false)) {
                            "success"
                        } else {
                            "invalid"
                        }
                    }
                    409 -> "conflict"
                    in 400..499 -> "invalid"
                    else -> "error"
                }
            }
        } catch (e: Exception) {
            Log.e("ProctorTokenDebug", "API validation error: ${e.message}")
            "error"
        }
    }

    /**
     * MENDOWNLOAD ARRAY TOKEN DARI SERVER
     */
    suspend fun syncTokensFromServer(context: Context) = withContext(Dispatchers.IO) {
        if (!isNetworkAvailable(context)) return@withContext

        val studentToken = CacheManager.getToken()
        if (studentToken.isNullOrEmpty() || studentToken.startsWith("offline_")) return@withContext

        val apiBaseUrl = "https://exam-edukreasi-api.edukreasi.workers.dev/api"
        val request = Request.Builder()
            .url("$apiBaseUrl/student/exam-tokens")
            .get()
            .addHeader("Authorization", "Bearer $studentToken")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respBody = JSONObject(response.body?.string() ?: "{}")
                    if (respBody.optString("status") == "success") {
                        val tokensArray = respBody.optJSONArray("tokens")
                        if (tokensArray != null && tokensArray.length() > 0) {
                            CacheManager.saveAvailableProctorTokens(tokensArray)
                            Log.d("ProctorTokenDebug", "✅ API GET Berhasil! Menarik ${tokensArray.length()} token ke Cache Lokal.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ProctorTokenDebug", "❌ API GET Error (Download token gagal): ${e.message}")
        }
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}