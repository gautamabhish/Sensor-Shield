package com.example.sensor_shield.service

import android.app.*
import android.app.AppOpsManager.OnOpActiveChangedListener
import android.app.usage.UsageEvents
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
import com.example.sensor_shield.data.AppDatabase
import com.example.sensor_shield.data.SensorEvent
import com.example.sensor_shield.engine.RiskEngine
import com.example.sensor_shield.engine.AccessCategory
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

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

    private val lastTxBytes = ConcurrentHashMap<String, Long>()
    private val lastSensorAccess = ConcurrentHashMap<String, Long>()
    private val recentlyLogged = ConcurrentHashMap<String, Long>()
    private val watchList = ConcurrentHashMap.newKeySet<String>()

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
        startForeground(notificationId, createNotification("Security Core Active"))

        setupMonitoring()
        startPollingLoop() 
    }

    private fun normalizeSensorName(op: String): String {
        return when {
            op.contains("camera", true) -> "CAMERA"
            op.contains("audio", true) || op.contains("mic", true) -> "MICROPHONE"
            op.contains("location", true) -> "LOCATION"
            else -> op.replace("android:", "").uppercase()
        }
    }

    private fun setupMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && opListener != null) {
            val ops = arrayOf(AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO, AppOpsManager.OPSTR_FINE_LOCATION)
            val executor = ContextCompat.getMainExecutor(this)
            ops.forEach { op -> try { appOpsManager.startWatchingActive(arrayOf(op), executor, opListener) } catch (e: Exception) { } }
        }

        cameraManager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                handleSensorActivation(getForegroundPackageName(), "CAMERA")
            }
        }, null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioManager.registerAudioRecordingCallback(object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                    if (configs.isNotEmpty()) handleSensorActivation(getForegroundPackageName(), "MICROPHONE")
                }
            }, null)
        }
    }

    private fun startPollingLoop() {
        serviceScope.launch {
            while (isActive) {
                val fg = getForegroundPackageName()
                if (fg != "unknown") watchList.add(fg)
                
                checkLocationUsage(fg)
                monitorWatchlistTraffic()
                
                // Cleanup watchlist (keep apps that were foreground or used sensor recently)
                val now = System.currentTimeMillis()
                val iterator = watchList.iterator()
                while (iterator.hasNext()) {
                    val pkg = iterator.next()
                    val lastAccess = lastSensorAccess[pkg] ?: 0L
                    if (pkg != fg && now - lastAccess > 15 * 60 * 1000) {
                        iterator.remove()
                    }
                }
                
                delay(12000) 
            }
        }
    }

    private fun checkLocationUsage(fgPkg: String) {
        if (fgPkg == "unknown" || fgPkg == packageName) return
        try {
            val uid = packageManager.getApplicationInfo(fgPkg, 0).uid
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, uid, fgPkg)
            } else {
                @Suppress("DEPRECATION")
                appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, uid, fgPkg)
            }
            if (mode == AppOpsManager.MODE_ALLOWED) handleSensorActivation(fgPkg, "LOCATION")
        } catch (e: Exception) { }
    }

    private fun getSafeTxBytes(uid: Int): Long {
        val tx = TrafficStats.getUidTxBytes(uid)
        return if (tx == TrafficStats.UNSUPPORTED.toLong()) 0L else tx
    }

    private fun monitorWatchlistTraffic() {
        val now = System.currentTimeMillis()
        val foregroundPkg = getForegroundPackageName()

        watchList.forEach { pkg ->
            try {
                val uid = packageManager.getApplicationInfo(pkg, 0).uid
                val currentTx = getSafeTxBytes(uid)
                if (currentTx <= 0) return@forEach

                val prevTx = lastTxBytes[pkg]
                if (prevTx == null) {
                    lastTxBytes[pkg] = currentTx
                    return@forEach
                }

                val delta = (currentTx - prevTx).coerceAtLeast(0)
                
                if (delta > 0) { // Log even small movements for accurate sums
                    val isForeground = (pkg == foregroundPkg)
                    val lastSensorTime = lastSensorAccess[pkg] ?: 0L
                    
                    if (!isForeground && (now - lastSensorTime) < 20 * 60 * 1000 && delta > 500 * 1024) {
                        logAnomalousEvent(pkg, "DELAYED_EXFILTRATION", delta, false)
                    } else {
                        logAnomalousEvent(pkg, "NETWORK", delta, isForeground)
                    }
                    lastTxBytes[pkg] = currentTx
                }
            } catch (e: Exception) { watchList.remove(pkg) }
        }
    }

    private fun logAnomalousEvent(packageName: String, type: String, bytes: Long, isForeground: Boolean) {
        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                if (db.sensorDao().isTrusted(packageName)) return@launch

                val category = when {
                    type == "DELAYED_EXFILTRATION" -> "CRITICAL"
                    !isForeground && bytes > 2 * 1024 * 1024 -> "SUSPICIOUS"
                    else -> "EXPECTED"
                }

                db.sensorDao().insertEvent(
                    SensorEvent(
                        packageName = packageName,
                        sensorType = type,
                        isForeground = isForeground,
                        riskScore = if (category == "CRITICAL") 0.95 else if (category == "SUSPICIOUS") 0.65 else 0.0,
                        riskCategory = category,
                        isAnomalous = category != "EXPECTED",
                        isScreenOff = !powerManager.isInteractive,
                        bytesUploaded = bytes
                    )
                )
                if (category == "CRITICAL" || category == "SUSPICIOUS") showRiskNotification(packageName, type, bytes)
            } catch (e: Exception) {}
        }
    }

    private fun handleSensorActivation(packageName: String, op: String) {
        if (packageName == this.packageName || packageName == "unknown") return
        
        val normalizedOp = normalizeSensorName(op)
        val dedupeKey = "$packageName:$normalizedOp"
        if (System.currentTimeMillis() - (recentlyLogged[dedupeKey] ?: 0L) < 20000) return
        
        recentlyLogged[dedupeKey] = System.currentTimeMillis()
        lastSensorAccess[packageName] = System.currentTimeMillis()
        watchList.add(packageName)

        serviceScope.launch {
            val uid = try { packageManager.getApplicationInfo(packageName, 0).uid } catch (e: Exception) { -1 }
            val startTx = if (uid != -1) getSafeTxBytes(uid) else 0L
            delay(15000)
            val endTx = if (uid != -1) getSafeTxBytes(uid) else 0L
            val bytesUploaded = (endTx - startTx).coerceAtLeast(0L)

            val isForeground = (packageName == getForegroundPackageName())
            val risk = RiskEngine.calculateRisk(packageName, op, isForeground, powerManager.isInteractive, getInstallSource(packageName))
            val finalCategory = if (bytesUploaded > 300 * 1024) AccessCategory.CRITICAL else risk.category

            try {
                val db = AppDatabase.getDatabase(applicationContext)
                if (db.sensorDao().isTrusted(packageName)) return@launch
                db.sensorDao().insertEvent(
                    SensorEvent(
                        packageName = packageName,
                        sensorType = normalizedOp,
                        isForeground = isForeground,
                        riskScore = if (finalCategory == AccessCategory.CRITICAL) 1.0 else risk.score,
                        riskCategory = finalCategory.name,
                        isAnomalous = finalCategory != AccessCategory.EXPECTED,
                        isScreenOff = !powerManager.isInteractive,
                        bytesUploaded = bytesUploaded
                    )
                )
                if (finalCategory != AccessCategory.EXPECTED) showRiskNotification(packageName, normalizedOp, bytesUploaded)
            } catch (e: Exception) { }
        }
    }

    private fun getForegroundPackageName(): String {
        val end = System.currentTimeMillis()
        val begin = end - 15000
        val events = usageStatsManager.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var lastPkg = "unknown"
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = event.packageName
            }
        }
        return lastPkg
    }

    private fun getInstallSource(packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) packageManager.getInstallSourceInfo(packageName).installingPackageName
            else @Suppress("DEPRECATION") packageManager.getInstallerPackageName(packageName)
        } catch (e: Exception) { null }
    }

    private fun showRiskNotification(packageName: String, sensor: String, bytes: Long) {
        val appName = packageName.split(".").last().replaceFirstChar { it.uppercase() }
        val title = if (sensor == "DELAYED_EXFILTRATION") "⚠️ HARVEST-AND-LEAK DETECTED" else "🛡️ Sensor Shield Alert"
        val content = if (sensor == "DELAYED_EXFILTRATION") "CRITICAL: $appName is uploading ${bytes/1024}KB after earlier sensor use!"
                     else if (bytes > 0) "DATA LEAK: $appName sent ${bytes/1024}KB during $sensor use!" 
                     else "$appName accessing $sensor in background."

        val killIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_KILL_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val killPendingIntent = PendingIntent.getActivity(this, packageName.hashCode() + 1, killIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val trustIntent = Intent(this, NotificationActionActivity::class.java).apply {
            action = ACTION_TRUST_APP
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val trustPendingIntent = PendingIntent.getActivity(this, packageName.hashCode() + 2, trustIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kill App", killPendingIntent)
            .addAction(android.R.drawable.ic_menu_save, "Trust App", trustPendingIntent)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(packageName.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channelId, "Security Service", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(alertChannelId, "Breach Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            })
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sensor Shield Pro").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock).setOngoing(true).build()
    }

    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
