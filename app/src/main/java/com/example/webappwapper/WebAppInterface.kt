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

class WebAppInterface(private val context: Context, private val webView: WebView) {

    private val APP_ID = 43981

    // --- Shared Helper ---
    private fun getAdapter(): BluetoothAdapter? {
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                Handler(Looper.getMainLooper()).post {
                    if (context is MainActivity) context.promptEnableBluetooth()
                    else showToast("Please turn on your Bluetooth!")
                }
                return null
            }
            return adapter
        } catch (e: Exception) {
            Log.e("BLE", "Error getting adapter: ${e.message}")
            return null
        }
    }

    // --- Shared Helper for Scanners (Checks BT + Location) ---
    private fun getScannerAdapter(isAutoCapture: Boolean = false): BluetoothAdapter? {
        try {
            val mainActivity = context as? MainActivity
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val isBtOn = bluetoothManager.adapter?.isEnabled == true
            val isLocOn = mainActivity?.isLocationEnabled() == true

            // 🚨 CRITICAL FIX: Set the memory flag BEFORE anything else happens!
            if ((!isBtOn || !isLocOn) && isAutoCapture && mainActivity != null) {
                mainActivity.pendingAutoCapture = true
            }

            // Now trigger the normal Bluetooth check/popup
            val adapter = getAdapter() ?: return null

            // If we survive the line above, Bluetooth is ON. Now check Location.
            if (mainActivity != null && !isLocOn) {
                Handler(Looper.getMainLooper()).post {
                    mainActivity.promptEnableLocation()
                }
                return null // Abort scan because Location is off
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
            val adapter = getAdapter() ?: return false
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
            val adapter = getAdapter() ?: return false
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
            val adapter = getScannerAdapter() ?: return false
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

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }
}