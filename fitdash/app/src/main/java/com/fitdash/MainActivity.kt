package com.fitdash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.fitdash.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hcManager: HealthConnectManager
    private lateinit var driveBackup: DriveBackupManager
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val DRIVE_SIGN_IN_RC = 9001
    var lastPayload: HealthConnectManager.DashboardPayload? = null
    private var lastLoadTime = 0L
    private var isLoading = false

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        loadDashboardData()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        hcManager = HealthConnectManager(this)
        driveBackup = DriveBackupManager(this)
        setupWebView()
        HealthSyncWorker.schedule(this)

        if (!hcManager.isAvailable()) {
            showError("Health Connect is not available on this device.")
            return
        }

        requestBatteryOptimizationExemption()
        requestPermissionsIfNeeded()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
            }
            addJavascriptInterface(JsBridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Only request permissions on first load, don't trigger data reload
                    lifecycleScope.launch {
                        if (!hcManager.hasPermissions()) {
                            requestPermissions.launch(hcManager.permissions + hcManager.historyPermission)
                        }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    android.util.Log.d("FitDash:JS", "${msg.message()} [${msg.lineNumber()}]")
                    return true
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
    }

    private fun requestPermissionsIfNeeded() {
        lifecycleScope.launch {
            if (!hcManager.hasPermissions()) {
                requestPermissions.launch(hcManager.permissions + hcManager.historyPermission)
            } else {
                loadDashboardData()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    fun loadDashboardData() {
        if (isLoading) return
        isLoading = true
        lastLoadTime = System.currentTimeMillis()
        lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    hcManager.withRawJson(hcManager.fetchDashboard())
                }
                lastPayload = payload
                val json    = payload.rawJson
                val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                binding.webView.post {
                    binding.webView.evaluateJavascript(
                        "window._hcDataLoaded=true; window.onHealthData && window.onHealthData('$escaped');", null
                    )
                }

                getSharedPreferences("fitdash_widget", MODE_PRIVATE).edit()
                    .putInt("steps", payload.today.steps.toInt())
                    .putInt("goal",  payload.today.stepsGoal.toInt())
                    .apply()

                val ctx           = applicationContext
                val widgetManager = android.appwidget.AppWidgetManager.getInstance(ctx)
                val widgetIds     = widgetManager.getAppWidgetIds(
                    android.content.ComponentName(ctx, StepsWidgetProvider::class.java)
                )
                if (widgetIds.isNotEmpty()) {
                    StepsWidgetProvider().onUpdate(ctx, widgetManager, widgetIds)
                }

                pushStepsToWatch(payload.today.steps.toInt(), payload.today.stepsGoal.toInt())
                fetchAndPushHistory()
            } catch (e: Exception) {
                val isQuota = e.message?.contains("quota exceeded", ignoreCase = true) == true ||
                              e.cause?.message?.contains("quota exceeded", ignoreCase = true) == true
                if (isQuota) {
                    android.util.Log.w("FitDash", "HC quota exceeded — skipping refresh")
                    lastLoadTime = 0L  // reset so user can try again once quota recovers
                    runOnUiThread {
                        android.widget.Toast.makeText(this@MainActivity, "HC rate limited — try again later", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.util.Log.e("FitDash", "HC error", e)
                    showError("Failed to load health data: ${e.message}")
                }
            } finally {
                isLoading = false
            }
        }
    }

    private fun showError(msg: String) {
        binding.webView.post {
            binding.webView.evaluateJavascript(
                "window.onHealthError && window.onHealthError('${msg.replace("'","\\'")}');", null
            )
        }
    }

    private fun pushStepsToWatch(steps: Int, goal: Int) {
        lifecycleScope.launch {
            try {
                val dataClient = com.google.android.gms.wearable.Wearable.getDataClient(applicationContext)
                val putDataReq = com.google.android.gms.wearable.PutDataMapRequest.create("/fitdash/steps").apply {
                    dataMap.putInt("steps", steps)
                    dataMap.putInt("goal", goal)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                com.google.android.gms.tasks.Tasks.await(dataClient.putDataItem(putDataReq))
            } catch (e: Exception) {
                android.util.Log.w("FitDash", "Watch push failed: ${e.message}")
            }
        }
    }

    private fun fetchAndPushHistory() {
        lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) { hcManager.fetchHistory(90) }
                val json    = com.google.gson.Gson().toJson(history)
                val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                binding.webView.post {
                    binding.webView.evaluateJavascript(
                        "window.onHistoryData && window.onHistoryData('$escaped');", null
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("FitDash", "History fetch failed", e)
            }
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun refresh() {
            loadDashboardData()
        }

        @JavascriptInterface
        fun requestPermissions() {
            runOnUiThread { requestPermissionsIfNeeded() }
        }

        @JavascriptInterface
        fun getBackupStatus(): String {
            val account = driveBackup.getSignedInAccount()
            return org.json.JSONObject().apply {
                put("signedIn",    account != null)
                put("email",       account?.email ?: "")
                put("lastBackup",  driveBackup.formatTime(driveBackup.getLastBackupTime()))
                put("lastRestore", driveBackup.formatTime(driveBackup.getLastRestoreTime()))
            }.toString()
        }

        @JavascriptInterface
        fun backupNow() {
            mainScope.launch {
                val ok  = driveBackup.backup()
                val msg = if (ok) "Settings backed up" else "Backup failed"
                binding.webView.post {
                    binding.webView.evaluateJavascript("window.onBackupResult('$msg')", null)
                }
            }
        }

        @JavascriptInterface
        fun restoreNow() {
            mainScope.launch {
                val ok = driveBackup.restore()
                if (ok) {
                    // Reschedule worker with potentially restored interval
                    HealthSyncWorker.schedule(this@MainActivity)
                }
                val msg = if (ok) "Settings restored" else "No backup found"
                binding.webView.post {
                    binding.webView.evaluateJavascript("window.onRestoreResult('$msg')", null)
                }
            }
        }

        @JavascriptInterface
        fun signInToDrive() {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
                .build()
            val client = GoogleSignIn.getClient(this@MainActivity, gso)
            startActivityForResult(client.signInIntent, DRIVE_SIGN_IN_RC)
        }

        @JavascriptInterface
        fun signOutOfDrive() {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(this@MainActivity, gso).signOut().addOnCompleteListener {
                binding.webView.post {
                    binding.webView.evaluateJavascript("window.onBackupStatusChanged()", null)
                }
            }
        }

        @JavascriptInterface
        fun getUltraSignalToken(): String {
            return UltraSignalSyncer(this@MainActivity).getApiToken() ?: ""
        }

        @JavascriptInterface
        fun setUltraSignalToken(token: String) {
            UltraSignalSyncer(this@MainActivity).setApiToken(token.trim())
        }

        @JavascriptInterface
        fun getSyncInterval(): String {
            val prefs = getSharedPreferences("fitdash_settings", MODE_PRIVATE)
            return prefs.getInt("sync_interval_minutes", 30).toString()
        }

        @JavascriptInterface
        fun setSyncInterval(minutes: Int) {
            getSharedPreferences("fitdash_settings", MODE_PRIVATE)
                .edit().putInt("sync_interval_minutes", minutes).apply()
            // Reschedule WorkManager with the new interval
            HealthSyncWorker.schedule(this@MainActivity)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DRIVE_SIGN_IN_RC) {
            GoogleSignIn.getSignedInAccountFromIntent(data)
                .addOnSuccessListener { account ->
                    android.util.Log.d("FitDash:Drive", "Signed in as ${account.email}")
                    binding.webView.post {
                        binding.webView.evaluateJavascript("window.onBackupStatusChanged()", null)
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("FitDash:Drive", "Sign-in failed", e)
                    binding.webView.post {
                        binding.webView.evaluateJavascript("window.onBackupResult('Sign-in failed')", null)
                    }
                }
        }
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastLoadTime > 5 * 60 * 1000) {
            loadDashboardData()
        }
    }
}
