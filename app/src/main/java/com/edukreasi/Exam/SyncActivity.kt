package com.edukreasi.Exam

import androidx.core.view.ViewCompat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class SyncActivity : AppCompatActivity() {

    private lateinit var rvHistory: RecyclerView
    private lateinit var tvEmptyHistory: TextView
    private lateinit var cardSyncStatus: MaterialCardView
    private lateinit var pbSync: ProgressBar
    private lateinit var tvSyncLabel: TextView
    private lateinit var fabStartSync: ExtendedFloatingActionButton

    private val csvDataFetcher by lazy { CsvDataFetcher(this) }
    private val historyManager by lazy { ExamHistoryManager(this) }
    private val syncRepository by lazy { SyncRepository(this) }
    private lateinit var syncAdapter: SyncAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CacheManager.init(this)
        
        setupDisplayEnvironment()
        setContentView(R.layout.activity_sync_history)
        
        // Perbaikan: Gunakan AppBarLayout untuk menangani Insets agar Toolbar tidak terpotong
        val appBar = findViewById<View>(R.id.app_bar)
        appBar?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { view, insets ->
                val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                // Gunakan padding agar Toolbar turun ke bawah Status Bar
                view.setPadding(0, statusBar.top, 0, 0)
                insets
            }
            // Paksa sistem untuk mengirim ulang insets
            it.requestApplyInsets()
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        hideNavigationBar()

        rvHistory = findViewById(R.id.rv_history)
        tvEmptyHistory = findViewById(R.id.tv_empty_history)
        cardSyncStatus = findViewById(R.id.card_sync_status)
        pbSync = findViewById(R.id.pb_sync)
        tvSyncLabel = findViewById(R.id.tv_sync_label)
        fabStartSync = findViewById(R.id.fab_start_sync)

        setupRecyclerView()
        loadLocalData()

        fabStartSync.setOnClickListener {
            performFullSync()
        }
    }

    private fun setupDisplayEnvironment() {
        // Matikan fit system windows otomatis agar kita bisa kontrol manual
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Buat Status Bar transparan agar warna Navy AppBarLayout terlihat
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun hideNavigationBar() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // Hanya sembunyikan Navigation Bar, biarkan Status Bar tetap muncul
        controller?.hide(WindowInsetsCompat.Type.navigationBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
    }

    private fun setupRecyclerView() {
        syncAdapter = SyncAdapter()
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = syncAdapter
    }

    private fun loadLocalData() {
        lifecycleScope.launch {
            val subjects = withContext(Dispatchers.IO) { CacheManager.getSubjects() } ?: JSONArray()
            val pendingResults = withContext(Dispatchers.IO) { CacheManager.getResultsQueue() }
            val examsList = mutableListOf<JSONObject>()

            val sharedPrefs = getSharedPreferences("exam_history_prefs", MODE_PRIVATE)
            val lastGlobalSync = sharedPrefs.getLong("history_sync_time", 0)

            val pendingMap = mutableMapOf<String, JSONObject>()
            for (i in 0 until pendingResults.length()) {
                val res = pendingResults.optJSONObject(i) ?: continue
                val sId = res.optString("subject_id").ifEmpty { res.optString("subjectId") }
                if (sId.isNotEmpty()) pendingMap[sId] = res
            }

            for (i in 0 until subjects.length()) {
                val subject = subjects.optJSONObject(i) ?: continue
                val id = subject.optString("id")
                val subjectCopy = JSONObject(subject.toString())

                val historyEntry = withContext(Dispatchers.IO) { historyManager.getExamResultFromHistory(id) }
                val localHistory = CacheManager.getLocalHistory(id)
                val inQueue = pendingMap[id]
                val localStatus = CacheManager.getSubmissionStatusString(id)
                val answerProgress = CacheManager.getAnswerProgress(id)
                val answeredCount = answerProgress?.length() ?: 0

                subjectCopy.put("_is_server_synced", historyEntry != null)
                subjectCopy.put("_has_local_history", localHistory != null)
                subjectCopy.put("_is_pending_queue", inQueue != null || localStatus == "pending" || localStatus == "failed")
                subjectCopy.put("_answered_count", answeredCount)
                subjectCopy.put("_last_sync_time", lastGlobalSync)

                if (historyEntry != null) {
                    subjectCopy.put("_submission_time", historyEntry.optLong("timestamp", 0))
                } else if (localHistory != null) {
                    subjectCopy.put("_submission_time", localHistory.optLong("timestamp", 0))
                }

                examsList.add(subjectCopy)
            }

            // Sort: Pending -> Local History -> Answered -> Rest
            examsList.sortWith(compareByDescending<JSONObject> { it.optBoolean("_is_server_synced") }
                .thenByDescending { it.optBoolean("_is_pending_queue") }
                .thenByDescending { it.optBoolean("_has_local_history") }
                .thenByDescending { it.optInt("_answered_count") })

            syncAdapter.submitList(ArrayList(examsList))
            tvEmptyHistory.visibility = if (examsList.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun performFullSync() {
        lifecycleScope.launch {
            setLoading(true, "Memulai sinkronisasi...")
            try {
                tvSyncLabel.text = "Mengunggah data ke server..."
                syncRepository.syncPendingResults()
                syncRepository.syncPendingTokens()
                
                tvSyncLabel.text = "Memperbarui jadwal & riwayat..."
                val studentInfo = CacheManager.getStudentInfo()
                val studentId = studentInfo?.optString("nisn", "")?.ifEmpty { studentInfo?.optString("id", "") } ?: ""
                
                if (studentId.isNotEmpty()) {
                    val result = withContext(Dispatchers.IO) { csvDataFetcher.fetchSubjects() }
                    if (result.optString("status") == "success") {
                        val subjects = result.optJSONArray("subjects") ?: JSONArray()
                        ProctorTokenManager.syncTokensFromServer(this@SyncActivity)
                        historyManager.fetchExamHistory()
                        
                        val merged = withContext(Dispatchers.IO) { historyManager.mergeHistoryWithSubjects(subjects) }
                        CacheManager.saveSubjects(merged)
                    }
                }
                
                delay(800)
                loadLocalData()
                Toast.makeText(this@SyncActivity, "Sinkronisasi Berhasil", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SyncActivity", "Sync Error: ${e.message}")
                Toast.makeText(this@SyncActivity, "Gagal Sinkron", Toast.LENGTH_SHORT).show()
            } finally { setLoading(false) }
        }
    }

    private fun setLoading(isLoading: Boolean, message: String = "") {
        cardSyncStatus.visibility = if (isLoading) View.VISIBLE else View.GONE
        fabStartSync.isEnabled = !isLoading
        if (message.isNotEmpty()) tvSyncLabel.text = message
    }
}
