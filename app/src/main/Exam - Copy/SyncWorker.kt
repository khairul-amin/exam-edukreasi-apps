package com.edukreasi.Exam

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // PENTING: Inisialisasi CacheManager sebelum digunakan di repositori
        try { CacheManager.init(applicationContext) } catch (e: Exception) { }
        
        val repository = SyncRepository(applicationContext)
        
        return try {
            Log.d("SyncWorker", "Memulai sinkronisasi hasil ujian...")
            val success = repository.syncAll()
            
            if (success) {
                Log.d("SyncWorker", "Sinkronisasi selesai dengan sukses.")
                Result.success()
            } else {
                Log.w("SyncWorker", "Beberapa data gagal dikirim, sistem akan menjadwalkan ulang.")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error fatal saat sinkronisasi: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ExamDataSync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        fun triggerNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val instantRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(instantRequest)
        }
    }
}
