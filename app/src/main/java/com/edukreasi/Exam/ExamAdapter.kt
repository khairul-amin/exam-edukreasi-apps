package com.edukreasi.Exam

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ExamAdapter(
    private val onStartClick: (JSONObject) -> Unit,
    private val onStatusChanged: ((Int) -> Unit)? = null,
    private val onScoreClick: ((ScoreDialogData) -> Unit)? = null
) : ListAdapter<JSONObject, ExamAdapter.ExamViewHolder>(DiffCallback) {

    data class ScoreDialogData(
        val examName: String,
        val score: Double,
        val correctAnswers: Int,
        val totalQuestions: Int,
        val timestamp: Long
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExamViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offline_exam, parent, false)
        return ExamViewHolder(view)
    }

    override fun onBindViewHolder(holder: ExamViewHolder, position: Int) {
        val exam = getItem(position)
        holder.bind(exam, onStartClick, onScoreClick) { 
            val currentPos = holder.adapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                onStatusChanged?.invoke(currentPos)
            }
        }
    }

    class ExamViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvExamName: TextView = itemView.findViewById(R.id.tv_exam_name)
        private val tvExamDate: TextView = itemView.findViewById(R.id.tv_exam_date)
        private val tvExamTime: TextView = itemView.findViewById(R.id.tv_exam_time)
        private val tvLastSync: TextView = itemView.findViewById(R.id.tv_last_sync)
        private val tvStatusBadge: TextView = itemView.findViewById(R.id.tv_status_badge)
        private val tvProgress: TextView = itemView.findViewById(R.id.tv_exam_progress)
        private val tvSubmissionStatus: TextView = itemView.findViewById(R.id.tv_submission_status)
        private val ivSyncStatus: ImageView = itemView.findViewById(R.id.iv_sync_status)
        private val btnStart: MaterialButton = itemView.findViewById(R.id.btn_start_exam)
        private val btnSend: MaterialButton = itemView.findViewById(R.id.btn_send_answers)

        private val handler = Handler(Looper.getMainLooper())
        private var countdownRunnable: Runnable? = null
        
        private val colorNavy = Color.parseColor("#1E293B")
        private val colorGreen = Color.parseColor("#10B981")
        private val colorYellow = Color.parseColor("#F59E0B")
        private val colorRed = Color.parseColor("#EF4444")
        private val colorPurple = Color.parseColor("#8B5CF6")
        private val colorBlue = Color.parseColor("#1E88E5")

        fun bind(
            exam: JSONObject,
            onStartClick: (JSONObject) -> Unit,
            onScoreClick: ((ScoreDialogData) -> Unit)?,
            onStatusChanged: () -> Unit
        ) {
            stopCountdown()
            val examId = exam.optString("id", "unknown")
            tvExamName.text = exam.optString("name", "Unknown Exam")
            
            var examDate = exam.optString("examDate", "")
            if (examDate.isEmpty()) examDate = exam.optJSONObject("data")?.optString("examDate", "") ?: ""
            
            var examTime = exam.optString("examTime", "00:00")
            if (examTime == "00:00") examTime = exam.optJSONObject("data")?.optString("examTime", "00:00") ?: "00:00"
            
            var duration = exam.optInt("durationMinutes", 60)
            if (duration == 60) duration = exam.optJSONObject("data")?.optInt("durationMinutes", 60) ?: 60
            
            tvExamDate.text = examDate
            tvExamTime.text = "$examTime ($duration Menit)"
            
            val submissionStatus = exam.optString("_sync_status").ifEmpty { 
                CacheManager.getSubmissionStatusString(examId) 
            }
            
            val hasHistory = exam.optBoolean("_has_history", false)
            val historyScore = exam.optDouble("_history_score", -1.0)
            val correctAnswers = exam.optInt("_correct_answers", 0)
            val totalQuestionsFromHistory = exam.optInt("_total_questions", 0)
            val submissionTime = exam.optLong("_submission_time", 0)
            
            val answerProgress = CacheManager.getAnswerProgress(examId)
            val answeredCount = answerProgress?.length() ?: 0
            
            btnStart.setOnClickListener(null)
            btnSend.setOnClickListener(null)

            updateUIState(
                exam, submissionStatus, examId, answeredCount, duration,
                hasHistory, historyScore, correctAnswers, totalQuestionsFromHistory,
                submissionTime, onStartClick, onScoreClick, onStatusChanged, examDate, examTime
            )
        }
        
        private fun stopCountdown() {
            countdownRunnable?.let { handler.removeCallbacks(it) }
            countdownRunnable = null
        }

        private fun startCountdown(
            startTimeMillis: Long,
            exam: JSONObject,
            onStartClick: (JSONObject) -> Unit,
            onScoreClick: ((ScoreDialogData) -> Unit)?,
            onStatusChanged: () -> Unit
        ) {
            stopCountdown()
            countdownRunnable = object : Runnable {
                override fun run() {
                    val now = System.currentTimeMillis()
                    val diff = startTimeMillis - now
                    if (diff <= 500) { 
                        bind(exam, onStartClick, onScoreClick, onStatusChanged)
                        onStatusChanged()
                    } else {
                        val hours = diff / (1000 * 60 * 60)
                        val minutes = (diff / (1000 * 60)) % 60
                        val seconds = (diff / 1000) % 60
                        val timeStr = if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
                                      else String.format("%02d:%02d", minutes, seconds)
                        btnStart.text = "Mulai dlm $timeStr"
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            handler.post(countdownRunnable!!)
        }

        private fun updateUIState(
            exam: JSONObject, submissionStatus: String, examId: String,
            answeredCount: Int, totalQuestions: Int, hasHistory: Boolean,
            historyScore: Double, correctAnswers: Int, totalQuestionsFromHistory: Int,
            submissionTime: Long, onStartClick: (JSONObject) -> Unit,
            onScoreClick: ((ScoreDialogData) -> Unit)?, onStatusChanged: () -> Unit,
            examDate: String, examTime: String
        ) {
            val examName = exam.optString("name", "Ujian")
            val isLocked = exam.optBoolean("_is_locked", false)

            // Reset visibility default
            btnStart.visibility = View.GONE
            btnSend.visibility = View.GONE
            ivSyncStatus.visibility = View.GONE
            tvSubmissionStatus.visibility = View.VISIBLE // PASTIKAN INI VISIBLE
            tvProgress.visibility = View.VISIBLE

            // Set Default Progress Text
            tvProgress.text = if (answeredCount > 0) "Progres: $answeredCount soal" else "Belum dikerjakan"
            tvProgress.setTextColor(colorBlue)

            if (hasHistory) {
                tvStatusBadge.text = "SELESAI"
                tvStatusBadge.setBackgroundResource(R.drawable.badge_bg_green)

                tvProgress.text = "Nilai: ${if (historyScore >= 0) String.format("%.1f", historyScore) else "-"}"
                tvProgress.setTextColor(colorGreen)

                // Atur Status Pengiriman
                tvSubmissionStatus.text = "✅ Sudah Terkirim"
                tvSubmissionStatus.setTextColor(colorGreen)

                showButton(btnStart, "Lihat Score", colorPurple, true)
                btnStart.setOnClickListener {
                    onScoreClick?.invoke(ScoreDialogData(examName, historyScore, correctAnswers, totalQuestionsFromHistory, submissionTime))
                }

                // Tampilkan tombol kirim ulang JIKA statusnya masih nyangkut di lokal (pending/failed)
                when (submissionStatus) {
                    "pending" -> {
                        tvSubmissionStatus.text = "🟡 Menunggu Kirim"
                        tvSubmissionStatus.setTextColor(colorYellow)
                        showButton(btnSend, "Kirim Jawaban", colorNavy, true)
                        btnSend.setOnClickListener {
                            CacheManager.saveSubmissionStatus(examId, "sending", "Mengirim...")
                            SyncWorker.triggerNow(itemView.context)
                            onStatusChanged()
                        }
                    }
                    "failed" -> {
                        tvSubmissionStatus.text = "❌ Gagal Mengirim"
                        tvSubmissionStatus.setTextColor(colorRed)
                        showButton(btnSend, "Kirim Ulang", colorRed, true)
                        btnSend.setOnClickListener {
                            CacheManager.saveSubmissionStatus(examId, "sending", "Mengirim...")
                            SyncWorker.triggerNow(itemView.context)
                            onStatusChanged()
                        }
                    }
                    "sending" -> {
                        tvSubmissionStatus.text = "⏳ Sedang Mengirim..."
                        tvSubmissionStatus.setTextColor(colorYellow)
                        showButton(btnSend, "Mengirim...", colorYellow, false)
                    }
                }
            }
            else if (isLocked) {
                tvStatusBadge.text = "TERKUNCI"
                tvStatusBadge.setBackgroundResource(R.drawable.badge_bg_red)

                tvProgress.text = "Progres: $answeredCount soal (Terkunci)"
                tvProgress.setTextColor(colorRed)

                tvSubmissionStatus.text = "🔒 Sesi Terhenti"
                tvSubmissionStatus.setTextColor(colorRed)

                showButton(btnStart, "Buka Kunci", colorRed, true)
                btnStart.setOnClickListener { onStartClick(exam) }
            }
            else {
                // BELUM SELESAI UJIAN ATAU SEDANG BERJALAN
                when (submissionStatus) {
                    "pending" -> {
                        tvStatusBadge.text = "SIAP"
                        tvStatusBadge.setBackgroundResource(R.drawable.badge_bg_yellow)

                        tvSubmissionStatus.text = "🟡 Belum Terkirim"
                        tvSubmissionStatus.setTextColor(colorYellow)

                        showButton(btnSend, "Kirim Jawaban", colorNavy, true)
                        btnSend.setOnClickListener {
                            CacheManager.saveSubmissionStatus(examId, "sending", "Mengirim...")
                            SyncWorker.triggerNow(itemView.context)
                            onStatusChanged()
                        }
                    }
                    "sending" -> {
                        tvStatusBadge.text = "MENGIRIM"
                        tvStatusBadge.setBackgroundResource(R.drawable.badge_bg_blue)

                        tvSubmissionStatus.text = "⏳ Sedang Mengirim..."
                        tvSubmissionStatus.setTextColor(colorBlue)

                        showButton(btnSend, "Mengirim...", colorYellow, false)
                    }
                    "failed" -> {
                        tvStatusBadge.text = "GAGAL"
                        tvStatusBadge.setBackgroundResource(R.drawable.badge_bg_red)

                        tvSubmissionStatus.text = "❌ Gagal Mengirim"
                        tvSubmissionStatus.setTextColor(colorRed)

                        showButton(btnSend, "Kirim Ulang", colorRed, true)
                        btnSend.setOnClickListener {
                            CacheManager.saveSubmissionStatus(examId, "sending", "Mengirim...")
                            SyncWorker.triggerNow(itemView.context)
                            onStatusChanged()
                        }
                    }
                    else -> {
                        val startTimeMillis = getStartTimeMillis(examDate, examTime)
                        val now = System.currentTimeMillis()

                        if (now >= startTimeMillis) {
                            tvStatusBadge.text = "AKTIF"
                            tvStatusBadge.setBackgroundResource(R.drawable.badge_bg_green)

                            tvSubmissionStatus.text = "📝 Sedang / Belum Ujian"
                            tvSubmissionStatus.setTextColor(Color.parseColor("#64748B")) // Warna Abu-abu

                            showButton(btnStart, if (answeredCount > 0) "Lanjutkan" else "Mulai Ujian", colorNavy, true)
                            btnStart.setOnClickListener { onStartClick(exam) }
                        } else {
                            tvStatusBadge.text = "TERKUNCI"
                            tvStatusBadge.setBackgroundResource(R.drawable.badge_bg_red)

                            tvProgress.text = "Waktu ujian belum dimulai"
                            tvProgress.setTextColor(colorRed)

                            tvSubmissionStatus.text = "⏳ Menunggu Waktu"
                            tvSubmissionStatus.setTextColor(colorRed)

                            showButton(btnStart, "Belum Waktunya", colorRed, false)
                            startCountdown(startTimeMillis, exam, onStartClick, onScoreClick, onStatusChanged)
                        }
                    }
                }
            }

            updateLastSyncInfo(exam, submissionTime)
        }

        private fun getStartTimeMillis(examDate: String, examTime: String): Long {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val timeSdf = SimpleDateFormat("HH:mm", Locale.US)
                val examDateObj = sdf.parse(examDate) ?: return 0
                val examTimeObj = timeSdf.parse(examTime) ?: return 0
                val examCalendar = Calendar.getInstance().apply { time = examDateObj }
                val timeCalendar = Calendar.getInstance().apply { time = examTimeObj }
                val startTime = Calendar.getInstance().apply {
                    time = examCalendar.time
                    set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                startTime.timeInMillis
            } catch (e: Exception) { 0 }
        }

        private fun showButton(button: MaterialButton, text: String, color: Int, enabled: Boolean) {
            button.visibility = View.VISIBLE
            button.text = text
            button.backgroundTintList = ColorStateList.valueOf(color)
            button.isEnabled = enabled
            button.alpha = if (enabled) 1.0f else 0.7f
        }
        
        private fun updateLastSyncInfo(exam: JSONObject, submissionTime: Long) {
            val lastSyncTime = exam.optLong("_last_sync_time", 0)
            if (lastSyncTime > 0) {
                val sdf = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                tvLastSync.text = "Terakhir sinkron: ${sdf.format(Date(lastSyncTime))}"
            } else if (submissionTime > 0) {
                val sdf = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                tvLastSync.text = "Hasil tersedia: ${sdf.format(Date(submissionTime))}"
            } else {
                tvLastSync.text = "Terakhir sinkron: -"
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<JSONObject>() {
        override fun areItemsTheSame(oldItem: JSONObject, newItem: JSONObject): Boolean = oldItem.optString("id") == newItem.optString("id")
        override fun areContentsTheSame(oldItem: JSONObject, newItem: JSONObject): Boolean {
            return oldItem.toString() == newItem.toString() && 
                   oldItem.optLong("_force_refresh", 0) == newItem.optLong("_force_refresh", 0)
        }
    }
}
