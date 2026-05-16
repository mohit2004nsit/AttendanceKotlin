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
import android.webkit.URLUtil
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.location.LocationManager
import android.content.Context
import android.webkit.ValueCallback
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import androidx.credentials.CustomCredential
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.accounts.Account
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    var pendingAutoCapture = false


    // ==========================================
    // ULTIMATE MODERN NATIVE GOOGLE SIGN-IN (Credential Manager)
    // ==========================================

    fun startNativeGoogleSignIn() {
        val credentialManager = CredentialManager.create(this)

        // 1. Configure the Google Sign-in options
        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false) // Shows all accounts on the phone
            .setServerClientId("247831875348-0qjc7hc9o13ipj0uaup9hsd3cbmeuo3i.apps.googleusercontent.com")
            .setAutoSelectEnabled(false)
            .build()

        // 2. Build the request
        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        // 3. Launch the native UI in a background coroutine
        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity,
                )

                val credential = result.credential

                // 4. Extract the token and send it to the WebView
                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken

                    webView.evaluateJavascript("javascript:if(window.signInFromAndroid) window.signInFromAndroid('$idToken');", null)
                }
            } catch (e: GetCredentialException) {
                Log.e("Auth", "Credential Manager failed: ${e.message}")
                android.widget.Toast.makeText(this@MainActivity, "Sign In Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }

        }
    }


    // ==========================================
    // NATIVE GOOGLE DRIVE OAUTH SYNC
    // ==========================================

    var currentGoogleEmail: String? = null

    // 1. The Launcher that catches the native "Allow Google Drive Access" popup
    private val drivePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // The user clicked "Allow"! Let's grab the token now.
            currentGoogleEmail?.let { fetchDriveTokenNatively(it) }
        } else {
            android.widget.Toast.makeText(this, "Drive sync cancelled.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 2. The function that asks Android for the Drive Token
    fun fetchDriveTokenNatively(email: String) {
        currentGoogleEmail = email
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Ask the Android OS for the secure Drive token
                val account = Account(email, "com.google")
                val scope = "oauth2:https://www.googleapis.com/auth/drive.file"
                val token = GoogleAuthUtil.getToken(this@MainActivity, account, scope)

                // Success! Send the token into the JavaScript WebView
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("javascript:if(window.onNativeDriveToken) window.onNativeDriveToken('$token');", null)
                }
            } catch (e: UserRecoverableAuthException) {
                // The OS says: "The user hasn't granted Drive permission yet!"
                // So we launch the native Android permission popup
                withContext(Dispatchers.Main) {
                    drivePermissionLauncher.launch(e.intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("DriveAuth", "Failed to get Drive token", e)
                    android.widget.Toast.makeText(this@MainActivity, "Sync Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ==========================================
    // NATIVE FILE PICKER (For Excel, Images, and PDFs)
    // ==========================================

    // 1. Holds the connection to the web page's upload button
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 2. The Native Android File Picker Launcher
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data?.data
            if (data != null) {
                // User picked a single file, send it back to HTML
                filePathCallback?.onReceiveValue(arrayOf(data))
            } else {
                filePathCallback?.onReceiveValue(null) // Cancelled
            }
        } else {
            filePathCallback?.onReceiveValue(null) // Cancelled
        }
        filePathCallback = null // Reset the callback so it can be used again
    }


    // ==========================================
    // 1. SMART PERMISSION SYSTEM (Just-In-Time)
    // ==========================================

    // Group 1: Bluetooth & Location (For Auto-Capture / Beacons)
    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // 🚨 FIX 1: Removed RECORD_AUDIO. This stops Android from silently auto-denying the camera!
    val cameraPermissions = arrayOf(Manifest.permission.CAMERA)

    // Launchers catch the result and tell Javascript to proceed
    private val blePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.all { it.value }) {
            webView.evaluateJavascript("javascript:if(window.onBlePermissionsGranted) window.onBlePermissionsGranted();", null)
            // 🚨 ADD THIS LINE TO RESUME AFTER PERMISSION GRANTED:
            webView.evaluateJavascript("javascript:if(window.resumeAutoCapture) window.resumeAutoCapture();", null)
        } else {
            showPermissionSettingsDialog("Bluetooth & Location")
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.all { it.value }) {
            webView.evaluateJavascript("javascript:if(window.onCameraPermissionsGranted) window.onCameraPermissionsGranted();", null)
        } else {
            showPermissionSettingsDialog("Camera")
        }
    }

    // Functions to trigger the checks
    fun checkAndRequestBlePermissions() {
        val hasAll = blePermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!hasAll) blePermissionLauncher.launch(blePermissions)
        else webView.evaluateJavascript("javascript:if(window.onBlePermissionsGranted) window.onBlePermissionsGranted();", null)
    }

    fun checkAndRequestCameraPermissions() {
        val hasAll = cameraPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!hasAll) cameraPermissionLauncher.launch(cameraPermissions)
        else webView.evaluateJavascript("javascript:if(window.onCameraPermissionsGranted) window.onCameraPermissionsGranted();", null)
    }

    private fun showPermissionSettingsDialog(type: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required ⚙️")
            .setMessage("iClassroom requires $type access to perform this action.\n\nPlease tap 'Settings', go to 'Permissions', and allow it.")
            .setCancelable(false)
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            // 🚨 FIX 2: Tell Javascript the user canceled so it unfreezes the UI and stops script errors!
            .setNegativeButton("Cancel") { _, _ ->
                if (type == "Camera") {
                    webView.evaluateJavascript("javascript:if(window.onCameraPermissionsDenied) window.onCameraPermissionsDenied();", null)
                } else {
                    webView.evaluateJavascript("javascript:if(window.abortAutoCapture) window.abortAutoCapture();", null)
                }
            }
            .show()
    }

    // 🚨 FIX 3: Wrapped the Bluetooth check in a try-catch so it doesn't crash the app!
    override fun onResume() {
        super.onResume()
        if (pendingAutoCapture) {
            try {
                val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val isBtOn = bluetoothManager.adapter?.isEnabled == true
                val isLocOn = isLocationEnabled()

                if (isBtOn && isLocOn) {
                    pendingAutoCapture = false
                    webView.evaluateJavascript("javascript:if(window.resumeAutoCapture) window.resumeAutoCapture();", null)
                } else {
                    pendingAutoCapture = false
                    webView.evaluateJavascript("javascript:if(window.abortAutoCapture) window.abortAutoCapture();", null)
                }
            } catch (e: SecurityException) {
                // User hasn't granted permissions yet, fail gracefully instead of crashing
                pendingAutoCapture = false
                webView.evaluateJavascript("javascript:if(window.abortAutoCapture) window.abortAutoCapture();", null)
            }
        }
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
        try {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } catch (e: SecurityException) {
            // Android 12+ strict enforcement caught here!
            checkAndRequestBlePermissions()
        }
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

        webView.loadUrl(BuildConfig.WEBAPP_URL)
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
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

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

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.startsWith("blob:")) {
                // 🚨 FIX: Handling Blob URLs (Modern Excel exports)
                // These don't work with DownloadManager, so we extract them via JS
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val js = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function(e) {
                            if (this.status == 200) {
                                var blob = this.response;
                                var reader = new FileReader();
                                reader.readAsDataURL(blob);
                                reader.onloadend = function() {
                                    var base64data = reader.result;
                                    AndroidApp.processBlobDownload(base64data, '$mimetype', '$fileName');
                                }
                            }
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            } else {
                // Normal HTTP/HTTPS URLs
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimetype)
                val cookies = CookieManager.getInstance().getCookie(url)
                request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Downloading file...")
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(applicationContext, "Downloading File", Toast.LENGTH_LONG).show()
            }
        }

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

            // 🚨 INTERCEPT FILE UPLOADS 🚨
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any existing request that might be hanging
                this@MainActivity.filePathCallback?.onReceiveValue(null)

                // Save the new callback to our global variable
                this@MainActivity.filePathCallback = filePathCallback

                // fileChooserParams automatically reads your HTML "accept" tags!
                // So it knows if it should look for PDFs, Images, or Excels.
                val intent = fileChooserParams?.createIntent()

                try {
                    if (intent != null) {
                        fileChooserLauncher.launch(intent)
                    }
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
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