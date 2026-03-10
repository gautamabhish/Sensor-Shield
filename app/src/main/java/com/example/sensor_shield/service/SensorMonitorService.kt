package com.example.sensor_shield.service

import android.app.*
import android.app.AppOpsManager.OnOpActiveChangedListener
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
        const val ACTION_REVOKE_APP = "com.example.sensor_shield.ACTION_REVOKE_APP"
        const val ACTION_TRUST_APP = "com.example.sensor_shield.ACTION_TRUST_APP"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }

    private val TAG = "SensorShield"
    private val channelId = "SensorMonitorChannel"
    private val alertChannelId = "SensorAlertChannel"
    private val notificationId = 1001 // Unique ID for foreground service

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
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        createNotificationChannels()
        val notification = createNotification("Privacy Guard is active")

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
        } catch (e: Exception) { /* ignore */ }
    }

    private fun handleSensorActivation(packageName: String, op: String) {
        if (packageName == this.packageName || packageName == "unknown") return

        val isScreenOn = powerManager.isInteractive
        val foregroundPkg = getForegroundPackageName()
        val isForeground = (packageName == foregroundPkg)
        val installSource = getInstallSource(packageName)

        val risk = RiskEngine.calculateRisk(packageName, op, isForeground, isScreenOn, installSource)

        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                
                if (db.sensorDao().isTrusted(packageName)) {
                    Log.i(TAG, "SKIP: $packageName is trusted.")
                    return@launch
                }

                val recentCount = db.sensorDao().getRecentCount(packageName, op, System.currentTimeMillis() - 20000)
                
                if (recentCount == 0) {
                    db.sensorDao().insertEvent(
                        SensorEvent(
                            packageName = packageName,
                            sensorType = op,
                            isForeground = isForeground,
                            riskScore = risk.score,
                            riskCategory = risk.category.name,
                            isAnomalous = risk.isAnomalous
                        )
                    )
                    
                    if (risk.category != AccessCategory.EXPECTED) {
                        showRiskNotification(packageName, op, risk)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Database error: ${e.message}")
            }
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
        } catch (e: Exception) {
            null
        }
    }

    private fun showRiskNotification(packageName: String, sensor: String, risk: RiskEngine.RiskResult) {
        val cleanSensor = sensor.replace("android:", "").uppercase()
        val appName = packageName.split(".").last().replaceFirstChar { it.uppercase() }
        
        val (title, priority) = when(risk.category) {
            AccessCategory.CRITICAL -> "⚠️ CRITICAL PRIVACY LEAK" to NotificationCompat.PRIORITY_MAX
            AccessCategory.UNEXPECTED -> "🚨 Unexpected Access" to NotificationCompat.PRIORITY_HIGH
            else -> "🔍 Suspicious Activity" to NotificationCompat.PRIORITY_DEFAULT
        }

        val killIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_KILL_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val killPendingIntent = PendingIntent.getActivity(
            this, packageName.hashCode() + 1, killIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val trustIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_TRUST_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val trustPendingIntent = PendingIntent.getActivity(
            this, packageName.hashCode() + 2, trustIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle(title)
            .setContentText("$appName is accessing $cleanSensor in the background!")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kill App", killPendingIntent)
            .addAction(android.R.drawable.ic_menu_save, "Trust (No Alert)", trustPendingIntent)
            .build()
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(packageName.hashCode(), notification)
    }

    private fun getForegroundPackageName(): String {
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 10000, time)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName ?: "unknown"
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val monitorChannel = NotificationChannel(channelId, "Sensor Monitoring Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that the privacy guard is active in the background"
            }
            manager.createNotificationChannel(monitorChannel)

            val alertChannel = NotificationChannel(alertChannelId, "Privacy Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "High priority alerts for suspicious sensor access"
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
                setBypassDnd(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sensor Shield Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
