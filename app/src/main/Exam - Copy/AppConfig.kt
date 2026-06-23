package com.edukreasi.Exam

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Konfigurasi Aplikasi & Mesin Keamanan Terpusat
 */
object AppConfig {

    // ==================== SECURITY SETTINGS ====================

    /**
     * Enable/Disable Emulator Detection
     */
    const val ENABLE_EMULATOR_DETECTION = true
    const val BLOCK_ON_EMULATOR = true

    /**
     * Enable Anti-Screenshot
     */
    const val ENABLE_ANTI_SCREENSHOT = true

    /**
     * Enable Kiosk Mode (Pinning Layar)
     */
    const val ENABLE_KIOSK_MODE = true

    /**
     * Enable/Disable Deteksi Mode Pengembang & USB Debugging
     * SET KE FALSE SAAT ANDA SEDANG TESTING/DEVELOPMENT
     * SET KE TRUE SAAT RILIS KE SISWA
     */
    const val ENABLE_DEVELOPER_MODE_DETECTION = false

    // ==================== DEBUG SETTINGS ====================

    const val ENABLE_DEBUG_LOG = false
    const val FORCE_OFFLINE_MODE = false
    const val SKIP_TOKEN_VALIDATION = false

    // ==================== HELPER FUNCTIONS ====================

    fun isEmulatorDetectionEnabled(): Boolean = ENABLE_EMULATOR_DETECTION && BLOCK_ON_EMULATOR
    fun isSecureMode(): Boolean = ENABLE_ANTI_SCREENSHOT && ENABLE_KIOSK_MODE
    fun isDevelopmentMode(): Boolean = ENABLE_DEBUG_LOG || FORCE_OFFLINE_MODE || SKIP_TOKEN_VALIDATION

    fun logDebug(tag: String, message: String) {
        if (ENABLE_DEBUG_LOG) {
            android.util.Log.d(tag, message)
        }
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        if (ENABLE_DEBUG_LOG || true) {
            if (throwable != null) {
                android.util.Log.e(tag, message, throwable)
            } else {
                android.util.Log.e(tag, message)
            }
        }
    }

    // ==================== MESIN DETEKSI KEAMANAN ====================

    /**
     * Mengecek seluruh keamanan perangkat (Emulator, Root, Developer Mode)
     * Mengembalikan Pair: Boolean (isSecure) dan String (Pesan Error)
     */
    fun isDeviceSecure(context: Context): Pair<Boolean, String> {
        // 1. Cek Emulator
        if (ENABLE_EMULATOR_DETECTION && isEmulator()) {
            return Pair(false, "Emulator terdeteksi!\nAplikasi ujian tidak dapat dijalankan di Emulator (Bluestacks, Nox, dll).")
        }

        // 2. Cek Developer Options & USB Debugging (Bisa dimatikan saat testing)
        if (ENABLE_DEVELOPER_MODE_DETECTION) {
            if (isDeveloperModeEnabled(context)) {
                return Pair(false, "Mode Pengembang (Developer Options) Aktif!\nHarap matikan fitur ini di Pengaturan HP Anda terlebih dahulu.")
            }
            if (isAdbEnabled(context)) {
                return Pair(false, "USB Debugging Aktif!\nHarap matikan fitur ini untuk mencegah kecurangan.")
            }
        }

        // 3. Cek Root (Selalu aktif, bahaya jika dimatikan)
        if (isDeviceRooted()) {
            return Pair(false, "Perangkat Root Terdeteksi!\nAplikasi dilarang keras dijalankan pada perangkat yang telah dimodifikasi (Root).")
        }

        return Pair(true, "Aman")
    }

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || "google_sdk" == Build.PRODUCT)
    }

    private fun isDeveloperModeEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) != 0
    }

    private fun isAdbEnabled(context: Context): Boolean {
        return Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.ADB_ENABLED, 0
        ) != 0
    }

    private fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        for (path in paths) {
            if (File(path).exists()) return true
        }

        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }
}