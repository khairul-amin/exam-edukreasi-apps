package com.edukreasi.Exam

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.edukreasi.Exam.databinding.ActivitySplashScreenBinding
import kotlin.system.exitProcess
import android.os.Build
import android.view.WindowManager
import android.app.ActivityManager
import android.content.Context

class SplashScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashScreenBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. BLOKIR OVERLAY & SCREENSHOT (Wajib dipanggil sebelum setContentView)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Sembunyikan Bubble Messenger/Overlay untuk Android 12 ke atas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        // 2. MATIKAN PAKSA APLIKASI MESSENGER DI LATAR BELAKANG
        killSuspiciousApps()

        binding = ActivitySplashScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CacheManager.init(this)

        if (AppConfig.isDevelopmentMode()) {
            AppConfig.logDebug("SplashScreen", "⚠️ DEVELOPMENT MODE AKTIF")
        }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.versionText.text = "Versi ${pInfo.versionName}"
        } catch (e: Exception) {
            binding.versionText.text = "Versi -"
        }

        // Animasi Splash Screen
        binding.logo.alpha = 0f
        binding.logo.scaleX = 0.5f
        binding.logo.scaleY = 0.5f
        binding.text.alpha = 0f
        binding.text.translationY = 50f
        binding.subtitle.alpha = 0f
        binding.subtitle.translationY = 50f

        binding.logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(1000).setInterpolator(OvershootInterpolator(1.2f)).start()

        binding.text.animate().alpha(1f).translationY(0f)
            .setDuration(800).setStartDelay(300).start()

        binding.subtitle.animate().alpha(1f).translationY(0f)
            .setDuration(800).setStartDelay(500).start()

        // PENTING: Cek Keamanan Perangkat sebelum melanjutkan
        Handler(Looper.getMainLooper()).postDelayed({
            checkDeviceSecurityAndProceed()
        }, 3000)
    }


    private fun killSuspiciousApps() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            // Daftar package aplikasi yang sering memunculkan bubble
            val suspiciousPackages = listOf(
                "com.facebook.orca", // FB Messenger
                "com.whatsapp",      // WhatsApp
                "com.whatsapp.w4b",  // WA Business
                "org.telegram.messenger", // Telegram
                "com.viber.voip",
                "com.skype.raider"
            )

            for (pkg in suspiciousPackages) {
                am.killBackgroundProcesses(pkg)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private fun checkDeviceSecurityAndProceed() {
        val securityStatus = AppConfig.isDeviceSecure(this)

        if (!securityStatus.first) {
            // HP tidak aman (Root/Emulator/Developer Mode aktif)
            AlertDialog.Builder(this)
                .setTitle("Pelanggaran Keamanan!")
                .setMessage(securityStatus.second)
                .setCancelable(false)
                .setPositiveButton("Tutup Aplikasi") { _, _ ->
                    finishAffinity()
                    exitProcess(0)
                }
                .show()
        } else {
            // HP Aman, lanjutkan ke aplikasi
            checkLoginStatus()
        }
    }

    private fun checkLoginStatus() {
        // Selalu arahkan ke WaitingScreenActivity apa pun status loginnya
        startActivity(Intent(this, WaitingScreenActivity::class.java))
        finish()
    }
}