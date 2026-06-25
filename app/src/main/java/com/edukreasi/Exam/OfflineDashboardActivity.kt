package com.edukreasi.Exam

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
import android.os.Build
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
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
    private lateinit var syncStatusCard: MaterialCardView
    private lateinit var tvSyncStatus: TextView
    private lateinit var timeTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var batteryIcon: ImageView
    private lateinit var logoutButton: ImageButton
    private lateinit var reloadButton: ImageButton
    private lateinit var syncCenterButton: TextView 
    private lateinit var ivSyncIndicator: ImageView

    private val csvDataFetcher by lazy { CsvDataFetcher(this) }
    private val historyManager by lazy { ExamHistoryManager(this) }
    private lateinit var adapter: ExamAdapter
    private var currentStudent: JSONObject? = null
    private val timeHandler = Handler(Looper.getMainLooper())
    private lateinit var batteryStatusReceiver: BroadcastReceiver

    // UPDATE: Listener sekarang memantau last_cache_update agar sinkronisasi di SyncActivity langsung berefek di sini
    private val submissionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && (
            key.startsWith("submission_status_") || 
            key.startsWith("answer_progress_") || 
            key.startsWith("locked_") ||
            key == "last_cache_update" ||
            key == "subjects_sync_time"
        )) {
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
        
        setupDisplayEnvironment()
        setContentView(R.layout.activity_offline_dashboard)
        hideNavigationBar()
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@OfflineDashboardActivity, "Gunakan tombol Logout untuk keluar.", Toast.LENGTH_SHORT).show()
            }
        })

        CacheManager.init(this)

        // Inisialisasi View
        timeTextView = findViewById(R.id.time_text)
        batteryTextView = findViewById(R.id.battery_text)
        batteryIcon = findViewById(R.id.battery)
        logoutButton = findViewById(R.id.logout_button)
        reloadButton = findViewById(R.id.reload_button)
        syncCenterButton = findViewById(R.id.tv_btn_sync_center)
        ivSyncIndicator = findViewById(R.id.iv_sync_indicator)
        tvStudentName = findViewById(R.id.tv_student_name)
        tvStudentInfo = findViewById(R.id.tv_student_info)
        rvExams = findViewById(R.id.rv_exams)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        syncStatusCard = findViewById(R.id.sync_status_card)
        tvSyncStatus = findViewById(R.id.tv_sync_status)

        syncCenterButton.setOnClickListener {
            startActivity(Intent(this, SyncActivity::class.java))
        }

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
        
        setupNetworkMonitoring()
        
        SyncWorker.enqueue(this)
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
            .setMessage("Masukkan Token Pengawas untuk melanjutkan.")
            .setView(dialogView)
            .setPositiveButton("Buka Kunci", null)
            .setNegativeButton("Batal", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val token = inputToken.text.toString()
                lifecycleScope.launch {
                    val isValid = ProctorTokenManager.validateToken(this@OfflineDashboardActivity, token)
                    if (isValid) {
                        CacheManager.setSessionLocked(examId, false)
                        CacheManager.setActiveToken(examId, token)
                        startExam(exam)
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@OfflineDashboardActivity, "Token Salah!", Toast.LENGTH_SHORT).show()
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

            val pendingResults = withContext(Dispatchers.IO) { CacheManager.getResultsQueue() }
            if (pendingResults.length() > 0) {
                syncStatusCard.visibility = View.VISIBLE
                tvSyncStatus.text = "${pendingResults.length()} jawaban belum terkirim. Klik Riwayat untuk sinkron."
                syncStatusCard.setOnClickListener {
                    startActivity(Intent(this@OfflineDashboardActivity, SyncActivity::class.java))
                }
            } else {
                syncStatusCard.visibility = View.GONE
            }

            for (i in 0 until subjects.length()) {
                val subject = subjects.optJSONObject(i) ?: continue
                var examDate = subject.optString("examDate", "")
                if (examDate.isEmpty()) examDate = subject.optJSONObject("data")?.optString("examDate", "") ?: ""

                if (examDate == todayDate) {
                    val id = subject.optString("id")
                    val localStatus = CacheManager.getSubmissionStatusString(id)
                    val isLocked = CacheManager.isSessionLocked(id)

                    val subjectCopy = JSONObject(subject.toString())
                    subjectCopy.put("_sync_status", localStatus)
                    subjectCopy.put("_last_sync_time", lastSyncTime)
                    subjectCopy.put("_is_locked", isLocked)

                    val historyEntry = withContext(Dispatchers.IO) { historyManager.getExamResultFromHistory(id) }
                    val localHistory = CacheManager.getLocalHistory(id)
                    
                    when {
                        historyEntry != null -> {
                            subjectCopy.put("_has_history", true)
                            subjectCopy.put("_history_score", historyEntry.optDouble("score", -1.0))
                            subjectCopy.put("_submission_time", historyEntry.optLong("timestamp", 0))
                        }
                        localHistory != null -> {
                            subjectCopy.put("_has_history", true)
                            subjectCopy.put("_history_score", localHistory.optDouble("score", -1.0))
                            subjectCopy.put("_submission_time", localHistory.optLong("timestamp", 0))
                        }
                        else -> subjectCopy.put("_has_history", false)
                    }
                    examsList.add(subjectCopy)
                }
            }
            adapter.submitList(ArrayList(examsList))
            tvEmptyState.visibility = if (examsList.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun startExam(exam: JSONObject) {
        val intent = Intent(this, WebViewActivity::class.java).apply {
            putExtra("EXAM_ID", exam.optString("id"))
            putExtra("EXAM_NAME", exam.optString("name"))
            putExtra("EXAM_DATA", exam.toString())
            if (csvDataFetcher.getAppMode() != "online") {
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

    private fun hideNavigationBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupNetworkMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { ivSyncIndicator.setColorFilter(android.graphics.Color.GREEN) }
            }
            override fun onLost(network: Network) {
                runOnUiThread { ivSyncIndicator.setColorFilter(android.graphics.Color.RED) }
            }
        })
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this).setTitle("Keluar").setMessage("Yakin keluar dari aplikasi?")
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
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (scale > 0) level * 100 / scale.toFloat() else 0f
                batteryTextView.text = String.format(Locale.getDefault(), "%d%%", pct.toInt())
                val res = when {
                    pct > 90 -> R.drawable.ic_battery_full
                    pct > 60 -> R.drawable.ic_battery_80
                    pct > 40 -> R.drawable.ic_battery_60
                    pct > 20 -> R.drawable.ic_battery_40
                    else -> R.drawable.ic_battery_alert
                }
                batteryIcon.setImageResource(res)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
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
