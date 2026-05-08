package com.example.webappwapper

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Locale
import java.util.Locale.getDefault

class WebAppInterface(private val context: Context, private val webView: WebView) {

    private val APP_ID = 43981

    // --- Shared Helper ---
    private fun hasBlePermissions(): Boolean {
        val mainActivity = context as? MainActivity ?: return false
        return mainActivity.blePermissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(mainActivity, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getAdapter(isAutoCapture: Boolean = false): BluetoothAdapter? {
        try {
            val mainActivity = context as? MainActivity
            // 🚨 FIX 1: Ask for permissions BEFORE touching Bluetooth to prevent crashes
            if (mainActivity != null && !hasBlePermissions()) {
                if (isAutoCapture) mainActivity.pendingAutoCapture = true
                Handler(Looper.getMainLooper()).post {
                    mainActivity.checkAndRequestBlePermissions()
                }
                return null
            }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                if (isAutoCapture && mainActivity != null) mainActivity.pendingAutoCapture = true
                Handler(Looper.getMainLooper()).post {
                    mainActivity?.promptEnableBluetooth()
                }
                return null
            }
            return adapter
        } catch (e: Exception) {
            Log.e("BLE", "Error getting adapter: ${e.message}")
            return null
        }
    }

    private fun getScannerAdapter(isAutoCapture: Boolean = false): BluetoothAdapter? {
        try {
            val mainActivity = context as? MainActivity
            val adapter = getAdapter(isAutoCapture) ?: return null

            val isLocOn = mainActivity?.isLocationEnabled() == true
            if (!isLocOn) {
                if (isAutoCapture && mainActivity != null) mainActivity.pendingAutoCapture = true
                Handler(Looper.getMainLooper()).post {
                    mainActivity?.promptEnableLocation()
                }
                return null
            }

            return adapter
        } catch (e: Exception) {
            Log.e("BLE", "Error getting scanner adapter: ${e.message}")
            return null
        }
    }

    // --- State Trackers ---
    private var studentAdvertiserCallback: AdvertiseCallback? = null
    private var studentScannerCallback: ScanCallback? = null
    private var teacherAdvertiserCallback: AdvertiseCallback? = null
    private var teacherScannerCallback: ScanCallback? = null

    // ==========================================
    // 1. STUDENT: BROADCAST ATTENDANCE (15s Burst)
    // ==========================================
    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun broadcastBluetooth(sessionCode: String, rollNumber: String, timestamp: String): Boolean {
        try {
            val adapter = getAdapter(isAutoCapture = false) ?: return false
            val advertiser = adapter.bluetoothLeAdvertiser ?: return false

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            val payloadBytes = "$sessionCode|$rollNumber".toByteArray(Charsets.UTF_8)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(APP_ID, payloadBytes)
                .build()

            studentAdvertiserCallback = object : AdvertiseCallback() {}
            advertiser.startAdvertising(settings, data, studentAdvertiserCallback)

            Handler(Looper.getMainLooper()).postDelayed({
                try { advertiser.stopAdvertising(studentAdvertiserCallback) } catch (e: Exception) {}
            }, 15000)

            return true
        } catch (e: Exception) {
            Log.e("BLE", "Broadcast crash: ${e.message}")
            return false
        }
    }

    // ==========================================
    // 2. STUDENT: SCAN FOR TEACHER BEACON (60s Burst)
    // ==========================================
    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun startStudentCodeScanner(): Boolean {
        try {
            val adapter = getScannerAdapter(isAutoCapture = true) ?: return false
            val scanner = adapter.bluetoothLeScanner ?: return false

            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            studentScannerCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.scanRecord?.getManufacturerSpecificData(APP_ID)?.let { bytes ->
                        val payload = String(bytes, Charsets.UTF_8)
                        val parts = payload.split("|")
                        if (parts.size >= 2 && parts[1] == "HOST") {
                            val code = parts[0]
                            val qrMode = if (parts.size >= 3) parts[2] else "peer"
                            Handler(Looper.getMainLooper()).post {
                                webView.evaluateJavascript("javascript:if(window.onBluetoothCodeFound) window.onBluetoothCodeFound('$code', '$qrMode');", null)
                            }
                            stopStudentCodeScanner()
                        }
                    }
                }
            }
            // 🚨 FIX: Pass emptyList() instead of null to prevent OEM hardware crashes
            scanner.startScan(emptyList(), settings, studentScannerCallback)

            Handler(Looper.getMainLooper()).postDelayed({ stopStudentCodeScanner() }, 60000)
            return true
        } catch (e: Exception) {
            Log.e("BLE", "Scanner crash: ${e.message}")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun stopStudentCodeScanner() {
        try {
            val adapter = getAdapter() ?: return
            adapter.bluetoothLeScanner?.stopScan(studentScannerCallback)
            studentScannerCallback = null
        } catch (e: Exception) {}
    }

    // ==========================================
    // 3. TEACHER: BROADCAST BEACON (Indefinite)
    // ==========================================
    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun startTeacherBeacon(sessionCode: String, qrMode: String): Boolean {
        try {
            val adapter = getAdapter(isAutoCapture = true) ?: return false
            val advertiser = adapter.bluetoothLeAdvertiser ?: return false

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            val payloadBytes = "$sessionCode|HOST|$qrMode".toByteArray(Charsets.UTF_8)
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(APP_ID, payloadBytes)
                .build()

            teacherAdvertiserCallback = object : AdvertiseCallback() {}
            advertiser.startAdvertising(settings, data, teacherAdvertiserCallback)
            return true
        } catch (e: Exception) {
            Log.e("BLE", "Beacon crash: ${e.message}")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun stopTeacherBeacon() {
        try {
            val adapter = getAdapter() ?: return
            adapter.bluetoothLeAdvertiser?.stopAdvertising(teacherAdvertiserCallback)
            teacherAdvertiserCallback = null
        } catch (e: Exception) {}
    }

    // ==========================================
    // 4. TEACHER: SCAN FOR STUDENTS (Indefinite)
    // ==========================================
    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun startBluetoothScanner(sessionCode: String): Boolean {
        try {
            // 🚨 FIX: Pass isAutoCapture = true so the Teacher's phone remembers to auto-resume!
            val adapter = getScannerAdapter(isAutoCapture = true) ?: return false
            val scanner = adapter.bluetoothLeScanner ?: return false

            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            teacherScannerCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result?.scanRecord?.getManufacturerSpecificData(APP_ID)?.let { bytes ->
                        val payload = String(bytes, Charsets.UTF_8)
                        val parts = payload.split("|")
                        if (parts.size >= 2 && parts[0] == sessionCode && parts[1] != "HOST") {
                            val rollNumber = parts[1]
                            Handler(Looper.getMainLooper()).post {
                                webView.evaluateJavascript("javascript:if(window.onBluetoothAttendanceReceived) window.onBluetoothAttendanceReceived('$rollNumber');", null)
                            }
                        }
                    }
                }
            }
            // 🚨 FIX: Pass emptyList() instead of null to prevent OEM hardware crashes
            scanner.startScan(emptyList(), settings, teacherScannerCallback)
            return true
        } catch (e: Exception) {
            Log.e("BLE", "Teacher Scanner crash: ${e.message}")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    @JavascriptInterface
    fun stopBluetoothScanner() {
        try {
            val adapter = getAdapter() ?: return
            adapter.bluetoothLeScanner?.stopScan(teacherScannerCallback)
            teacherScannerCallback = null
        } catch (e: Exception) {}
    }


    @JavascriptInterface
    fun triggerNativeLogin() {
        // Tell MainActivity to open the native Google Account picker
        Handler(Looper.getMainLooper()).post {
            (context as? MainActivity)?.startNativeGoogleSignIn()
        }
    }

    @JavascriptInterface
    fun requestDriveToken(email: String) {
        // Tell Android to get the Google Drive token for this specific email
        Handler(Looper.getMainLooper()).post {
            (context as? MainActivity)?.fetchDriveTokenNatively(email)
        }
    }


    // ==========================================
    // 5. NATIVE UI BRIDGE (Updated)
    // ==========================================

    // 🚨 ADD THIS NEW FUNCTION HERE
    @JavascriptInterface
    fun triggerHapticFeedback(patternType: String) {
        Handler(Looper.getMainLooper()).post {

            // This grabs the Vibrator service safely, supporting all Android versions
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {

                // We define specific vibration patterns based on the action
                when (patternType.lowercase(getDefault())) {

                    // A very light, sharp "tick" (perfect for marking attendance)
                    "tick" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            // Effect: 20ms pulse at medium intensity
                            vibrator.vibrate(VibrationEffect.createOneShot(20, 100))
                        } else {
                            // Fallback for older Androids
                            vibrator.vibrate(20)
                        }
                    }

                    // A slightly longer pulse (great for "Saving complete")
                    "success" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(100, 180))
                        } else {
                            vibrator.vibrate(100)
                        }
                    }

                    // A heavy, jarring pattern (for errors or cancellations)
                    "error" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 200), -1))
                        } else {
                            vibrator.vibrate(longArrayOf(0, 50, 50, 200), -1)
                        }
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun requestBlePermissions() {
        Handler(Looper.getMainLooper()).post {
            (context as? MainActivity)?.checkAndRequestBlePermissions()
        }
    }

    @JavascriptInterface
    fun requestCameraPermissions() {
        Handler(Looper.getMainLooper()).post {
            (context as? MainActivity)?.checkAndRequestCameraPermissions()
        }
    }


    // ==========================================
    @JavascriptInterface
    fun showNativeToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            // Toast.LENGTH_SHORT is about 2 seconds, standard for Android
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}