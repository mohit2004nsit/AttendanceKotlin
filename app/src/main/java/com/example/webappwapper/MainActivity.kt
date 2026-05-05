package com.example.webappwapper

import android.Manifest
import android.app.Dialog
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.Activity
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.location.LocationManager
import android.content.Context


class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    var pendingAutoCapture = false


    // ==========================================
    // 1. STRICT PERMISSION SYSTEM ("THE HARD GATE")
    // ==========================================

    // Safely handles both older Androids and Android 12+ Bluetooth rules
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            showPermissionSettingsDialog()
        }
    }

    // This automatically checks permissions EVERY TIME they open or return to the app
    override fun onResume() {
        super.onResume()
        if (!hasAllPermissions()) {
            requestPermissionsLauncher.launch(requiredPermissions)
        } else if (pendingAutoCapture) {
            // 🚨 NEW: They just came back from settings. Check if they fixed it!
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val isBtOn = bluetoothManager.adapter?.isEnabled == true
            val isLocOn = isLocationEnabled()

            if (isBtOn && isLocOn) {
                // They fixed it! Auto-start the scan!
                pendingAutoCapture = false
                webView.evaluateJavascript("javascript:if(window.resumeAutoCapture) window.resumeAutoCapture();", null)
            } else {
                // They came back but didn't turn it on. Cancel the scan.
                pendingAutoCapture = false
                webView.evaluateJavascript("javascript:if(window.abortAutoCapture) window.abortAutoCapture();", null)
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required 🛑")
            .setMessage("iClassroom requires your Camera, Microphone, Location, and Bluetooth to securely mark attendance.\n\nPlease tap 'Settings', go to 'Permissions', and allow all of them to continue.")
            .setCancelable(false) // Prevents them from tapping outside to dismiss it
            .setPositiveButton("Go to Settings") { _, _ ->
                // Teleports them directly to the app's permission page in Android Settings!
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Exit App") { _, _ ->
                finish() // Closes the app if they refuse
            }
            .show()
    }

    // ==========================================
    // NEW: Smart Launcher to chain BT and Location
    // ==========================================
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // They turned on Bluetooth! Are we waiting to scan?
            if (pendingAutoCapture) {
                if (!isLocationEnabled()) {
                    // Chain the next prompt automatically
                    promptEnableLocation()
                } else {
                    // Both are ON! Auto-start the scan!
                    pendingAutoCapture = false
                    webView.evaluateJavascript("javascript:if(window.resumeAutoCapture) window.resumeAutoCapture();", null)
                }
            }
        } else {
            // User hit "Deny". Cancel the waiting state.
            if (pendingAutoCapture) {
                pendingAutoCapture = false
                webView.evaluateJavascript("javascript:if(window.abortAutoCapture) window.abortAutoCapture();", null)
            }
        }
    }

    // A public function your WebAppInterface can call
    fun promptEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(enableBtIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        // Handle system bar padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupWebView()
        handleBackNavigation()

        // Pointing to the Test Environment
        webView.loadUrl("https://attendance-dtu--test-vybq7g6v.web.app/")
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK

            // Fix Google Login by customising the User Agent
            val defaultUA = userAgentString
            val customUA = defaultUA
                .replace("; wv", "")
                .replace("Version/\\d+\\.\\d+\\s".toRegex(), "")
            userAgentString = customUA
        }

        // IMPORTANT: Enable third-party cookies for OAuth to work
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Attach your Bluetooth Bridge
        webView.addJavascriptInterface(WebAppInterface(this, webView), "AndroidApp")

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {

            // ==========================================
            // 3. NEW: Tell WebView to allow HTML5 Camera/Mic
            // ==========================================
            override fun onPermissionRequest(request: PermissionRequest?) {
                // This automatically grants the web app access to the camera and microphone
                // IF the native Android permissions (from step 2) were granted by the user.
                request?.grant(request.resources)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebViewConsole", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                // Let Chrome DevTools see the error!
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.settings.apply {
                    javaScriptEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    domStorageEnabled = true
                    setSupportMultipleWindows(true)
                    userAgentString = view?.settings?.userAgentString
                }

                CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)

                val dialog = Dialog(this@MainActivity)
                dialog.setContentView(newWebView)
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                dialog.show()

                newWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        dialog.dismiss()
                        newWebView.destroy()
                    }
                }

                newWebView.webViewClient = WebViewClient()

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()

                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }


    // ==========================================
    // NEW: Check if physical GPS/Location is ON
    // ==========================================
    fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    // ==========================================
    // NEW: Pop up to ask Student to turn it on
    // ==========================================
    fun promptEnableLocation() {
        AlertDialog.Builder(this)
            .setTitle("Location is Off 📍")
            .setMessage("Android requires your device's Location/GPS to be turned ON to scan for the Teacher's Bluetooth signal.\n\nPlease tap 'Turn On' to enable it.")
            .setCancelable(false)
            .setPositiveButton("Turn On") { _, _ ->
                // Teleports them directly to the Location Settings page
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}