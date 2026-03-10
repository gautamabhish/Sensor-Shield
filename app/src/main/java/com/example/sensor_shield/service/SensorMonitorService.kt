package com.example.sensor_shield.service

import android.app.*
import android.app.AppOpsManager.OnOpActiveChangedListener
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.sensor_shield.MainActivity
import com.example.sensor_shield.data.AppDatabase
import com.example.sensor_shield.data.SensorEvent
import com.example.sensor_shield.engine.RiskEngine
import kotlinx.coroutines.*

class SensorMonitorService : Service() {

    private val TAG = "SensorShield"
    private val channelId = "SensorMonitorChannel"
    private val notificationId = 1

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var powerManager: PowerManager
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var audioManager: AudioManager
    private lateinit var cameraManager: CameraManager
    private lateinit var appOpsManager: AppOpsManager

    /**
     * AppOps listener (camera, mic, location)
     * Detects real-time activation of sensors.
     */
    private val opListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        OnOpActiveChangedListener { op, _, packageName, active ->
            if (active) {
                // If the package is GMS, it's often a proxy for another app
                val pkg = if (packageName == "com.google.android.gms") getForegroundPackageName() else packageName
                if (pkg != null && pkg != "unknown") {
                    Log.w(TAG, "Active Sensor Op: $op by $pkg")
                    handleSensorActivation(pkg, op)
                }
            }
        }
    } else null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Sensor Monitor Service Created")

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        createNotificationChannel()
        val notification = createNotification("Privacy Guard is monitoring sensors")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }

        setupMonitoring()
        startPollingLoop() 
    }

    private fun setupMonitoring() {
        // 1. AppOps Monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && opListener != null) {
            val ops = arrayOf(
                AppOpsManager.OPSTR_CAMERA,
                AppOpsManager.OPSTR_RECORD_AUDIO,
                AppOpsManager.OPSTR_FINE_LOCATION,
                AppOpsManager.OPSTR_COARSE_LOCATION,
                AppOpsManager.OPSTR_MONITOR_LOCATION,
                AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION
            )
            val executor = ContextCompat.getMainExecutor(this)
            ops.forEach { op ->
                try {
                    appOpsManager.startWatchingActive(arrayOf(op), executor, opListener)
                    Log.d(TAG, "Watching AppOp: $op")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to watch $op: ${e.message}")
                }
            }
        }

        // 2. Camera Callback
        cameraManager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                val pkg = getForegroundPackageName()
                handleSensorActivation(pkg, "android:camera")
            }
        }, null)

        // 3. Audio Recording Callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioManager.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    if (configs.isNotEmpty()) {
                        val pkg = getForegroundPackageName()
                        handleSensorActivation(pkg, "android:microphone")
                    }
                }
            }, null)
        }
    }

    private fun startPollingLoop() {
        serviceScope.launch {
            while (isActive) {
                // Polling fallback for location as it's often not "active" for long enough to trigger callbacks
                checkLocationUsage()
                delay(15000)
            }
        }
    }

    private fun checkLocationUsage() {
        try {
            val pkg = getForegroundPackageName()
            if (pkg == "unknown" || pkg == packageName) return

            // Check if the foreground app is allowed to use location
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), pkg)
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), pkg)
            }

            if (mode == AppOpsManager.MODE_ALLOWED) {
                handleSensorActivation(pkg, AppOpsManager.OPSTR_FINE_LOCATION)
            }
        } catch (e: Exception) { /* ignore */ }
    }

    private fun handleSensorActivation(packageName: String, op: String) {
        if (packageName == this.packageName || packageName == "unknown") return

        val isScreenOn = powerManager.isInteractive
        val foregroundPkg = getForegroundPackageName()
        val isForeground = (packageName == foregroundPkg)

        val risk = RiskEngine.calculateRisk(packageName, op, isForeground, isScreenOn)

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                // Deduplicate: Don't log same sensor for same app within 20 seconds
                val recentCount = db.sensorDao().getRecentCount(packageName, op, System.currentTimeMillis() - 20000)
                
                if (recentCount == 0) {
                    db.sensorDao().insertEvent(
                        SensorEvent(
                            packageName = packageName,
                            sensorType = op,
                            isForeground = isForeground,
                            riskScore = risk.score,
                            isAnomalous = risk.isAnomalous
                        )
                    )
                    Log.i(TAG, "LOGGED: $packageName used $op (Risk: ${risk.score})")
                    
                    if (risk.isAnomalous) {
                        showRiskNotification(packageName, op)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Database error: ${e.message}")
            }
        }
    }

    private fun showRiskNotification(packageName: String, sensor: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cleanSensor = sensor.replace("android:", "").uppercase()
        val appName = packageName.split(".").last().replaceFirstChar { it.uppercase() }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Privacy Alert!")
            .setContentText("$appName is using $cleanSensor in the background")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun getForegroundPackageName(): String {
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: "unknown"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sensor Monitoring", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sensor Shield")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && opListener != null) {
            appOpsManager.stopWatchingActive(opListener)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
