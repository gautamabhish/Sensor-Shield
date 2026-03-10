package com.example.sensor_shield.service

import android.app.*
import android.app.AppOpsManager.OnOpActiveChangedListener
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.TrafficStats
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
import com.example.sensor_shield.engine.AccessCategory
import kotlinx.coroutines.*

class SensorMonitorService : Service() {

    companion object {
        const val ACTION_KILL_APP = "com.example.sensor_shield.ACTION_KILL_APP"
        const val ACTION_TRUST_APP = "com.example.sensor_shield.ACTION_TRUST_APP"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private val TAG = "SensorShield"
    private val channelId = "SensorMonitorChannel"
    private val alertChannelId = "SensorAlertChannel"
    private val notificationId = 1001

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var powerManager: PowerManager
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var audioManager: AudioManager
    private lateinit var cameraManager: CameraManager
    private lateinit var appOpsManager: AppOpsManager
    private lateinit var activityManager: ActivityManager

    private val opListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        OnOpActiveChangedListener { op, _, packageName, active ->
            if (active) {
                val pkg = if (packageName == "com.google.android.gms") getForegroundPackageName() else packageName
                if (pkg != null && pkg != "unknown") {
                    handleSensorActivation(pkg, op)
                }
            }
        }
    } else null

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        createNotificationChannels()
        val notification = createNotification("Behavioral Security Active")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }

        setupMonitoring()
        startPollingLoop() 
    }

    private fun setupMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && opListener != null) {
            val ops = arrayOf(
                AppOpsManager.OPSTR_CAMERA,
                AppOpsManager.OPSTR_RECORD_AUDIO,
                AppOpsManager.OPSTR_FINE_LOCATION
            )
            val executor = ContextCompat.getMainExecutor(this)
            ops.forEach { op ->
                try {
                    appOpsManager.startWatchingActive(arrayOf(op), executor, opListener)
                } catch (e: Exception) { }
            }
        }

        cameraManager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                val pkg = getForegroundPackageName()
                handleSensorActivation(pkg, "android:camera")
            }
        }, null)

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
                checkLocationUsage()
                delay(15000)
            }
        }
    }

    private fun checkLocationUsage() {
        try {
            val pkg = getForegroundPackageName()
            if (pkg == "unknown" || pkg == packageName) return

            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), pkg)
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, android.os.Process.myUid(), pkg)
            }

            if (mode == AppOpsManager.MODE_ALLOWED) {
                handleSensorActivation(pkg, AppOpsManager.OPSTR_FINE_LOCATION)
            }
        } catch (e: Exception) { }
    }

    private fun handleSensorActivation(packageName: String, op: String) {
        if (packageName == this.packageName || packageName == "unknown") return

        val isScreenOn = powerManager.isInteractive
        val foregroundPkg = getForegroundPackageName()
        val isForeground = (packageName == foregroundPkg)
        val installSource = getInstallSource(packageName)

        serviceScope.launch {
            val uid = try {
                packageManager.getApplicationInfo(packageName, 0).uid
            } catch (e: Exception) { -1 }

            val initialTx = if (uid != -1) TrafficStats.getUidTxBytes(uid) else 0L
            
            // Monitor network for 20 seconds
            delay(20000)
            
            val finalTx = if (uid != -1) TrafficStats.getUidTxBytes(uid) else 0L
            val bytesUploaded = (finalTx - initialTx).coerceAtLeast(0L)

            var risk = RiskEngine.calculateRisk(packageName, op, isForeground, isScreenOn, installSource)
            
            // Critical upgrade: Upload detected during sensor use
            if (bytesUploaded > 512 * 1024) { // > 500KB
                risk = risk.copy(category = AccessCategory.CRITICAL, score = 1.0)
            }

            try {
                val db = AppDatabase.getDatabase(applicationContext)
                if (db.sensorDao().isTrusted(packageName)) return@launch

                val recentCount = db.sensorDao().getRecentCount(packageName, op, System.currentTimeMillis() - 30000)
                if (recentCount == 0) {
                    db.sensorDao().insertEvent(
                        SensorEvent(
                            packageName = packageName,
                            sensorType = op,
                            isForeground = isForeground,
                            riskScore = risk.score,
                            riskCategory = risk.category.name,
                            isAnomalous = risk.isAnomalous || bytesUploaded > 0,
                            isScreenOff = !isScreenOn,
                            bytesUploaded = bytesUploaded
                        )
                    )
                    
                    if (risk.category != AccessCategory.EXPECTED) {
                        showRiskNotification(packageName, op, risk, bytesUploaded)
                    }
                }
            } catch (e: Exception) { }
        }
    }

    private fun getInstallSource(packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        } catch (e: Exception) { null }
    }

    private fun showRiskNotification(packageName: String, sensor: String, risk: RiskEngine.RiskResult, bytes: Long) {
        val cleanSensor = sensor.replace("android:", "").uppercase()
        val appName = packageName.split(".").last().replaceFirstChar { it.uppercase() }
        
        val content = if (bytes > 0) {
            "DATA LEAK ALERT: $appName sent ${bytes/1024}KB during $cleanSensor use!"
        } else {
            "$appName accessing $cleanSensor while ${if(!powerManager.isInteractive) "Screen is OFF" else "in Background"}"
        }

        val title = when(risk.category) {
            AccessCategory.CRITICAL -> "⚠️ CRITICAL PRIVACY BREACH"
            AccessCategory.UNEXPECTED -> "🚨 Unusual Sensor Activity"
            else -> "🔍 Suspicious Behavior"
        }

        val killIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_KILL_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val killPendingIntent = PendingIntent.getActivity(this, packageName.hashCode() + 1, killIntent, PendingIntent.FLAG_IMMUTABLE)

        val trustIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_TRUST_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val trustPendingIntent = PendingIntent.getActivity(this, packageName.hashCode() + 2, trustIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Terminate App", killPendingIntent)
            .addAction(android.R.drawable.ic_menu_save, "Trust App", trustPendingIntent)
            .build()
        
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(packageName.hashCode(), notification)
    }

    private fun getForegroundPackageName(): String {
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: "unknown"
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "Security Service", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(alertChannelId, "Breach Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setBypassDnd(true)
            })
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sensor Shield Pro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
