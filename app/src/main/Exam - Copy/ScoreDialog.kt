package com.edukreasi.Exam

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.*

class ScoreDialog : DialogFragment() {
    
    companion object {
        private const val ARG_EXAM_NAME = "exam_name"
        private const val ARG_SCORE = "score"
        private const val ARG_CORRECT = "correct_answers"
        private const val ARG_TOTAL = "total_questions"
        private const val ARG_TIMESTAMP = "timestamp"
        private const val ARG_FINISH_ON_CLOSE = "finish_on_close"
        
        fun newInstance(
            examName: String,
            score: Double,
            correctAnswers: Int,
            totalQuestions: Int,
            timestamp: Long,
            finishOnClose: Boolean = false
        ): ScoreDialog {
            return ScoreDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_EXAM_NAME, examName)
                    putDouble(ARG_SCORE, score)
                    putInt(ARG_CORRECT, correctAnswers)
                    putInt(ARG_TOTAL, totalQuestions)
                    putLong(ARG_TIMESTAMP, timestamp)
                    putBoolean(ARG_FINISH_ON_CLOSE, finishOnClose)
                }
            }
        }
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val args = requireArguments()
        
        val examName = args.getString(ARG_EXAM_NAME, "Ujian")
        val score = args.getDouble(ARG_SCORE, 0.0)
        val correctAnswers = args.getInt(ARG_CORRECT, 0)
        val totalQuestions = args.getInt(ARG_TOTAL, 0)
        val timestamp = args.getLong(ARG_TIMESTAMP, 0)
        val finishOnClose = args.getBoolean(ARG_FINISH_ON_CLOSE, false)
        
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_score_detail, null)
        
        // Inisialisasi views
        val tvExamName: TextView = view.findViewById(R.id.tv_exam_name_detail)
        val tvScore: TextView = view.findViewById(R.id.tv_score_large)
        val tvScoreLabel: TextView = view.findViewById(R.id.tv_score_label)
        val tvCorrectAnswers: TextView = view.findViewById(R.id.tv_correct_answers)
        val tvTotalQuestions: TextView = view.findViewById(R.id.tv_total_questions)
        val tvSubmitTime: TextView = view.findViewById(R.id.tv_submit_time)
       // val scoreProgressBar: ProgressBar = view.findViewById(R.id.score_progress_bar)
        val tvScorePercentage: TextView = view.findViewById(R.id.tv_score_percentage)
        val btnClose: ImageButton = view.findViewById(R.id.btn_close_dialog)
        
        tvExamName.text = examName
        tvScore.text = String.format("%.1f", score)
        
        val percentage = if (totalQuestions > 0) {
            ((correctAnswers.toDouble() / totalQuestions.toDouble()) * 100).toInt()
        } else {
            score.toInt()
        }
        
    //    scoreProgressBar.progress = percentage
      //  tvScorePercentage.text = "$percentage%"
        tvScoreLabel.text = when {
            score >= 90 -> "Luar Biasa! 🏆"
            score >= 80 -> "Sangat Baik! 🌟"
            score >= 70 -> "Bagus! 👍"
            score >= 60 -> "Cukup! 👌"
            else -> "Yuk, Coba Lagi! 💪" // Gunakan salah satu kalimat di atas
        }
        
        tvScoreLabel.setTextColor(when {
            score >= 80 -> Color.parseColor("#10B981")
            score >= 60 -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#EF4444")
        })
        
        tvCorrectAnswers.text = "$correctAnswers"
        tvTotalQuestions.text = "$totalQuestions"
        
        if (timestamp > 0) {
            val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))
            tvSubmitTime.text = sdf.format(Date(timestamp))
        }
        
        btnClose.setOnClickListener { 
            dismiss()
            if (finishOnClose) {
                activity?.finish()
            }
        }
        
        return MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(false) // Mencegah klik di luar dialog
            .create()
    }
}
