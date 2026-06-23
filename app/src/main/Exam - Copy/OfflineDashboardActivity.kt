package com.edukreasi.Exam

import android.app.ActivityManager
import android.os.Build
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class OfflineDashboardActivity : AppCompatActivity() {

    private lateinit var tvStudentName: TextView
    private lateinit var tvStudentInfo: TextView
    private lateinit var rvExams: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var fabSync: ExtendedFloatingActionButton
    private lateinit var syncStatusCard: MaterialCardView
    private lateinit var tvSyncStatus: TextView
    private lateinit var timeTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var batteryIcon: ImageView
    private lateinit var logoutButton: ImageButton
    private lateinit var reloadButton: ImageButton
    private lateinit var ivSyncIndicator: ImageView

    private val csvDataFetcher by lazy { CsvDataFetcher(this) }
    private val historyManager by lazy { ExamHistoryManager(this) }
    private lateinit var adapter: ExamAdapter
    private var currentStudent: JSONObject? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private lateinit var batteryStatusReceiver: BroadcastReceiver

    private val submissionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (key.startsWith("submission_status_") || key.startsWith("answer_progress_") || key.startsWith("locked_"))) {
            runOnUiThread { loadExamsFromCache() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        setContentView(R.layout.activity_offline_dashboard)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@OfflineDashboardActivity, "Gunakan tombol Logout untuk keluar dari aplikasi.", Toast.LENGTH_SHORT).show()
            }
        })

        CacheManager.init(this)
        setupDisplayEnvironment()

        timeTextView = findViewById(R.id.time_text)
        batteryTextView = findViewById(R.id.battery_text)
        batteryIcon = findViewById(R.id.battery)
        logoutButton = findViewById(R.id.logout_button)
        reloadButton = findViewById(R.id.reload_button)
        ivSyncIndicator = findViewById(R.id.iv_sync_indicator)
        tvStudentName = findViewById(R.id.tv_student_name)
        tvStudentInfo = findViewById(R.id.tv_student_info)
        rvExams = findViewById(R.id.rv_exams)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        fabSync = findViewById(R.id.fab_sync)
        syncStatusCard = findViewById(R.id.sync_status_card)
        tvSyncStatus = findViewById(R.id.tv_sync_status)

        reloadButton.setOnClickListener {
            loadExamsFromCache()
            Toast.makeText(this, "Tampilan diperbarui", Toast.LENGTH_SHORT).show()
        }

        logoutButton.setOnClickListener { showLogoutConfirmation() }

        setupBatteryReceiver()
        startRealTimeUpdates()

        currentStudent = CacheManager.getStudentInfo()
        if (currentStudent == null) { finish(); return }

        setupRecyclerView()
        loadStudentData()
        rvExams.post { loadExamsFromCache() }

        SyncWorker.enqueue(this)
        setupNetworkMonitoring()
        fabSync.setOnClickListener { performSyncData() }
    }

    private fun setupRecyclerView() {
        adapter = ExamAdapter(
            onStartClick = { exam -> checkLockAndStart(exam) },
            onStatusChanged = { _ -> loadExamsFromCache() },
            onScoreClick = { scoreData -> showScoreDialog(scoreData) }
        )
        rvExams.layoutManager = LinearLayoutManager(this)
        rvExams.adapter = adapter
    }

    private fun checkLockAndStart(exam: JSONObject) {
        val examId = exam.optString("id")
        if (CacheManager.isSessionLocked(examId)) {
            showProctorUnlockDialog(exam)
        } else {
            startExam(exam)
        }
    }

    private fun showProctorUnlockDialog(exam: JSONObject) {
        val examId = exam.optString("id")
        val dialogView = layoutInflater.inflate(R.layout.dialog_proctor_token, null)
        val inputToken = dialogView.findViewById<TextInputEditText>(R.id.et_proctor_token)

        inputToken.filters = arrayOf(android.text.InputFilter.AllCaps())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ujian Terkunci")
            .setMessage("Siswa keluar dari aplikasi sebelum selesai. Masukkan Token Pengawas untuk melanjutkan.")
            .setView(dialogView)
            .setPositiveButton("Buka Kunci", null)
            .setNegativeButton("Batal", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val token = inputToken.text.toString()
                Log.d("DebugOffline", "User entered proctor token: '$token'")
                lifecycleScope.launch {
                    val isValid = ProctorTokenManager.validateToken(this@OfflineDashboardActivity, token)
                    Log.d("DebugOffline", "Validation result for token '$token': $isValid")
                    if (isValid) {
                        CacheManager.setSessionLocked(examId, false)
                        CacheManager.setActiveToken(examId, token)
                        startExam(exam)
                        dialog.dismiss()
                    } else {
                        // Distinguish error messages per token state
                        val cleaned = token.uppercase().replace(Regex("[^A-Z0-9-]"), "")
                        val usedTokens = CacheManager.getUsedProctorTokens().map { it.replace(Regex("[^A-Z0-9-]"), "").uppercase() }
                        val message = if (usedTokens.contains(cleaned)) {
                            "Token sudah pernah dipakai!"
                        } else {
                            "Token Salah!"
                        }
                        Toast.makeText(this@OfflineDashboardActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showScoreDialog(scoreData: ExamAdapter.ScoreDialogData) {
        val dialog = ScoreDialog.newInstance(
            examName = scoreData.examName,
            score = scoreData.score,
            correctAnswers = scoreData.correctAnswers,
            totalQuestions = scoreData.totalQuestions,
            timestamp = scoreData.timestamp
        )
        dialog.show(supportFragmentManager, "ScoreDialog")
    }

    private fun loadExamsFromCache() {
        lifecycleScope.launch {
            val subjects: JSONArray = withContext(Dispatchers.IO) { CacheManager.getSubjects() } ?: JSONArray()
            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val examsList = mutableListOf<JSONObject>()

            val lastSyncTime = CacheManager.getPrefs().getLong("subjects_sync_time", 0)
            val pendingResults: JSONArray = withContext(Dispatchers.IO) { CacheManager.getResultsQueue() }
            val pendingMap = mutableMapOf<String, JSONObject>()
            for (i in 0 until pendingResults.length()) {
                val res = pendingResults.optJSONObject(i) ?: continue
                val sId = res.optString("subject_id")
                if (sId.isNotEmpty()) pendingMap[sId] = res
            }

            for (i in 0 until subjects.length()) {
                val subject = subjects.optJSONObject(i) ?: continue
                var examDate = subject.optString("examDate", "")
                if (examDate.isEmpty()) examDate = subject.optJSONObject("data")?.optString("examDate", "") ?: ""

                if (examDate == todayDate) {
                    val id = subject.optString("id")
                    val subjectCopy = JSONObject(subject.toString())

                    val localStatus = CacheManager.getSubmissionStatusString(id)
                    subjectCopy.put("_sync_status", localStatus)
                    subjectCopy.put("_last_sync_time", lastSyncTime)
                    subjectCopy.put("_is_locked", CacheManager.isSessionLocked(id))

                    if (localStatus == "none") {
                        subjectCopy.put("_has_history", false)
                    } else {
                        val historyEntry: JSONObject? = withContext(Dispatchers.IO) { historyManager.getExamResultFromHistory(id) }
                        val inQueueResult = pendingMap[id]

                        when {
                            historyEntry != null -> {
                                subjectCopy.put("_has_history", true)
                                subjectCopy.put("_history_score", historyEntry.optDouble("score", -1.0))
                                subjectCopy.put("_correct_answers", historyEntry.optInt("correctAnswers", 0))
                                subjectCopy.put("_total_questions", historyEntry.optInt("totalQuestions", 0))
                                subjectCopy.put("_submission_time", historyEntry.optLong("timestamp", 0))
                            }
                            inQueueResult != null -> {
                                subjectCopy.put("_has_history", true)
                                subjectCopy.put("_history_score", inQueueResult.optDouble("score", -1.0))
                                subjectCopy.put("_correct_answers", inQueueResult.optInt("correct_answers", 0))
                                subjectCopy.put("_total_questions", inQueueResult.optInt("total_questions", 0))
                                subjectCopy.put("_submission_time", inQueueResult.optLong("timestamp", 0))
                            }
                            else -> {
                                subjectCopy.put("_has_history", false)
                            }
                        }
                    }
                    examsList.add(subjectCopy)
                }
            }
            adapter.submitList(ArrayList(examsList))
            tvEmptyState.visibility = if (examsList.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun performSyncData(silent: Boolean = false) {
        val loadingDialog = if (!silent) {
            AlertDialog.Builder(this).setMessage("Memperbarui data...").setCancelable(false).show()
        } else null

        Log.d("DebugOffline", "--- MULAI SINKRONISASI DATA ---")

        lifecycleScope.launch {
            try {
                // 1. COBA SINKRONISASI ONLINE TERLEBIH DAHULU
                withContext(Dispatchers.IO) {
                    try {
                        Log.d("DebugOffline", "Mencoba download token dari server...")
                        ProctorTokenManager.syncTokensFromServer(this@OfflineDashboardActivity)
                        Log.d("DebugOffline", "Token API di lokal sekarang: ${CacheManager.getAvailableProctorTokens()}")
                    } catch(e: Exception) {
                        Log.e("DebugOffline", "Gagal download token: ${e.message}")
                    }
                    try {
                        Log.d("DebugOffline", "Mencoba fetchExamHistory...")
                        historyManager.fetchExamHistory()
                    } catch(e: Exception) {
                        Log.e("DebugOffline", "Gagal fetch history: ${e.message}")
                    }
                }

                // 2. COBA AMBIL SOAL DARI SERVER
                var result = JSONObject()
                try {
                    Log.d("DebugOffline", "Mencoba fetchSubjects dari server...")
                    result = withContext(Dispatchers.IO) { csvDataFetcher.fetchSubjects() }
                    Log.d("DebugOffline", "Hasil fetchSubjects: ${result.optString("status")}")
                } catch (e: Exception) {
                    Log.d("DebugOffline", "Gagal fetchSubjects (Mungkin Offline/URL kosong). Pindah ke Fallback Lokal.")
                }

                var subjects = if (result.optString("status") == "success") {
                    result.optJSONArray("subjects") ?: JSONArray()
                } else JSONArray()

                // 3. FALLBACK OFFLINE (Sangat Penting)
                if (subjects.length() == 0) {
                    Log.d("DebugOffline", "Membaca soal dari CSV lokal...")
                    withContext(Dispatchers.IO) {
                        val student = CacheManager.getStudentInfo()
                        val kelas = student?.optString("className") ?: ""
                        val csvExams = csvDataFetcher.getAllLinksByKelas(kelas, forceRefresh = true)
                        val arr = JSONArray()
                        for (exam in csvExams) {
                            val obj = JSONObject()
                            val id = exam["link"] ?: exam["mapel"] ?: UUID.randomUUID().toString()
                            obj.put("id", id)
                            obj.put("name", exam["mapel"])
                            obj.put("examDate", exam["tanggalMulai"])

                            val durasiStr = exam["durasi"] ?: ""
                            val duration = durasiStr.toIntOrNull() ?: calculateDuration(exam["jamMulai"] ?: "00:00", exam["jamSelesai"] ?: "00:00")
                            obj.put("durationMinutes", duration)

                            val dataObj = JSONObject()
                            for ((k, v) in exam) dataObj.put(k, v)
                            obj.put("data", dataObj)

                            arr.put(obj)
                        }
                        subjects = arr
                    }
                }

                // 4. SIMPAN DATA & PERBARUI UI
                if (subjects.length() > 0) {
                    val merged = withContext(Dispatchers.IO) { historyManager.mergeHistoryWithSubjects(subjects) }
                    CacheManager.saveSubjects(merged)
                    loadExamsFromCache()
                    if (!silent) Toast.makeText(this@OfflineDashboardActivity, "Data berhasil diperbarui", Toast.LENGTH_SHORT).show()
                    Log.d("DebugOffline", "--- SINKRONISASI SELESAI DENGAN DATA ---")
                } else {
                    if (!silent) Toast.makeText(this@OfflineDashboardActivity, "Tidak ada data ujian", Toast.LENGTH_SHORT).show()
                    Log.d("DebugOffline", "--- SINKRONISASI SELESAI TAPI KOSONG ---")
                }

                loadingDialog?.dismiss()
            } catch (e: Exception) {
                loadingDialog?.dismiss()
                if (!silent) Toast.makeText(this@OfflineDashboardActivity, "Terjadi kesalahan: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("DebugOffline", "Error Fatal Sinkronisasi: ${e.message}")
            }
        }
    }

    private fun calculateDuration(start: String, end: String): Int {
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.US)
            val d1 = sdf.parse(start); val d2 = sdf.parse(end)
            if (d1 != null && d2 != null) ((d2.time - d1.time) / (1000 * 60)).toInt() else 60
        } catch (e: Exception) { 60 }
    }

    private fun startExam(exam: JSONObject) {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra("EXAM_ID", exam.optString("id"))
            putExtra("EXAM_NAME", exam.optString("name"))
            putExtra("EXAM_DATA", exam.toString())

            val appMode = csvDataFetcher.getAppMode()
            if (appMode == "semi-offline" || appMode == "offline") {
                putExtra("IS_OFFLINE_EXAM", true)
            } else {
                putExtra("EXAM_URL", exam.optString("url"))
            }
        }
        startActivity(intent)
    }

    private fun loadStudentData() {
        currentStudent?.let {
            tvStudentName.text = it.optString("name", "Student Name")
            tvStudentInfo.text = "NISN: ${it.optString("nisn")} | Kelas: ${it.optString("className")}"
        }
    }

    private fun setupDisplayEnvironment() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupNetworkMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    ivSyncIndicator.setColorFilter(android.graphics.Color.GREEN)
                    SyncWorker.enqueue(this@OfflineDashboardActivity)
                }
            }
            override fun onLost(network: Network) { runOnUiThread { ivSyncIndicator.setColorFilter(android.graphics.Color.RED) } }
        })
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this).setTitle("Keluar").setMessage("Yakin keluar?")
            .setPositiveButton("Ya") { _, _ -> finishAffinity(); exitProcess(0) }
            .setNegativeButton("Tidak", null).show()
    }

    private fun startRealTimeUpdates() {
        timeHandler.post(object : Runnable {
            override fun run() {
                val cal = Calendar.getInstance()
                timeTextView.text = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
                timeHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupBatteryReceiver() {
        batteryStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                batteryTextView.text = "${(level * 100 / scale.toFloat()).toInt()}%"
                batteryIcon.setImageResource(android.R.drawable.ic_lock_power_off)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryStatusReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        CacheManager.getPrefs().registerOnSharedPreferenceChangeListener(submissionListener)
        loadExamsFromCache()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(batteryStatusReceiver) } catch (e: Exception) { }
        CacheManager.getPrefs().unregisterOnSharedPreferenceChangeListener(submissionListener)
    }
}