package com.edukreasi.Exam

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import kotlin.system.exitProcess
import android.content.pm.ActivityInfo
import androidx.activity.OnBackPressedCallback
import android.os.PowerManager

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingLayout: LinearLayout
    private lateinit var timeTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var batteryIcon: ImageView
    private lateinit var logoutButton: ImageButton
    private lateinit var reloadButton: ImageButton

    private val csvDataFetcher by lazy { CsvDataFetcher(this) }
    private lateinit var firebaseAuth: FirebaseAuth
    private lateinit var signInClient: GoogleSignInClient

    private val handler = Handler(Looper.getMainLooper())
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshInterval = 2 * 60 * 1000L
    private lateinit var batteryStatusReceiver: BroadcastReceiver

    private val RC_SIGN_IN = 9001
    private var isDashboardReady = false
    private var lastFetchedData: Pair<JSONObject, JSONArray>? = null

    // Variabel untuk mengelola reload
    private var isReloading = false
    private var hasPendingReload = false

    // Status Submit
    private var isExamSubmitted = false
    // Variabel pembantu url tertunda jika diperlukan
    private var pendingUrl: String? = null
    private var isExamActive = true
    // Gunakan ini jika Anda mau memunculkan AlertDialog bawaan aplikasi
    // agar tidak dianggap pelanggaran (contoh: dialog konfirmasi submit)
    private var isShowingAppDialog = false
    private var isKeyboardVisible = false
    private val focusCheckHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { CacheManager.init(this) } catch (e: Exception) { }

        setupDisplayEnvironment()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // PERBAIKAN 1: Tambahkan FLAG_KEEP_SCREEN_ON agar layar tidak mati sendiri saat ujian
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (AppConfig.ENABLE_ANTI_SCREENSHOT) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        // Pastikan ini ada di onCreate WebViewActivity Anda
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // INI YANG MEMBUNUH BUBBLE SAAT BERADA DI WEBVIEW
            window.setHideOverlayWindows(true)
        }
        setContentView(R.layout.activity_webview)

        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {

            val rect = android.graphics.Rect()
            window.decorView.getWindowVisibleDisplayFrame(rect)

            val screenHeight = window.decorView.rootView.height
            val keypadHeight = screenHeight - rect.bottom

            // keyboard dianggap muncul jika memakai >15% layar
            isKeyboardVisible = keypadHeight > screenHeight * 0.15
        }

        webView = findViewById(R.id.web_view)
        progressBar = findViewById(R.id.progress_bar)
        loadingLayout = findViewById(R.id.loading_layout)
        timeTextView = findViewById(R.id.time_text)
        batteryTextView = findViewById(R.id.battery_text)
        batteryIcon = findViewById(R.id.battery)
        logoutButton = findViewById(R.id.logout_button)
        reloadButton = findViewById(R.id.reload_button)

        firebaseAuth = FirebaseAuth.getInstance()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("38256340987-ldve0qg1r7vsv70l91bs0uop64p9nqdc.apps.googleusercontent.com")
            .requestEmail()
            .build()
        signInClient = GoogleSignIn.getClient(this, gso)

        logoutButton.setOnClickListener { showLogoutDialog() }
        reloadButton.setOnClickListener { reloadPage() }

        setupWebView()
        setupBatteryReceiver()
        startRealTimeUpdates()
        hideNavigationBarOnly()
        setupBackButton()

        val isOfflineExam = intent.getBooleanExtra("IS_OFFLINE_EXAM", false)
        val appMode = csvDataFetcher.getAppMode()

        if (isOfflineExam || appMode == "semi-offline") {
            logoutButton.visibility = View.VISIBLE
            loadLocalTemplate()
        } else {
            lifecycleScope.launch {
                if (appMode == "on-lan" || appMode == "offline") {
                    val offlineUrl = csvDataFetcher.getOfflineUrl()
                    if (!offlineUrl.isNullOrEmpty()) {
                        logoutButton.visibility = View.VISIBLE
                        webView.loadUrl(offlineUrl)
                        delay(500)
                        loadingLayout.visibility = View.GONE
                    } else {
                        Toast.makeText(this@WebViewActivity, "Link Server tidak ditemukan!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    webView.loadUrl("file:///android_asset/index.html")
                    val account = GoogleSignIn.getLastSignedInAccount(this@WebViewActivity)
                    if (account != null) handlePostLogin(account.email ?: "")
                    else triggerAccountPicker()
                }
            }
        }
    }

    private fun getActiveUserEmail(): String? {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        return account?.email ?: CacheManager.getStudentInfo()?.optString("email")
    }

    private fun handleStudentExitEvent(reason: String) {
        val examId = intent.getStringExtra("EXAM_ID") ?: return
        CacheManager.setSessionLocked(examId, true)
        val activeToken = CacheManager.getActiveToken(examId)
        if (activeToken != null) {
            CacheManager.markProctorTokenAsUsed(activeToken)
            CacheManager.saveExamAttempt(examId, activeToken, reason)
        }
    }

    private fun loadLocalTemplate() {
        try {
            val html = assets.open("exam_template.html").bufferedReader().use { it.readText() }
            webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            webView.loadUrl("file:///android_asset/exam_template.html")
        }
    }

    private fun setupDisplayEnvironment() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loadingLayout.visibility == View.VISIBLE) {
                    Toast.makeText(this@WebViewActivity, "Harap tunggu...", Toast.LENGTH_SHORT).show()
                    return
                }
                if (csvDataFetcher.isOfflineMode() || csvDataFetcher.getAppMode() == "semi-offline") {
                    Toast.makeText(this@WebViewActivity, "Tombol kembali dikunci selama ujian. Gunakan menu Logout.", Toast.LENGTH_SHORT).show()
                    return
                }
                val currentUrl = webView.url
                if (currentUrl != null && currentUrl.endsWith("index.html")) {
                    Toast.makeText(this@WebViewActivity, "Anda berada di halaman utama.", Toast.LENGTH_SHORT).show()
                } else {
                    if (webView.canGoBack()) webView.goBack()
                    else webView.loadUrl("file:///android_asset/index.html")
                }
            }
        })
    }

    private fun hideNavigationBarOnly() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.show(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Ketika layar mati, abaikan selama ujian aktif agar tidak dianggap pelanggaran
            if (isExamActive) {
                Log.d("ExamMonitor", "Screen off event ignored (exam active)")
            }
        }
    }

    private fun startRealTimeUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                val cal = Calendar.getInstance()
                timeTextView.text = String.format(Locale.getDefault(), "%02d:%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
                handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(batteryStatusReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Register screen‑off receiver – we ignore the event while the exam is active
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        if (AppConfig.ENABLE_ANTI_SCREENSHOT) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        if (AppConfig.ENABLE_KIOSK_MODE) {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            try {
                if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                    startLockTask()
                }
            } catch (e: Exception) { }
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(batteryStatusReceiver) } catch (e: Exception) { }
        // Unregister screen‑off receiver
        try { unregisterReceiver(screenOffReceiver) } catch (e: Exception) { }
    }

    @SuppressLint("SetJavaScriptEnabled")

    private fun setupWebView() {
    //    Log.e("TEST_APP", "setupWebView dijalankan")
        // === FITUR BARU: Konfigurasi Cookie & Sinkronisasi ===
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // === FITUR BARU: Modifikasi User Agent agar tidak terdeteksi sebagai WebView aman oleh Google ===
        val originalUA = settings.userAgentString
        settings.userAgentString = originalUA.replace("; wv", "")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
            }

            // --- PERBAIKAN: Cegat Alert bawaan JS agar tidak memicu pelanggaran ---
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                isShowingAppDialog = true // Beritahu sistem ini dialog resmi
                AlertDialog.Builder(this@WebViewActivity)
                    .setTitle("Peringatan")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ ->
                        result?.confirm()
                    }
                    .setCancelable(false) // Tidak bisa ditutup dengan klik luar
                    .setOnDismissListener { isShowingAppDialog = false } // Kembalikan status
                    .show()
                return true // Kita yang handle dialognya
            }

            // --- PERBAIKAN: Cegat Confirm bawaan JS agar tidak memicu pelanggaran ---
            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                isShowingAppDialog = true // Beritahu sistem ini dialog resmi
                AlertDialog.Builder(this@WebViewActivity)
                    .setTitle("Konfirmasi")
                    .setMessage(message)
                    .setPositiveButton("Ya") { _, _ ->
                        result?.confirm()
                    }
                    .setNegativeButton("Tidak") { _, _ ->
                        result?.cancel()
                    }
                    .setCancelable(false) // Tidak bisa ditutup dengan klik luar
                    .setOnDismissListener { isShowingAppDialog = false } // Kembalikan status
                    .show()
                return true // Kita yang handle dialognya
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                val url = request?.url.toString()
                //Log.e("UJIAN_DEBUG", "shouldOverrideUrlLoading HIT")
                //Log.e("UJIAN_DEBUG", "URL = $url")

                val email = getActiveUserEmail()
                //Log.e("UJIAN_DEBUG", "EMAIL = $email")

                // Blokir logout / ganti akun Google
                if (
                    url.contains("accounts.google.com/Logout") ||
                    url.contains("AddSession") ||
                    url.contains("SignOutOptions") ||
                    url.contains("SwitchAccount")
                ) {
                    Toast.makeText(
                        this@WebViewActivity,
                        "Aksi dilarang selama ujian!",
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }

                val currentWebViewUrl = view?.url

                // HANYA tambahkan login_hint saat klik Google Form dari dashboard
                if (url.contains("docs.google.com/forms")) {

                    if (email != null && !url.contains("login_hint=")) {

                        val separator = if (url.contains("?")) "&" else "?"
                        val finalUrl = "$url${separator}login_hint=$email"

                        Log.d("FORM_LOGIN", "EMAIL = $email")
                        Log.d("FORM_LOGIN", "URL = $url")
                        Log.d("FORM_LOGIN", "FINAL_URL = $finalUrl")

                        val headers = mutableMapOf<String, String>()
                        headers["X-Requested-With"] = ""

                        view?.loadUrl(finalUrl, headers)
                        return true
                    }
                }

                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                    //   Log.e("PAGE_DEBUG", "FINISH = $url")

                val email = getActiveUserEmail()

                if (email != null && url?.contains("accounts.google.com") == true) {

                    Handler(Looper.getMainLooper()).postDelayed({

                        webView.evaluateJavascript("""
                (function() {
                    try {

                        var input =
                            document.querySelector('input[type="email"]') ||
                            document.querySelector('input[name="identifier"]') ||
                            document.getElementById('identifierId');

                        if (!input) {
                            console.log("INPUT EMAIL TIDAK DITEMUKAN");
                            return;
                        }

                        input.focus();

                        input.value = "$email";

                        input.dispatchEvent(
                            new Event('input', { bubbles:true })
                        );

                        input.dispatchEvent(
                            new Event('change', { bubbles:true })
                        );

                        setTimeout(function() {

                            var btn =
                                document.getElementById('identifierNext') ||
                                document.querySelector('#identifierNext button') ||
                                document.querySelector('button[jsname]');

                            if(btn){
                                btn.click();
                            }

                        },1000);

                    } catch(e){
                        console.log(e);
                    }
                })();
            """.trimIndent(), null)

                    }, 2500)
                }

                if (url?.endsWith("index.html") == true) {
                    isDashboardReady = true
                    lastFetchedData?.let {
                        injectDataToWebView(it.first, it.second)
                    }
                } else {
                    loadingLayout.visibility = View.GONE
                }
            }
        }

        webView.addJavascriptInterface(WebAppInterface(this), "Android")
    }

    private fun triggerAccountPicker() {
        webView.evaluateJavascript("if(typeof cancelWatchdog === 'function') cancelWatchdog();", null)
        startActivityForResult(signInClient.signInIntent, RC_SIGN_IN)
    }


    private fun handlePostLogin(email: String) {
        lifecycleScope.launch {
            val currentUrl = webView.url
            if (currentUrl == null || !currentUrl.endsWith("index.html")) {
                isDashboardReady = false
                webView.loadUrl("file:///android_asset/index.html")
            }

            tampilkanListSoal(email, isManualReload = false)
            startAutoRefresh(email)
            pendingUrl?.let {
                // Tambahkan login_hint ke url tertunda jika itu link Google
                val finalUrl = if (it.contains("docs.google.com/forms") && !it.contains("login_hint=")) {
                    val sep = if (it.contains("?")) "&" else "?"
                    "$it${sep}login_hint=$email"
                } else it

                webView.loadUrl(finalUrl)
                pendingUrl = null
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account?.email != null) {
                    handlePostLogin(account.email!!)
                    CookieManager.getInstance().flush()
                } else {
                    webView.evaluateJavascript("if(typeof cancelWatchdog === 'function') cancelWatchdog();", null)
                    loadingLayout.visibility = View.GONE
                    webView.evaluateJavascript("showInfoMessage('Login Dibatalkan', 'Silakan klik tombol <b>Reload</b>.');", null)
                }
            } catch (e: ApiException) {
                webView.evaluateJavascript("if(typeof cancelWatchdog === 'function') cancelWatchdog();", null)
                loadingLayout.visibility = View.GONE
                webView.evaluateJavascript("showInfoMessage('Gagal Login', 'Terjadi kesalahan: ${e.message}');", null)
            }
        }
    }

    private fun tampilkanListSoal(email: String, isManualReload: Boolean = false, isAutoRefresh: Boolean = false) {
        if (!isAutoRefresh && !isDashboardReady) loadingLayout.visibility = View.VISIBLE
        if (!isAutoRefresh) webView.evaluateJavascript("if(typeof startDataWatchdog === 'function') startDataWatchdog();", null)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val info = csvDataFetcher.getSiswaInfo(email, forceRefresh = isManualReload)
                    val list = info?.let { csvDataFetcher.getAllLinksByKelas(it.kelas, forceRefresh = isManualReload) }
                    Pair(info, list)
                }

                var waitCount = 0
                while (!isDashboardReady && waitCount < 100) {
                    delay(50)
                    waitCount++
                }

                if (!isDashboardReady) {
                    if (!isAutoRefresh) {
                        withContext(Dispatchers.Main) {
                            loadingLayout.visibility = View.GONE
                            webView.evaluateJavascript("if(typeof cancelWatchdog === 'function') cancelWatchdog();", null)
                            webView.evaluateJavascript("document.body.innerHTML = '<div style=\"text-align:center; padding: 20px;\"><h2>Koneksi Terputus</h2><p>Tekan tombol 🔄 untuk memuat ulang.</p></div>';", null)
                        }
                    }
                    return@launch
                }

                if (result.first == null) {
                    if (!isAutoRefresh) {
                        withContext(Dispatchers.Main) {
                            loadingLayout.visibility = View.GONE
                            webView.evaluateJavascript("if(typeof cancelWatchdog === 'function') cancelWatchdog();", null)
                            webView.evaluateJavascript("showInfoMessage('Akun Tidak Terdaftar', 'Email: <b>$email</b> tidak ditemukan.');", null)
                        }
                    }
                    return@launch
                }

                val userInfo = JSONObject().apply {
                    put("name", result.first!!.nama)
                    put("email", email)
                    put("class", result.first!!.kelas)
                }

                val examArray = JSONArray()
                result.second?.forEach { itMap ->
                    val obj = JSONObject().apply {
                        put("mapel", itMap["mapel"] ?: "-")
                        put("link", itMap["link"] ?: "#")
                        put("status", itMap["status"] ?: "SEGERA")
                        put("kelas", itMap["kelas"] ?: "-")
                        put("jamMulai", itMap["jamMulai"] ?: "00:00")
                        put("jamSelesai", itMap["jamSelesai"] ?: "23:59")
                        val rawTgl = itMap["tanggalMulai"] ?: ""
                        put("tanggalMulai", rawTgl.replace("/", "-"))
                        put("tanggalSelesai", (itMap["tanggalSelesai"] ?: rawTgl).replace("/", "-"))
                    }
                    examArray.put(obj)
                }

                lastFetchedData = Pair(userInfo, examArray)

                withContext(Dispatchers.Main) {
                    injectDataToWebView(userInfo, examArray, isManualReload)
                }

            } catch (e: Exception) {
                if (!isAutoRefresh) {
                    withContext(Dispatchers.Main) {
                        loadingLayout.visibility = View.GONE
                        webView.evaluateJavascript("if(typeof cancelWatchdog === 'function') cancelWatchdog();", null)
                    }
                }
            }
        }
    }

    private fun injectDataToWebView(userInfo: JSONObject, examArray: JSONArray, isManualReload: Boolean = false) {
        webView.evaluateJavascript("updateExamList($userInfo, $examArray);", null)
        loadingLayout.visibility = View.GONE

        if (isManualReload) {
            Toast.makeText(this@WebViewActivity, "Sinkronisasi selesai.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAutoRefresh(email: String) {
        refreshHandler.postDelayed(object : Runnable {
            override fun run() {
                tampilkanListSoal(email)
                refreshHandler.postDelayed(this, refreshInterval)
            }
        }, refreshInterval)
    }

    private fun showLogoutDialog() {
        val appMode = csvDataFetcher.getAppMode()
        val isOfflineExam = intent.getBooleanExtra("IS_OFFLINE_EXAM", false) || appMode == "semi-offline"

        if (isOfflineExam) {
            showExitAppConfirmation(isOfflineExam = true)
        } else if (appMode == "on-lan") {
            showExitAppConfirmation(isOfflineExam = false)
        } else {
            val options = arrayOf("Keluar Aplikasi", "Ganti Akun Google")
            isShowingAppDialog = true // Tambahkan ini
            AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(options) { _, which ->
                    if (which == 0) showExitAppConfirmation(isOfflineExam = false) else showGoogleLogoutConfirmation()
                }
                .setNegativeButton("Batal", null)
                .setOnDismissListener { isShowingAppDialog = false } // Tambahkan ini
                .show()
        }
    }

    private fun showExitAppConfirmation(isOfflineExam: Boolean) {
        val message = if (isOfflineExam) {
            "Yakin ingin keluar?\n\nJika ujian sedang berlangsung, status ujian akan TERKUNCI."
        } else {
            "Yakin ingin keluar dari aplikasi?"
        }
        isShowingAppDialog = true // Tambahkan ini
        AlertDialog.Builder(this)
            .setTitle("Keluar Aplikasi")
            .setMessage(message)
            .setPositiveButton("Ya, Keluar") { _, _ ->
                if (isOfflineExam) {
                    handleStudentExitEvent("manual_logout")
                }
                try { stopLockTask() } catch (e: Exception) {}

                // --- PERBAIKAN UTAMA: Tambahkan Delay sebelum kill process ---
                // Beri waktu 300ms agar CacheManager sempat menyimpan status Terkunci ke memory
                Handler(Looper.getMainLooper()).postDelayed({
                    finishAffinity()
                    exitProcess(0)
                }, 300)
            }
            .setNegativeButton("Tidak", null)
            .setOnDismissListener { isShowingAppDialog = false } // Tambahkan ini
            .show()
    }


    private fun showGoogleLogoutConfirmation() {
        isShowingAppDialog = true // Tambahkan ini
        AlertDialog.Builder(this)
            .setTitle("Ganti Akun")
            .setMessage("Yakin ingin mengganti akun Google Anda?")
            .setPositiveButton("Ya") { _, _ ->
                loadingLayout.visibility = View.VISIBLE
                isDashboardReady = false
                lastFetchedData = null
                webView.evaluateJavascript("if(typeof cancelWatchdog === 'function') cancelWatchdog();", null)
                webView.loadUrl("file:///android_asset/index.html")
                CookieManager.getInstance().removeAllCookies(null)
                try { firebaseAuth.signOut() } catch (e: Exception) {}
                signInClient.signOut().addOnCompleteListener {
                    Handler(Looper.getMainLooper()).postDelayed({
                        triggerAccountPicker()
                    }, 200)
                }
            }
            .setNegativeButton("Batal", null)
            .setOnDismissListener { isShowingAppDialog = false } // Tambahkan ini
            .show()
    }

    private fun reloadPage() {
        if (csvDataFetcher.isOfflineMode()) {
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.reload()
            return
        }

        if (isReloading) {
            hasPendingReload = true
            return
        }
        isReloading = true
        val url = webView.url
        val email = getActiveUserEmail()

        if (url == null || url.endsWith("index.html")) {
            loadingLayout.visibility = View.VISIBLE
            refreshHandler.removeCallbacksAndMessages(null)

            lifecycleScope.launch {
                try {
                    delay(1000)
                    csvDataFetcher.invalidateAllCaches()
                    if (email != null) {
                        tampilkanListSoal(email, isManualReload = true)
                    } else {
                        triggerAccountPicker()
                    }
                } finally {
                    isReloading = false
                    if (hasPendingReload) {
                        hasPendingReload = false
                        reloadPage()
                    } else {
                        if (email != null) startAutoRefresh(email)
                    }
                }
            }
        } else {
            isReloading = false
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

            // Jika mereload halaman Google, tambahkan login_hint jika belum ada
            if (email != null && (url.contains("accounts.google.com") || url.contains("docs.google.com/forms"))) {
                if (!url.contains("login_hint=")) {
                    val sep = if (url.contains("?")) "&" else "?"
                    webView.loadUrl("$url${sep}login_hint=$email")
                } else {
                    webView.reload()
                }
            } else if (email == null) {
                // Jika user tidak login app, simpan URL saat ini dan minta login app
                pendingUrl = url
                triggerAccountPicker()
            } else {
                webView.reload()
            }
        }
    }

    class WebAppInterface(private val activity: WebViewActivity) {
        @JavascriptInterface fun getExamData(): String = activity.intent.getStringExtra("EXAM_DATA") ?: "{}"

        @JavascriptInterface fun getSavedAnswers(): String {
            val examId = activity.intent.getStringExtra("EXAM_ID") ?: return "{}"
            val examDataStr = activity.intent.getStringExtra("EXAM_DATA") ?: "{}"
            val progress = CacheManager.getAnswerProgress(examId) ?: JSONArray()
            val result = JSONObject()

            val questions = try {
                val data = JSONObject(examDataStr)
                data.optJSONArray("questions") ?: data.optJSONObject("data")?.optJSONArray("questions")
            } catch (e: Exception) { null }

            for (i in 0 until progress.length()) {
                val item = progress.optJSONObject(i) ?: continue
                val qId = item.optString("id")
                if (qId.isNotEmpty()) {
                    result.put(qId, item.optInt("answerIndex", -1))
                }
            }
            return result.toString()
        }

        @JavascriptInterface fun saveAnswer(questionId: String, answerIndex: Int, isDoubtful: Boolean) {
            val examId = activity.intent.getStringExtra("EXAM_ID") ?: return
            val progress = CacheManager.getAnswerProgress(examId) ?: JSONArray()
            var found = false
            for (i in 0 until progress.length()) {
                val item = progress.optJSONObject(i)
                if (item?.optString("id") == questionId) {
                    item.put("answerIndex", answerIndex); item.put("isDoubtful", isDoubtful); found = true; break
                }
            }
            if (!found) progress.put(JSONObject().apply { put("id", questionId); put("answerIndex", answerIndex); put("isDoubtful", isDoubtful) })
            CacheManager.saveAnswerProgress(examId, progress)
        }

        @JavascriptInterface fun saveRemainingTime(seconds: Int, totalDuration: Int) {
            val examId = activity.intent.getStringExtra("EXAM_ID") ?: return
            CacheManager.saveTimeLeft(examId, seconds, totalDuration)
        }

        @JavascriptInterface fun getRemainingTime(currentTotalDuration: Int): Int {
            val examId = activity.intent.getStringExtra("EXAM_ID") ?: return -1
            return CacheManager.getTimeLeft(examId, currentTotalDuration)
        }

        @JavascriptInterface fun submitExam(scoreNotUsed: Double, responseJson: String) {
            val examId = activity.intent.getStringExtra("EXAM_ID") ?: return
            val examDataStr = activity.intent.getStringExtra("EXAM_DATA") ?: "{}"
            activity.runOnUiThread {
                try {
                    activity.isExamActive = false
                    activity.isExamSubmitted = true

                    val examData = JSONObject(examDataStr)
                    val questions = examData.optJSONArray("questions")
                        ?: examData.optJSONObject("data")?.optJSONArray("questions")
                    val respObj = JSONObject(responseJson)
                    val studentAnswers = respObj.optJSONObject("answers") ?: JSONObject()

                    // Hitung skor
                    var correctCount = 0
                    val totalQuestions = questions?.length() ?: 0
                    if (questions != null) {
                        for (i in 0 until totalQuestions) {
                            val qObj = questions.getJSONObject(i)
                            val qId = qObj.optString("id")
                            if (studentAnswers.optInt(qId, -1) == qObj.optInt("correctIndex", -1)) correctCount++
                        }
                    }

                    val finalScore = if (totalQuestions > 0)
                        (correctCount.toDouble() / totalQuestions) * 100.0 else 0.0

                    // BUILD HASIL LENGKAP
                    val result = JSONObject().apply {
                        put("subject_id", examId)
                        put("nisn", CacheManager.getStudentInfo()?.optString("nisn"))
                        put("score", finalScore)
                        put("correct_answers", correctCount)
                        put("total_questions", totalQuestions)
                        put("timestamp", System.currentTimeMillis())

                        // ✅ TAMBAHKAN INI:
                        put("answers", studentAnswers) // Jawaban siswa per soal
                        put("question_snapshot", questions ?: JSONArray()) // Snapshot soal
                    }

                    CacheManager.queueResult(result)
                    CacheManager.deleteAnswerProgress(examId)
                    CacheManager.saveLocalHistory(examId, result)
                    CacheManager.saveSubmissionStatus(examId, "pending", "Menunggu sinkronisasi")

                    val activeToken = CacheManager.getActiveToken(examId)
                    if(activeToken != null) {
                        CacheManager.markProctorTokenAsUsed(activeToken)
                        CacheManager.saveExamAttempt(examId, activeToken, "completed")
                    }

                    SyncWorker.triggerNow(activity)
                    activity.finish()
                } catch (e: Exception) { activity.finish() }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            focusCheckHandler.removeCallbacksAndMessages(null)
            return
        }
        // =========================================================================
        // PERBAIKAN 2: Cek status layar. Apakah layar dalam keadaan HIDUP (ON)?
        // Jika layar mati, PowerManager akan mendeteksi !isInteractive
        // =========================================================================
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }

        // TAMBAHKAN isScreenOn di logika pengecekan.
        // Pelanggaran HANYA tercatat JIKA (kehilangan fokus) DAN (layar masih nyala)
        focusCheckHandler.postDelayed({

                // fokus sudah kembali → aman
                if (window.decorView.hasWindowFocus()) return@postDelayed

                // keyboard sedang tampil → abaikan
                if (isKeyboardVisible) return@postDelayed

                // dialog aplikasi sendiri → abaikan
                if (isShowingAppDialog) return@postDelayed

                // ujian sudah submit → abaikan
                if (isExamSubmitted) return@postDelayed

                // ujian sudah tidak aktif → abaikan
                if (!isExamActive) return@postDelayed

                // layar mati → abaikan
                if (!isScreenOn) return@postDelayed

                // ======================
                // BARU DIANGGAP PELANGGARAN
                // ======================

                isExamActive = false

                val examId = intent.getStringExtra("EXAM_ID") ?: ""
                if (examId.isNotEmpty()) {
                    CacheManager.setSessionLocked(examId, true)
                }

            val warningHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body {
                            background-color: #f8f9fa; 
                            display: flex; 
                            flex-direction: column;
                            justify-content: center; 
                            align-items: center; 
                            height: 100vh; 
                            margin: 0; 
                            text-align: center; 
                            font-family: sans-serif; 
                            padding: 20px;
                            box-sizing: border-box;
                        }
                        h1 { color: #d32f2f; font-size: 2.2em; margin-bottom: 10px; }
                        p { font-size: 1.1em; color: #333; margin-bottom: 20px; }
                        .box { 
                            padding: 20px; 
                            border: 2px solid #d32f2f; 
                            background-color: white; 
                            border-radius: 8px; 
                            margin-bottom: 30px;
                            box-shadow: 0 4px 8px rgba(0,0,0,0.1);
                        }
                        button {
                            background-color: #d32f2f;
                            color: white;
                            border: none;
                            padding: 15px 30px;
                            font-size: 1.1em;
                            font-weight: bold;
                            border-radius: 8px;
                            cursor: pointer;
                            box-shadow: 0 4px 6px rgba(0,0,0,0.2);
                        }
                        button:active { background-color: #b71c1c; }
                    </style>
                </head>
                <body>
                    <h1>PELANGGARAN TERDETEKSI!</h1>
                    <p>Anda terdeteksi membuka layar melayang (Bubble Chat) atau Aplikasi Lain.</p>
                    <div class="box">
                        <b style="color: red; font-size: 1.2em;">SESI UJIAN ANDA TELAH DIKUNCI</b><br><br>
                        Tutup semua Chat yang melayang, lalu klik tombol di bawah untuk keluar. Anda harus meminta <b>Token Pengawas</b> untuk dapat melanjutkan ujian.
                    </div>
                    <button onclick="AndroidExitInterface.forceExitApp()">KELUAR DARI UJIAN</button>
                </body>
                </html>
            """.trimIndent()

            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun forceExitApp() {
                    runOnUiThread {
                        try { stopLockTask() } catch (e: Exception) {}
                        finish()
                    }
                }
            }, "AndroidExitInterface")

            webView.settings.javaScriptEnabled = true
            webView.loadDataWithBaseURL(null, warningHtml, "text/html", "UTF-8", null)
            webView.clearHistory()
            }, 400)

    }
}
