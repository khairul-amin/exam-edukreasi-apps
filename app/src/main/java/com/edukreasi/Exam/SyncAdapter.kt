package com.edukreasi.Exam

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class SyncAdapter : ListAdapter<JSONObject, SyncAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_exam_name)
        private val tvDate: TextView = view.findViewById(R.id.tv_exam_date)
        private val tvTime: TextView = view.findViewById(R.id.tv_exam_time)
        private val tvProgress: TextView = view.findViewById(R.id.tv_exam_progress)
        private val tvSubStatus: TextView = view.findViewById(R.id.tv_submission_status)
        private val tvBadge: TextView = view.findViewById(R.id.tv_status_badge)
        private val tvLastSync: TextView = view.findViewById(R.id.tv_last_sync) // Tambahkan ini
        private val btnStart: View = view.findViewById(R.id.btn_start_exam)
        private val btnSend: View = view.findViewById(R.id.btn_send_answers)

        fun bind(exam: JSONObject) {
            tvName.text = exam.optString("name", "Ujian")
            
            var examDate = exam.optString("examDate", "")
            if (examDate.isEmpty()) examDate = exam.optJSONObject("data")?.optString("examDate", "") ?: ""
            tvDate.text = examDate

            var examTime = exam.optString("examTime", "")
            if (examTime.isEmpty()) examTime = exam.optJSONObject("data")?.optString("examTime", "") ?: ""
            tvTime.text = examTime

            // Ambil Flag Status
            val isServerSynced = exam.optBoolean("_is_server_synced", false)
            val hasLocalHistory = exam.optBoolean("_has_local_history", false)
            val isPendingQueue = exam.optBoolean("_is_pending_queue", false)
            val answeredCount = exam.optInt("_answered_count", 0)
            
            // Logika Waktu Sinkron
            val lastSyncTime = exam.optLong("_last_sync_time", 0)
            val submissionTime = exam.optLong("_submission_time", 0)
            val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

            when {
                lastSyncTime > 0 -> tvLastSync.text = "Terakhir sinkron: ${sdf.format(Date(lastSyncTime))}"
                submissionTime > 0 -> tvLastSync.text = "Tersimpan: ${sdf.format(Date(submissionTime))}"
                else -> tvLastSync.text = "Terakhir sinkron: -"
            }

            btnStart.visibility = View.GONE
            btnSend.visibility = View.GONE

            when {
                isServerSynced -> {
                    tvBadge.text = "TERKIRIM"
                    tvBadge.setBackgroundResource(R.drawable.badge_bg_green)
                    tvProgress.text = "Status: Selesai"
                    tvSubStatus.text = "✅ Sudah Terkirim ke Server"
                    tvSubStatus.setTextColor(Color.parseColor("#10B981"))
                }
                isPendingQueue || hasLocalHistory -> {
                    tvBadge.text = "LOKAL"
                    tvBadge.setBackgroundResource(R.drawable.badge_bg_yellow)
                    tvProgress.text = if (hasLocalHistory) "Status: Selesai (Simpan Lokal)" else "Status: Menunggu Antrean"
                    tvSubStatus.text = "🟡 Belum Terkirim ke Server"
                    tvSubStatus.setTextColor(Color.parseColor("#F59E0B"))
                }
                answeredCount > 0 -> {
                    tvBadge.text = "PROGRES"
                    tvBadge.setBackgroundResource(R.drawable.badge_bg_blue)
                    tvProgress.text = "Status: Dikerjakan ($answeredCount soal)"
                    tvSubStatus.text = "🔵 Masih dalam pengerjaan"
                    tvSubStatus.setTextColor(Color.parseColor("#1E88E5"))
                }
                else -> {
                    tvBadge.text = "BELUM"
                    tvBadge.setBackgroundResource(R.drawable.badge_bg_blue)
                    tvProgress.text = "Status: Belum dimulai"
                    tvSubStatus.text = "⚪ Menunggu pelaksanaan"
                    tvSubStatus.setTextColor(Color.GRAY)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_offline_exam, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<JSONObject>() {
        override fun areItemsTheSame(oldItem: JSONObject, newItem: JSONObject) = oldItem.optString("id") == newItem.optString("id")
        override fun areContentsTheSame(oldItem: JSONObject, newItem: JSONObject) = oldItem.toString() == newItem.toString()
    }
}
