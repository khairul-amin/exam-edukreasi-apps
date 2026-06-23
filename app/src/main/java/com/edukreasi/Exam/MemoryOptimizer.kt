package com.edukreasi.Exam

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Utility untuk optimasi memory pada HP jadul/low-end
 * Membantu mengurangi memory leak dan garbage collection
 */
class MemoryOptimizer(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val tag = "MemoryOptimizer"

    companion object {
        private const val LOW_MEMORY_THRESHOLD_MB = 50  // Threshold untuk low memory
        private var instance: MemoryOptimizer? = null

        fun getInstance(context: Context): MemoryOptimizer {
            if (instance == null) {
                instance = MemoryOptimizer(context.applicationContext)
            }
            return instance!!
        }
    }

    /**
     * Check apakah device termasuk low-end
     */
    fun isLowEndDevice(): Boolean {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        // Device jadul biasanya punya RAM < 2GB
        val totalRamMB = memInfo.totalMem / (1024 * 1024)
        val isLowRam = totalRamMB < 2048
        
        Log.d(tag, "📱 Device RAM: ${totalRamMB}MB - Low-end: $isLowRam")
        return isLowRam
    }

    /**
     * Get available memory dalam MB
     */
    fun getAvailableMemoryMB(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /**
     * Check low memory state
     */
    fun isLowMemory(): Boolean {
        return getAvailableMemoryMB() < LOW_MEMORY_THRESHOLD_MB
    }

    /**
     * Register lifecycle observer untuk cleanup otomatis
     */
    fun observeLifecycle(owner: LifecycleOwner, webView: WebView? = null) {
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // Pause WebView ketika activity tidak visible
                    webView?.onPause()
                    // Cleanup resources
                    Runtime.getRuntime().gc()
                    Log.d(tag, "⏸️ Activity paused - Memory cleaned")
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView?.onResume()
                    Log.d(tag, "▶️ Activity resumed")
                }
                Lifecycle.Event.ON_DESTROY -> {
                    // Cleanup WebView sebelum destroy
                    webView?.destroy()
                    Log.d(tag, "🗑️ Activity destroyed - WebView cleaned")
                }
                else -> {}
            }
        })
    }

    /**
     * Cleanup memory agresif untuk low-end device
     */
    fun forceCleanup() {
        System.gc()
        System.runFinalization()
        Log.d(tag, "🧹 Forced memory cleanup")
    }

    /**
     * Log memory info untuk debugging
     */
    fun logMemoryInfo() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMB = memInfo.totalMem / (1024 * 1024)
        val availMB = memInfo.availMem / (1024 * 1024)
        val usedMB = totalMB - availMB
        val percentage = (usedMB * 100) / totalMB
        
        Log.d(tag, """
            📊 Memory Info:
            Total: ${totalMB}MB
            Used: ${usedMB}MB ($percentage%)
            Available: ${availMB}MB
            Low Memory: ${memInfo.lowMemory}
        """.trimIndent())
    }

    /**
     * Get device info
     */
    fun getDeviceInfo(): String {
        return """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            API Level: ${Build.VERSION.SDK_INT}
            RAM: ${getAvailableMemoryMB()}MB available
            Low-end: ${isLowEndDevice()}
        """.trimIndent()
    }
}

