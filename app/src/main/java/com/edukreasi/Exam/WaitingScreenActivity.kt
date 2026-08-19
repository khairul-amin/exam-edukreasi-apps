package com.edukreasi.Exam

import android.content.pm.ActivityInfo
import android.os.PowerManager
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.RelativeLayout
import android.widget.LinearLayout
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import androidx.appcompat.widget.Toolbar
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import androidx.core.view.GravityCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.nio.charset.StandardCharsets
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.UnknownHostException

class WaitingScreenActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var retryButton: Button
    private val csvDataFetcher by lazy { CsvDataFetcher(this) }
    private lateinit var exitButton: Button
    private lateinit var scanQrButton: Button
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var scannerContainer: RelativeLayout
    private lateinit var previewView: PreviewView
    private lateinit var cancelScanButton: Button
    private lateinit var torchButton: ImageButton
    private lateinit var flipCameraButton: ImageButton
    private lateinit var scannerLaser: View
    private lateinit var schoolNameTextView: TextView

    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingText: TextView

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isTorchOn = false
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    private val CAMERA_PERMISSION_CODE = 101
    private val WORKER_BASE_URL = "https://exam-edukreasi-api.edukreasi.workers.dev"

    // VARIABEL PENANDA DIALOG: Untuk mencegah Deteksi Aplikasi Mengambang
    var isIntentionalFocusLoss = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Agar layar tidak cepat mati di layar tunggu (Optional)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

// 1. BLOKIR OVERLAY & SCREENSHOT (Wajib di setiap Activity)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

// 2. Sembunyikan Bubble Messenger/Overlay di Activity INI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
        setupDisplayEnvironment()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_waiting_screen)

        toolbar = findViewById(R.id.toolbar)
        drawerLayout = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        startButton = findViewById(R.id.start_button)
        statusTextView = findViewById(R.id.status_text_view)
        retryButton = findViewById(R.id.retry_button)
        exitButton = findViewById(R.id.exit_button)
        scanQrButton = findViewById(R.id.scan_qr_button)
        scannerContainer = findViewById(R.id.scanner_container)
        previewView = findViewById(R.id.preview_view)
        cancelScanButton = findViewById(R.id.cancel_scan_button)
        torchButton = findViewById(R.id.torch_button)
        flipCameraButton = findViewById(R.id.flip_camera_button)
        scannerLaser = findViewById(R.id.scanner_laser)
        schoolNameTextView = findViewById(R.id.tv_school_name)
        loadingOverlay = findViewById(R.id.loading_overlay)
        loadingText = findViewById(R.id.loading_text)

        if (AppConfig.ENABLE_EMULATOR_DETECTION && isEmulator()) {
            if (AppConfig.BLOCK_ON_EMULATOR) {
                showEmulatorAlert()
                return
            } else {
                AppConfig.logDebug("WaitingScreenActivity", "Emulator terdeteksi tapi tidak diblokir (debug mode)")
            }
        }

        setSupportActionBar(toolbar)

        val toggle = object : ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close) {}
        toggle.drawerArrowDrawable.color = ContextCompat.getColor(this, android.R.color.white)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_fitur -> showFiturDialog()
                R.id.nav_about -> showAboutDialog()
                R.id.nav_update -> openUpdatePage()
                R.id.nav_reset_qr -> showResetQrConfirmationDialog()
                R.id.nav_clear_all -> showClearAllDataConfirmation()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        scanQrButton.setOnClickListener { checkCameraPermissionAndScan() }
        cancelScanButton.setOnClickListener { stopScanner() }
        torchButton.setOnClickListener { toggleTorch() }
        flipCameraButton.setOnClickListener { flipCamera() }

        statusTextView.setOnLongClickListener {
            if (scanQrButton.visibility == View.VISIBLE) {
                showManualActivationDialog()
                true
            } else false
        }

        exitButton.setOnClickListener {
            try { stopLockTask() } catch (e: Exception) {}
            finishAffinity()
            System.exit(0)
        }

        hideNavigationBarOnly()
        updateUIState()
        checkForUpdates()
        preFetchData()
    }

    // =======================================================================
    // DETEKSI APLIKASI MENGAMBANG & HELPER DIALOG
    // =======================================================================
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // =========================================================================
        // PERBAIKAN 2: Pastikan Layar hidup sebelum menuduh ada aplikasi mengambang
        // =========================================================================
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

        // Jika layar kehilangan fokus, BUKAN karena dialog kita, DAN layar masih menyala
        if (!hasFocus && !isIntentionalFocusLoss && isScreenOn) {
            isIntentionalFocusLoss = true // Supaya dialog peringatan tidak nge-loop

            AlertDialog.Builder(this)
                .setTitle("Peringatan Sistem")
                .setMessage("Terdeteksi Aplikasi Mengambang (Chat Heads, dsb) atau Layar Terbelah!\n\nHarap tutup semua aplikasi lain dan bersihkan notifikasi sebelum mengikuti ujian.")
                .setCancelable(false)
                .setPositiveButton("Mengerti") { dialog, _ ->
                    isIntentionalFocusLoss = false // Kembalikan status
                    dialog.dismiss()
                }
                .show()
        }
    }

    // Fungsi pembantu agar tidak capek menulis isIntentionalFocusLoss berulang kali
    private fun showSafeDialog(builder: AlertDialog.Builder) {
        isIntentionalFocusLoss = true
        val dialog = builder.create()
        dialog.setOnDismissListener { isIntentionalFocusLoss = false }
        dialog.show()
    }
    // =======================================================================


    private fun showLoading(msg: String) {
        loadingText.text = msg
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    private fun setupDisplayEnvironment() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
    }

    private fun hideNavigationBarOnly() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.show(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showManualActivationDialog() {
        val inputCode = EditText(this).apply {
            hint = "Masukkan Kode Aktivasi"
            setPadding(48, 48, 48, 48)
            inputType = InputType.TYPE_CLASS_TEXT
            gravity = Gravity.CENTER
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Aktivasi Manual")
            .setMessage("Gunakan fitur ini jika kamera bermasalah.")
            .setView(inputCode)
            .setPositiveButton("Aktifkan") { _, _ ->
                val code = inputCode.text.toString().trim()
                if (code.isNotEmpty()) processQrValue(code)
                else Toast.makeText(this, "Kode tidak boleh kosong", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)

        showSafeDialog(builder)
    }

    private fun isEmulator(): Boolean {
        val isEmu = Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") || Build.HARDWARE.contains("goldfish") ||
                Build.HARDWARE.contains("ranchu") || File("/system/bin/ttVM-prop").exists()
        if (isEmu) {
            AppConfig.logDebug("EmulatorCheck", "Emulator detected: ${Build.FINGERPRINT} / ${Build.MODEL}")
        }
        return isEmu
    }

    private fun showEmulatorAlert() {
        // Emulator alert juga butuh perlindungan agar tidak memicu deteksi overlay
        val builder = AlertDialog.Builder(this).setTitle("Keamanan").setMessage("Emulator tidak diizinkan.").setCancelable(false)
            .setPositiveButton("Keluar") { _, _ -> finishAffinity(); System.exit(0) }
        showSafeDialog(builder)
    }

    private fun preFetchData() {
        val pubUrl = csvDataFetcher.getPubUrl()
        if (pubUrl.isNullOrEmpty() || csvDataFetcher.isOfflineMode()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                csvDataFetcher.ensureGidsAvailable()
                csvDataFetcher.refreshCacheIfNeeded()
                val response: TokenResponse? = csvDataFetcher.getToken()
                if (response != null) {
                    csvDataFetcher.saveSchoolName(response.sekolah)
                    withContext(Dispatchers.Main) { schoolNameTextView.text = "di ${response.sekolah}" }
                }
            } catch (e: Exception) { Log.e("PreFetch", "Gagal fetch", e) }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("https://edukreasi.vercel.app/update.json").build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        if (bodyStr.isNotEmpty()) {
                            val json = JSONObject(bodyStr)
                            val latest = json.getInt("latestVersionCode")
                            val pInfo = packageManager.getPackageInfo(packageName, 0)
                            val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode.toInt() else pInfo.versionCode
                            if (latest > current) {
                                withContext(Dispatchers.Main) { showUpdateDialog(json.getString("downloadUrl")) }
                            }
                        }
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun showUpdateDialog(url: String) {
        val builder = AlertDialog.Builder(this).setTitle("Update").setMessage("Perbarui aplikasi?").setCancelable(false)
            .setPositiveButton("Update") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        showSafeDialog(builder)
    }

    override fun onResume() {
        super.onResume()
        updateUIState()

        // Anti-Overlay: Sembunyikan floating windows (Messenger Bubble, WeTV, dll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }
    }

    override fun onDestroy() { super.onDestroy() ; if (::cameraExecutor.isInitialized) cameraExecutor.shutdown() }

    private fun updateUIState() {
        val offlineUrl = csvDataFetcher.getOfflineUrl()
        val pubUrl = csvDataFetcher.getPubUrl()

        val isSemiOffline = csvDataFetcher.isOfflineMode() && offlineUrl.isNullOrEmpty()

        val hasData = if (isSemiOffline) {
            true
        } else if (csvDataFetcher.isOfflineMode()) {
            !offlineUrl.isNullOrEmpty()
        } else {
            !pubUrl.isNullOrEmpty()
        }

        if (hasData) {
            schoolNameTextView.text = "di ${csvDataFetcher.getSchoolName()}"
            schoolNameTextView.visibility = View.VISIBLE
            if (checkInternetConnection() || csvDataFetcher.isOfflineMode()) {
                scannerContainer.visibility = View.GONE; scanQrButton.visibility = View.GONE
                retryButton.visibility = View.GONE; statusTextView.visibility = View.GONE
                startButton.visibility = View.VISIBLE; startButton.text = "Cek Aplikasi"
                startButton.setOnClickListener { checkScreenPinning() }
            } else {
                statusTextView.visibility = View.VISIBLE; statusTextView.text = "Internet tidak tersedia"
                retryButton.visibility = View.VISIBLE; startButton.visibility = View.GONE
                retryButton.setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
            }
        } else {
            schoolNameTextView.visibility = View.GONE; scanQrButton.visibility = View.VISIBLE
            startButton.visibility = View.GONE; retryButton.visibility = View.GONE
            statusTextView.visibility = View.VISIBLE; statusTextView.text = "Aplikasi perlu diaktivasi."
        }
    }

    private fun checkScreenPinning() {
        try {
            startLockTask()
            startButton.text = "Mulai Ujian"
            exitButton.visibility = View.VISIBLE
            startButton.setOnClickListener {
                if (csvDataFetcher.isOfflineMode()) handleOfflineLoginFlow()
                else showTokenDialog()
            }
        } catch (e: Exception) { Toast.makeText(this, "Gagal mengunci.", Toast.LENGTH_SHORT).show() }
    }

    private fun handleOfflineLoginFlow() {
        val mode = csvDataFetcher.getAppMode()
        if (mode == "on-lan") {
            startActivity(Intent(this, WebViewActivity::class.java))
            return
        }

        val currentStudent = CacheManager.getStudentInfo()
        if (currentStudent != null) startActivity(Intent(this@WaitingScreenActivity, OfflineDashboardActivity::class.java))
        else showNisnLoginDialog()
    }

    private fun showNisnLoginDialog() {
        val inputNisn = EditText(this).apply {
            background = ContextCompat.getDrawable(this@WaitingScreenActivity, R.drawable.rounded_edittext)
            setPadding(16, 16, 16, 16)
            setSingleLine(true)
            gravity = Gravity.CENTER
            hint = "Masukkan NISN Anda"
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = RelativeLayout(this).apply {
            val padding = 32
            setPadding(padding, padding, padding, padding)
            addView(inputNisn)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Login Peserta")
            .setMessage("Sekolah: ${csvDataFetcher.getSchoolName()}\nSilakan masukkan NISN untuk sinkronisasi data.")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val nisn = inputNisn.text.toString().trim()
                if (nisn.isNotBlank()) verifyNisnAndSync(nisn)
                else Toast.makeText(this, "NISN wajib diisi.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)

        showSafeDialog(builder)

        inputNisn.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputNisn, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun verifyNisnAndSync(nisn: String) {
        showLoading("Memverifikasi NISN...")
        lifecycleScope.launch {
            try {
                val loginResult = csvDataFetcher.loginStudent(nisn)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    if (loginResult != null && loginResult.optString("status") == "success") {
                        val student = loginResult.optJSONObject("student")
                        if (student != null) {
                            val studentName = student.getString("studentName")
                            val className = student.getString("className")
                            val schoolName = student.getString("schoolName")
                            val token = loginResult.optString("token", "")

                            csvDataFetcher.saveSchoolName(schoolName)
                            CacheManager.init(this@WaitingScreenActivity)
                            CacheManager.saveStudentInfo(nisn, studentName, className, schoolName)

                            if (token.isNotEmpty()) {
                                CacheManager.saveToken(token)
                                showSyncConfirmationDialog(SiswaInfo(studentName, className))
                            } else {
                                showErrorDialog("Gagal Aktivasi", "Token tidak tersedia. Pastikan sekolah sudah mengaktifkan ujian.")
                            }
                        }
                    } else {
                        val errorMsg = loginResult?.optString("message") ?: "NISN tidak terdaftar"
                        showErrorDialog("Gagal Login", errorMsg)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    handleNetworkError(e, "Gagal Verifikasi NISN")
                }
            }
        }
    }

    private fun handleNetworkError(e: Exception, title: String) {
        val message = if (e is UnknownHostException || e.message?.contains("Unable to resolve host") == true) {
            "Koneksi internet tidak ditemukan.\n\nPastikan HP terhubung ke Wi-Fi sekolah atau data seluler aktif untuk proses aktivasi awal ini."
        } else {
            "Terjadi kesalahan: ${e.message}"
        }
        showErrorDialog(title, message)
    }

    private fun showErrorDialog(title: String, message: String) {
        val builder = AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Coba Lagi", null)
        showSafeDialog(builder)
    }

    private fun showSyncConfirmationDialog(siswa: SiswaInfo) {
        val builder = AlertDialog.Builder(this).setTitle("Data Ditemukan").setMessage("Nama: ${siswa.nama}\nKelas: ${siswa.kelas}\n\nKlik 'Sinkron' untuk mengunduh jadwal.")
            .setCancelable(false).setPositiveButton("Sinkron") { _, _ -> performSyncData() }
            .setNegativeButton("Batal") { _, _ -> CacheManager.init(this@WaitingScreenActivity); CacheManager.clearAllCache() }
        showSafeDialog(builder)
    }

    private fun performSyncData() {
        showLoading("Mengunduh Jadwal & Soal...")
        lifecycleScope.launch {
            try {
                val subjectsResult = csvDataFetcher.fetchSubjects()
                if (subjectsResult != null && subjectsResult.optString("status") == "success") {
                    val subjects = subjectsResult.optJSONArray("subjects")
                    if (subjects != null) {
                        CacheManager.init(this@WaitingScreenActivity)
                        CacheManager.saveSubjects(subjects)
                        hideLoading()
                        Toast.makeText(this@WaitingScreenActivity, "Berhasil mengunduh ${subjects.length()} mata pelajaran.", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@WaitingScreenActivity, OfflineDashboardActivity::class.java))
                    }
                } else {
                    val errorMsg = subjectsResult?.optString("message") ?: "Gagal sinkron."
                    hideLoading()
                    showErrorDialog("Gagal Sinkron", errorMsg)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    handleNetworkError(e, "Gagal Sinkron")
                }
            }
        }
    }

    private fun startExam() { startActivity(Intent(this, WebViewActivity::class.java)) }

    private fun checkCameraPermissionAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startScanner()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startScanner()
    }

    private fun startScanner() {
        scannerContainer.visibility = View.VISIBLE
        val scannerAnimation = AnimationUtils.loadAnimation(this, R.anim.scanner_animation)
        scannerLaser.startAnimation(scannerAnimation)
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                bindCameraUseCases(cameraProviderFuture.get())
            } catch (e: Exception) {
                Log.e("Scanner", "Failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val imageAnalysis = ImageAnalysis.Builder().build().also {
            it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { qrValue ->
                runOnUiThread { stopScanner(); processQrValue(qrValue) }
            })
        }
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
    }

    private fun stopScanner() {
        scannerContainer.visibility = View.GONE
        if (::cameraProviderFuture.isInitialized) {
            try { cameraProviderFuture.get().unbindAll() } catch (e: Exception) {}
        }
    }

    private fun toggleTorch() { if (camera?.cameraInfo?.hasFlashUnit() == true) { isTorchOn = !isTorchOn; camera?.cameraControl?.enableTorch(isTorchOn) } }
    private fun flipCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        if (::cameraProviderFuture.isInitialized) {
            try { bindCameraUseCases(cameraProviderFuture.get()) } catch (e: Exception) {}
        }
    }

    private fun processQrValue(qrValue: String) {
        showLoading("Mengaktivasi...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val trimmed = qrValue.trim()
                if (trimmed.startsWith("EDUKREASI-ONLAN|")) {
                    val decoded = QrSecurityUtils.decodeEdukreasiQr(trimmed)
                    if (decoded != null) {
                        val url = decoded.optString("url", "")
                        csvDataFetcher.setAppMode("on-lan")
                        csvDataFetcher.saveOfflineUrl(url)
                        withContext(Dispatchers.Main) { hideLoading(); updateUIState() }
                        return@launch
                    }
                } else if (trimmed.startsWith("EDUKREASI-OFFLINE|")) {
                    val decoded = QrSecurityUtils.decodeEdukreasiQr(trimmed)
                    if (decoded != null) {
                        val npsn = decoded.optString("npsn", "")
                        val school = decoded.optString("school", "Sekolah")

                        csvDataFetcher.setSemiOfflineData(npsn, school)
                        fetchSemiOfflineTokens()
                        return@launch
                    }
                } else {
                    performOnlineActivation(trimmed)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    handleNetworkError(e, "Aktivasi Gagal")
                }
            }
        }
    }

    private fun performOnlineActivation(hash: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("hash", hash) }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("https://esmkveggutxzklavspnn.supabase.co/functions/v1/activate-qr")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY).post(body).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val json = JSONObject(bodyStr)
                        csvDataFetcher.setAppMode("online")
                        csvDataFetcher.savePubUrl(json.getString("link"))
                        csvDataFetcher.saveNpsn(json.getString("npsn"))

                        // Optimasi: Berikan respon sukses instan ke user tanpa menunggu fetch data berat
                        withContext(Dispatchers.Main) { 
                            hideLoading()
                            updateUIState() 
                        }

                        // Lakukan sinkronisasi token dan caching CSV di latar belakang
                        preFetchData()

                    } else if (response.code == 403) {
                        // ✅ TANGKAP ERROR 403 (TRIAL HABIS / LISENSI NONAKTIF)
                        val errorBody = response.body?.string() ?: ""
                        var pesanPeringatan = "Akses Ditolak."

                        try {
                            val jsonError = JSONObject(errorBody)
                            if (jsonError.has("message")) {
                                pesanPeringatan = jsonError.getString("message")
                            }
                        } catch (e: Exception) {
                            Log.e("ActivationError", "Gagal parse JSON error", e)
                        }

                        withContext(Dispatchers.Main) {
                            hideLoading()
                            // Gunakan showErrorDialog yang sudah ada di Activity Anda
                            showErrorDialog("Lisensi Terbatas", pesanPeringatan)
                        }

                    } else {
                        // ERROR LAIN (404, 500, dsb)
                        withContext(Dispatchers.Main) {
                            hideLoading()
                            showErrorDialog("QR Tidak Valid", "Silakan gunakan QR Code resmi dari sekolah.")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { hideLoading(); handleNetworkError(e, "Aktivasi Gagal") }
            }
        }
    }

    private fun showTokenDialog() {
        val inputToken = EditText(this).apply {
            background = ContextCompat.getDrawable(this@WaitingScreenActivity, R.drawable.rounded_edittext)
            setPadding(16, 16, 16, 16)
            setSingleLine(true)
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val container = RelativeLayout(this).apply {
            val padding = 32
            setPadding(padding, padding, padding, padding)
            addView(inputToken)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Masukkan Token Ujian")
            .setMessage("Sekolah: ${csvDataFetcher.getSchoolName()}")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val token = inputToken.text.toString()
                if (token.isNotBlank()) validateToken(token) else Toast.makeText(this, "Token wajib diisi.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)

        showSafeDialog(builder)

        inputToken.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(inputToken, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun validateToken(inputToken: String) {
        showLoading("Validasi token...")
        lifecycleScope.launch {
            try {
                val trimmedInput = inputToken.trim()
                val appMode = csvDataFetcher.getAppMode()

                if (appMode == "semi-offline") {
                    validateSemiOfflineToken(trimmedInput)
                } else {
                    val response = withContext<TokenResponse?>(Dispatchers.IO) {
                        csvDataFetcher.getToken()
                     }
                    hideLoading()
                    if (response != null && trimmedInput.equals(response.token.trim(), true)) {
                        startExam()
                    } else {
                        showErrorDialog("Token Salah", "Token yang Anda masukkan tidak sesuai.")
                    }
                }
            } catch (e: Exception) {
                hideLoading()
                handleNetworkError(e, "Validasi Gagal")
            }
        }
    }

    private suspend fun validateSemiOfflineToken(token: String) {
        val usedTokens = csvDataFetcher.getUsedTokens()

        // 1. CEK APAKAH TOKEN SUDAH PERNAH DIPAKAI DI HP INI
        if (usedTokens.contains(token)) {
            hideLoading()
            showErrorDialog("Token Kedaluwarsa", "Token ini sudah pernah digunakan dan tidak dapat digunakan kembali.")
            return
        }

        val availableTokens = csvDataFetcher.getAvailableTokens()
        val authToken = csvDataFetcher.getToken()?.token ?: ""

        try {
            // 2. VALIDASI KE SERVER WORKER (Jika Ada Internet)
            val body = JSONObject().apply { put("token", token) }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$WORKER_BASE_URL/api/student/exam-tokens/validate")
                .header("Authorization", "Bearer $authToken")
                .post(body)
                .build()

            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        if (response.isSuccessful) {
                            csvDataFetcher.markTokenAsUsed(token)
                            startExam()
                        } else {
                            showErrorDialog("Token Tidak Valid", "Token tidak valid atau sudah digunakan.")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 3. FALLBACK: VALIDASI OFFLINE (Jika Internet Mati)
            withContext(Dispatchers.Main) {
                if (availableTokens.contains(token)) {
                    csvDataFetcher.markTokenAsUsed(token)
                    startExam()
                } else {
                    hideLoading()
                    handleNetworkError(e, "Validasi Gagal")
                }
            }
        }
    }

    private fun fetchSemiOfflineTokens() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val authToken = csvDataFetcher.getToken()?.token ?: ""

                val request = Request.Builder()
                    .url("$WORKER_BASE_URL/api/student/exam-tokens")
                    .header("Authorization", "Bearer $authToken")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val json = JSONObject(bodyStr)
                        val tokensArray = json.optJSONArray("tokens") ?: json.optJSONArray("data")

                        if (tokensArray != null) {
                            val tokenList = mutableListOf<String>()
                            for (i in 0 until tokensArray.length()) {
                                val tokenObj = tokensArray.optJSONObject(i)
                                if (tokenObj != null) {
                                    tokenList.add(tokenObj.getString("token"))
                                } else {
                                    tokenList.add(tokensArray.getString(i))
                                }
                            }
                            csvDataFetcher.saveTokenList(tokenList)
                        }

                        withContext(Dispatchers.Main) {
                            updateUIState()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            updateUIState()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateUIState()
                }
            }
        }
    }

    private fun showResetQrConfirmationDialog() {
        val builder = AlertDialog.Builder(this).setTitle("Reset").setMessage("Yakin reset?")
            .setPositiveButton("Ya") { _, _ ->
                csvDataFetcher.clearActivationData()
                updateUIState()
            }
            .setNegativeButton("Batal", null)
        showSafeDialog(builder)
    }

    private fun showClearAllDataConfirmation() {
        val builder = AlertDialog.Builder(this)
            .setTitle("Bersihkan Semua Data")
            .setMessage("Ini akan menghapus:\n- Cache soal\n- Data login\n- Token\n- Semua jawaban yang belum dikirim\n\nLanjutkan?")
            .setPositiveButton("Hapus Semua") { _, _ ->
                CacheManager.init(this@WaitingScreenActivity)
                CacheManager.clearAllCache()
                csvDataFetcher.clearActivationData()
                Toast.makeText(this, "Semua data berhasil dihapus", Toast.LENGTH_SHORT).show()
                updateUIState()
            }
            .setNegativeButton("Batal", null)
        showSafeDialog(builder)
    }

    private fun checkInternetConnection(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(n) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun showFiturDialog() {
        val builder = AlertDialog.Builder(this).setTitle("Fitur").setMessage("Anti-Emulator, Kiosk Mode, Anti-Screenshot.")
        showSafeDialog(builder)
    }

    private fun showAboutDialog() {
        val name = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "-" }
        val builder = AlertDialog.Builder(this).setTitle("Tentang").setMessage("Versi $name\n© 2025 Edukreasi")
        showSafeDialog(builder)
    }

    private fun openUpdatePage() { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://edukreasi.vercel.app/#download"))) }
}

private class QrCodeAnalyzer(private val onQrCodeScanned: (String) -> Unit) : ImageAnalysis.Analyzer {
    private var isScanning = true
    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: androidx.camera.core.ImageProxy) {
        if (!isScanning) { imageProxy.close(); return }
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            BarcodeScanning.getClient().process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        isScanning = false
                        barcodes[0].rawValue?.let(onQrCodeScanned)
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else imageProxy.close()
    }
}