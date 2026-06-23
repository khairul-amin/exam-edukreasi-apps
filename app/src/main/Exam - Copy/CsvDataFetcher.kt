package com.edukreasi.Exam

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReader
import com.opencsv.CSVReaderBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class SiswaInfo(val nama: String, val kelas: String)
data class TokenResponse(val token: String, val sekolah: String)

class CsvDataFetcher(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ExamPrefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val TAG = "CsvDataFetcher"
        private const val PREF_PUB_URL = "pubUrl"
        private const val PREF_NPSN = "npsn"
        private const val PREF_SCHOOL_NAME = "school_name"
        private const val PREF_ACTIVATION_HASH = "activation_hash"
        private const val PREF_GID_SISWA = "gid_siswa"
        private const val PREF_GID_UJIAN = "gid_ujian"
        private const val PREF_APP_MODE = "app_mode"
        private const val PREF_OFFLINE_URL = "offline_url"
        private const val PREF_CACHE_PREFIX = "cache_"
        private const val PREF_CACHE_TIME_PREFIX = "cache_time_"
        private const val PREF_EXAM_TOKENS = "exam_tokens"
        private const val PREF_USED_TOKENS = "used_tokens"

        private const val MAX_RETRIES = 2
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    }

    fun setMode(isOffline: Boolean) = prefs.edit().putString(PREF_APP_MODE, if (isOffline) "offline" else "online").apply()
    fun setAppMode(mode: String) = prefs.edit().putString(PREF_APP_MODE, mode).apply()
    fun getAppMode(): String = prefs.getString(PREF_APP_MODE, "online") ?: "online"

    fun isOfflineMode(): Boolean = prefs.getString(PREF_APP_MODE, "online").let {
        it == "offline" || it == "semi-offline" || it == "on-lan"
    }

    fun saveOfflineUrl(url: String) = prefs.edit().putString(PREF_OFFLINE_URL, url).apply()
    fun getOfflineUrl(): String? = prefs.getString(PREF_OFFLINE_URL, null)
    fun savePubUrl(pubHtmlUrl: String) = prefs.edit().putString(PREF_PUB_URL, pubHtmlUrl).apply()
    fun getPubUrl(): String? = prefs.getString(PREF_PUB_URL, null)
    fun saveNpsn(npsn: String) = prefs.edit().putString(PREF_NPSN, npsn).apply()
    fun getNpsn(): String? = prefs.getString(PREF_NPSN, null)

    fun saveSchoolName(name: String) = prefs.edit().putString(PREF_SCHOOL_NAME, name).commit()
    fun getSchoolName(): String = prefs.getString(PREF_SCHOOL_NAME, "Sekolah") ?: "Sekolah"

    fun setSemiOfflineData(npsn: String, school: String) {
        saveNpsn(npsn); saveSchoolName(school)
        prefs.edit().putString(PREF_OFFLINE_URL, "").apply()
        prefs.edit().putString(PREF_APP_MODE, "semi-offline").apply()
    }

    fun clearActivationData() {
        val editor = prefs.edit()
        editor.remove(PREF_PUB_URL); editor.remove(PREF_NPSN); editor.remove(PREF_SCHOOL_NAME)
        editor.remove(PREF_ACTIVATION_HASH); editor.remove(PREF_APP_MODE); editor.remove(PREF_OFFLINE_URL)
        editor.remove(PREF_GID_SISWA); editor.remove(PREF_GID_UJIAN)
        editor.remove(PREF_EXAM_TOKENS); editor.remove(PREF_USED_TOKENS)
        prefs.all.keys.filter { it.startsWith(PREF_CACHE_PREFIX) || it.startsWith(PREF_CACHE_TIME_PREFIX) }
            .forEach { editor.remove(it) }
        editor.commit()
    }
    
    fun saveTokenList(tokens: List<String>) {
        val tokenJson = JSONObject().apply { put("tokens", tokens) }
        prefs.edit().putString(PREF_EXAM_TOKENS, tokenJson.toString()).apply()
    }
    
    fun getAvailableTokens(): List<String> {
        val tokenJson = prefs.getString(PREF_EXAM_TOKENS, null) ?: return emptyList()
        val usedTokens = getUsedTokens()
        return try {
            val json = JSONObject(tokenJson)
            val tokens = json.optJSONArray("tokens") ?: return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until tokens.length()) {
                val token = tokens.getString(i)
                if (!usedTokens.contains(token)) result.add(token)
            }
            result
        } catch (e: Exception) { emptyList() }
    }
    
    fun getUsedTokens(): List<String> {
        val usedJson = prefs.getString(PREF_USED_TOKENS, null) ?: return emptyList()
        return try {
            val json = JSONObject(usedJson)
            val tokens = json.optJSONArray("tokens") ?: return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until tokens.length()) {
                result.add(tokens.getString(i))
            }
            result
        } catch (e: Exception) { emptyList() }
    }
    
    fun markTokenAsUsed(token: String) {
        val used = getUsedTokens().toMutableList()
        if (!used.contains(token)) {
            used.add(token)
            val json = JSONObject().apply { put("tokens", used) }
            prefs.edit().putString(PREF_USED_TOKENS, json.toString()).apply()
        }
    }

    private suspend fun <T> retryIO(block: suspend () -> T): T {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            try { return block() } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_RETRIES - 1) delay(1000L * (attempt + 1))
            }
        }
        throw lastException ?: Exception("Gagal terhubung.")
    }

    private suspend fun fetchString(url: String): String = withContext(Dispatchers.IO) {
        retryIO {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                .header("Pragma", "no-cache").header("Expires", "0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")
                response.body?.string() ?: ""
            }
        }
    }

    suspend fun ensureGidsAvailable() {
        if (isOfflineMode()) return
        val pubUrl = getPubUrl() ?: return
        if (pubUrl.contains("script.google.com")) return
        try {
            val htmlPub = fetchString(pubUrl)
            val gidSiswa = parseGidFromHtml(htmlPub, "Siswa")
            val gidUjian = parseGidFromHtml(htmlPub, "Ujian")
            if (gidSiswa != null && gidUjian != null) {
                prefs.edit().putString(PREF_GID_SISWA, gidSiswa).putString(PREF_GID_UJIAN, gidUjian).commit()
            }
        } catch (e: Exception) { Log.e(TAG, "Gagal parse GID dari HTML", e) }
    }

    suspend fun refreshCacheIfNeeded() {
        if (isOfflineMode()) return
        ensureGidsAvailable()
        for (sheet in listOf("Siswa", "Ujian")) {
            try { fetchCsvBySheetName(sheet) } catch (e: Exception) { }
        }
    }

    private fun parseGidFromHtml(html: String, sheetName: String): String? {
        val patterns = listOf(
            Regex("""\{.*?name\s*[:=]\s*['"]$sheetName['"].*?gid\s*[:=]\s*['"](-?\d+)['"].*?\}""", RegexOption.IGNORE_CASE),
            Regex("""name\s*[:=]\s*['"]$sheetName['"].*?gid\s*[:=]\s*['"](-?\d+)['"]""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) pattern.find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun getStoredGid(sheetName: String): String? = when (sheetName.lowercase()) {
        "siswa" -> prefs.getString(PREF_GID_SISWA, null)
        "ujian" -> prefs.getString(PREF_GID_UJIAN, null)
        else -> null
    }

    suspend fun invalidateAllCaches() = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(PREF_CACHE_PREFIX) || it.startsWith(PREF_CACHE_TIME_PREFIX) }
            .forEach { editor.remove(it) }
        editor.commit()
    }

    private suspend fun fetchCsvDirect(sheetName: String): String = withContext(Dispatchers.IO) {
        retryIO {
            val pubUrl = getPubUrl() ?: throw Exception("URL Kosong")
            val cacheBuster = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            val finalUrl = if (pubUrl.contains("script.google.com")) {
                val base = if (pubUrl.contains("?")) "$pubUrl&" else "$pubUrl?"
                "${base}sheet=$sheetName&cb=$cacheBuster"
            } else {
                val pubId = extractPubId(pubUrl) ?: throw Exception("ID Error")
                val gid = getStoredGid(sheetName) ?: run {
                    ensureGidsAvailable()
                    getStoredGid(sheetName) ?: throw Exception("GID Missing")
                }
                "https://docs.google.com/spreadsheets/d/e/$pubId/pub?output=csv&gid=$gid&cb=$cacheBuster"
            }
            Log.d(TAG, "Fetching CSV Direct [$sheetName] from: $finalUrl")
            fetchString(finalUrl)
        }
    }

    private suspend fun fetchCsvBySheetName(sheetName: String, bypassCache: Boolean = false): String {
        val cacheTime = prefs.getLong(PREF_CACHE_TIME_PREFIX + sheetName, 0L)
        val isExpired = System.currentTimeMillis() - cacheTime > OptimizationConfig.CACHE_VALIDITY_MS

        if (!bypassCache && !isExpired) {
            val cached = prefs.getString(PREF_CACHE_PREFIX + sheetName, null)
            if (!cached.isNullOrEmpty()) return cached
        }
        
        val freshCsv = fetchCsvDirect(sheetName)
        prefs.edit().putString(PREF_CACHE_PREFIX + sheetName, freshCsv)
            .putLong(PREF_CACHE_TIME_PREFIX + sheetName, System.currentTimeMillis()).commit()
        return freshCsv
    }

    private fun parseCsvToMaps(csvData: String): List<Map<String, String>> {
        if (csvData.isBlank()) return emptyList()
        return try {
            val firstLine = csvData.lines().firstOrNull() ?: ""
            val separator = when {
                firstLine.contains("\t") -> '\t'
                firstLine.contains(";") && !firstLine.contains(",") -> ';'
                else -> ','
            }
            val reader = CSVReaderBuilder(StringReader(csvData))
                .withCSVParser(CSVParserBuilder().withSeparator(separator).build()).build()
            val rows = reader.readAll()
            if (rows.isEmpty()) return emptyList()
            val header = rows.first().map { it.trim() }
            rows.drop(1).mapNotNull { row ->
                if (row.all { it.isBlank() }) null else {
                    val map = mutableMapOf<String, String>()
                    header.forEachIndexed { index, h -> if (index < row.size) map[h] = row[index].trim() }
                    map
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Gagal parse CSV", e); emptyList() }
    }

    private fun getValueIgnoreCase(row: Map<String, String>, key: String): String? {
        return row.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractPubId(pubUrl: String): String? {
        val patterns = listOf(Regex("/d/e/([a-zA-Z0-9_-]+)/pubhtml"), Regex("/d/e/([a-zA-Z0-9_-]+)/pub"), Regex("/d/([a-zA-Z0-9_-]+)/"))
        for (p in patterns) p.find(pubUrl)?.let { return it.groupValues[1] }
        return null
    }

    suspend fun getToken(): TokenResponse? = withContext(Dispatchers.IO) {
        retryIO {
            val npsn = getNpsn() ?: ""
            if (npsn.isBlank()) return@retryIO null
            val hash = MessageDigest.getInstance("SHA-256").digest(npsn.trim().toByteArray(Charsets.UTF_8))
                .joinToString("") { String.format("%02x", it.toInt() and 0xFF) }.slice(0..11)
            val url = "https://esmkveggutxzklavspnn.supabase.co/functions/v1/get-school-token?h=$hash&cb=${System.currentTimeMillis()}"
            val request = Request.Builder().url(url).header("apikey", BuildConfig.SUPABASE_ANON_KEY).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    TokenResponse(json.optString("token", ""), json.optString("sekolah", "Sekolah"))
                } else throw Exception("Gagal sinkron server.")
            }
        }
    }

    suspend fun getSiswaInfo(email: String, forceRefresh: Boolean = false): SiswaInfo? {
        val data = parseCsvToMaps(fetchCsvBySheetName("Siswa", bypassCache = forceRefresh))
        val target = email.trim().lowercase()
        for (row in data) {
            if (getValueIgnoreCase(row, "Email")?.lowercase() == target) {
                return SiswaInfo(getValueIgnoreCase(row, "Nama") ?: "", getValueIgnoreCase(row, "Kelas") ?: "")
            }
        }
        return null
    }

    suspend fun getAllLinksByKelas(kelas: String, forceRefresh: Boolean = false): List<Map<String, String>> {
        val data = parseCsvToMaps(fetchCsvBySheetName("Ujian", bypassCache = forceRefresh))
        val targetKelas = kelas.trim().uppercase()
        val hasil = mutableListOf<Map<String, String>>()

        val now = Date()
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val fullFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        val todayStr = dateFormatter.format(now)
        val cal = Calendar.getInstance()
        cal.time = now
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val todayOnlyDate = cal.time

        Log.d(TAG, "Filtering Ujian untuk Kelas: $targetKelas (Waktu Sekarang: ${fullFormat.format(now)})")

        for (row in data) {
            val rowKelas = getValueIgnoreCase(row, "Kelas")?.uppercase()?.trim() ?: ""
            if (rowKelas != targetKelas) continue

            val tglMulaiStr = getValueIgnoreCase(row, "Tanggal Mulai") ?: getValueIgnoreCase(row, "Tanggal") ?: getValueIgnoreCase(row, "Mulai") ?: todayStr
            val tglSelesaiStr = getValueIgnoreCase(row, "Tanggal Selesai") ?: getValueIgnoreCase(row, "Selesai") ?: tglMulaiStr
            val jamMulaiStr = getValueIgnoreCase(row, "Jam Mulai") ?: "00:00"
            val jamSelesaiStr = getValueIgnoreCase(row, "Jam Selesai") ?: "23:59"

            val startDateOnly = try { dateFormatter.parse(tglMulaiStr) } catch (e: Exception) { null }
            val endDateOnly = try { dateFormatter.parse(tglSelesaiStr) } catch (e: Exception) { null }
            val startDateTime = try { fullFormat.parse("$tglMulaiStr $jamMulaiStr") } catch (e: Exception) { null }
            val endDateTime = try { fullFormat.parse("$tglSelesaiStr $jamSelesaiStr") } catch (e: Exception) { null }

            if (startDateOnly == null || endDateOnly == null || startDateTime == null || endDateTime == null) continue

            // 1. FILTER VISIBILITAS: Ujian tetap Muncul selama hari ini berada dalam rentang Tanggal Mulai s/d Tanggal Selesai
            // Ini memastikan ujian tidak hilang walau jam sudah lewat, selama masih di hari pelaksanaan.
            if (todayOnlyDate.before(startDateOnly) || todayOnlyDate.after(endDateOnly)) {
                Log.d(TAG, "Skipping [${getValueIgnoreCase(row, "Nama Mapel")}]: Tanggal diluar rentang ($tglMulaiStr s/d $tglSelesaiStr)")
                continue
            }

            // 2. PENENTUAN STATUS (Gunakan gabungan Tanggal + Jam untuk presisi)
            val status = when {
                now.before(startDateTime) -> "SEGERA"
                now.after(endDateTime) -> "SELESAI" // Tetap muncul di daftar, tapi label SELESAI
                else -> "AKTIF"
            }

            val mapel = getValueIgnoreCase(row, "Nama Mapel") ?: getValueIgnoreCase(row, "Mapel") ?: "-"
            Log.d(TAG, "Adding Mapel: $mapel | Status: $status")

            hasil.add(mapOf(
                "mapel" to mapel,
                "kelas" to targetKelas,
                "link" to (getValueIgnoreCase(row, "Link Google Form") ?: getValueIgnoreCase(row, "Link") ?: "#"),
                "status" to status,
                "jamMulai" to jamMulaiStr,
                "jamSelesai" to jamSelesaiStr,
                "tanggalMulai" to tglMulaiStr,
                "tanggalSelesai" to tglSelesaiStr
            ))
        }
        Log.d(TAG, "Total ujian ditampilkan: ${hasil.size}")
        return hasil
    }

    suspend fun loginStudent(nisn: String): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                if (isOfflineMode() && getOfflineUrl().isNullOrEmpty()) {
                    val body = JSONObject().apply { put("nisn", nisn) }.toString()
                    val request = Request.Builder().url("https://exam-edukreasi-api.edukreasi.workers.dev/api/student/login")
                        .post(body.toRequestBody("application/json".toMediaType())).build()
                    client.newCall(request).execute().use { response ->
                        val json = JSONObject(response.body?.string() ?: "")
                        if (json.optString("status") == "success") return@withContext json
                        else return@withContext JSONObject().apply { put("status", "error"); put("message", json.optString("message", "Login gagal")) }
                    }
                }
                val data = parseCsvToMaps(fetchCsvBySheetName("Siswa"))
                val nisnLower = nisn.trim().lowercase()
                for (row in data) {
                    if ((getValueIgnoreCase(row, "NISN") ?: getValueIgnoreCase(row, "NIS"))?.lowercase() == nisnLower) {
                        return@withContext JSONObject().apply {
                            put("status", "success")
                            put("student", JSONObject().apply {
                                put("studentName", getValueIgnoreCase(row, "Nama") ?: "Siswa")
                                put("className", getValueIgnoreCase(row, "Kelas") ?: "")
                                put("schoolName", getSchoolName())
                            })
                            put("token", getValueIgnoreCase(row, "Token") ?: "offline_${System.currentTimeMillis()}")
                        }
                    }
                }
                JSONObject().apply { put("status", "error"); put("message", "NISN tidak terdaftar") }
            } catch (e: Exception) { JSONObject().apply { put("status", "error"); put("message", e.message) } }
        }
    }

    suspend fun fetchSubjects(): JSONObject {
        return withContext(Dispatchers.IO) {
            try {
                val token = CacheManager.getToken() ?: return@withContext JSONObject().apply { put("status", "error"); put("message", "Token tidak tersedia") }
                val request = Request.Builder().url("https://exam-edukreasi-api.edukreasi.workers.dev/api/student/subjects")
                    .header("Authorization", "Bearer $token").get().build()
                client.newCall(request).execute().use { response ->
                    val json = JSONObject(response.body?.string() ?: "")
                    if (response.isSuccessful && json.optString("status") == "success") return@withContext json
                    else return@withContext JSONObject().apply { put("status", "error"); put("message", "Gagal sinkron soal") }
                }
            } catch (e: Exception) { JSONObject().apply { put("status", "error"); put("message", e.message) } }
        }
    }
}
