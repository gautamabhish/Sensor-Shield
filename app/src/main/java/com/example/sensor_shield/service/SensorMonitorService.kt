package com.example.sensor_shield.service

import android.app.*
import android.app.AppOpsManager.OnOpActiveChangedListener
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.ConnectivityManager
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
    private lateinit var networkStatsManager: NetworkStatsManager
    private lateinit var audioManager: AudioManager
    private lateinit var cameraManager: CameraManager
    private lateinit var appOpsManager: AppOpsManager

    private val lastTxBytes = ConcurrentHashMap<String, Long>()
    private val lastSensorAccess = ConcurrentHashMap<String, Long>()
    private val recentlyLogged = ConcurrentHashMap<String, Long>()
    private val watchList = ConcurrentHashMap.newKeySet<String>()

    private val opListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        OnOpActiveChangedListener { op, _, packageName, active ->
            if (active && packageName != "unknown") handleSensorActivation(packageName, op)
        }
    } else null

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        networkStatsManager = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        createNotificationChannels()
        startForeground(notificationId, createNotification("Security Intelligence Active"))

        setupMonitoring()
        startPollingLoop() 
    }

    private fun setupMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && opListener != null) {
            val ops = arrayOf(AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO, AppOpsManager.OPSTR_FINE_LOCATION)
            ops.forEach { op -> try { appOpsManager.startWatchingActive(arrayOf(op), ContextCompat.getMainExecutor(this), opListener) } catch (e: Exception) { } }
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
                
                monitorWatchlistTraffic()
                
                // Cleanup watchlist (apps inactive for 15 mins)
                val now = System.currentTimeMillis()
                watchList.removeIf { pkg -> (now - (lastSensorAccess[pkg] ?: 0L)) > 15 * 60 * 1000 && pkg != fg }
                
                delay(10000) 
            }
        }
    }

    private fun getUidTxBytes(uid: Int): Long {
        return try {
            val bucket = networkStatsManager.querySummaryForUser(ConnectivityManager.TYPE_WIFI, null, 0, System.currentTimeMillis())
            var total = 0L
            val entry = NetworkStats.Bucket()
            while (bucket.hasNextBucket()) {
                bucket.getNextBucket(entry)
                if (entry.uid == uid) total += entry.txBytes
            }
            // Add Mobile data as well
            val mobileBucket = networkStatsManager.querySummaryForUser(ConnectivityManager.TYPE_MOBILE, null, 0, System.currentTimeMillis())
            while (mobileBucket.hasNextBucket()) {
                mobileBucket.getNextBucket(entry)
                if (entry.uid == uid) total += entry.txBytes
            }
            total
        } catch (e: Exception) { 0L }
    }

    private fun monitorWatchlistTraffic() {
        val foregroundPkg = getForegroundPackageName()
        val now = System.currentTimeMillis()

        watchList.forEach { pkg ->
            try {
                val uid = packageManager.getApplicationInfo(pkg, 0).uid
                val currentTx = getUidTxBytes(uid)
                val prevTx = lastTxBytes[pkg] ?: run {
                    lastTxBytes[pkg] = currentTx
                    return@forEach
                }

                val delta = currentTx - prevTx
                if (delta > 1024) { // Log any upload > 1KB
                    val lastSensorTime = lastSensorAccess[pkg] ?: 0L
                    val isDelayedExfil = (now - lastSensorTime) < 20 * 60 * 1000 && delta > 500 * 1024
                    
                    logEvent(pkg, if (isDelayedExfil) "DELAYED_EXFILTRATION" else "NETWORK", delta, pkg == foregroundPkg)
                    lastTxBytes[pkg] = currentTx
                }
            } catch (e: Exception) { watchList.remove(pkg) }
        }
    }

    private fun handleSensorActivation(packageName: String, op: String) {
        if (packageName == this.packageName || packageName == "unknown") return
        val normalized = when {
            op.contains("camera", true) -> "CAMERA"
            op.contains("audio", true) || op.contains("mic", true) -> "MICROPHONE"
            else -> "LOCATION"
        }
        
        val key = "$packageName:$normalized"
        if (System.currentTimeMillis() - (recentlyLogged[key] ?: 0L) < 15000) return
        recentlyLogged[key] = System.currentTimeMillis()
        lastSensorAccess[packageName] = System.currentTimeMillis()
        watchList.add(packageName)

        serviceScope.launch {
            val uid = try { packageManager.getApplicationInfo(packageName, 0).uid } catch (e: Exception) { -1 }
            val startTx = if (uid != -1) getUidTxBytes(uid) else 0L
            delay(12000)
            val endTx = if (uid != -1) getUidTxBytes(uid) else 0L
            val bytes = (endTx - startTx).coerceAtLeast(0L)

            val isFg = (packageName == getForegroundPackageName())
            val risk = RiskEngine.calculateRisk(packageName, normalized, isFg, powerManager.isInteractive)
            
            logEvent(packageName, normalized, bytes, isFg, risk.category.name)
            if (risk.category != AccessCategory.EXPECTED) showAlert(packageName, normalized, bytes)
        }
    }

    private fun logEvent(pkg: String, type: String, bytes: Long, isFg: Boolean, category: String? = null) {
        serviceScope.launch {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                if (db.sensorDao().isTrusted(pkg)) return@launch
                
                db.sensorDao().insertEvent(SensorEvent(
                    packageName = pkg,
                    sensorType = type,
                    isForeground = isFg,
                    riskScore = if (category == "CRITICAL") 0.9 else if (category == "SUSPICIOUS") 0.5 else 0.0,
                    riskCategory = category ?: "EXPECTED",
                    isAnomalous = category != "EXPECTED" && category != null,
                    isScreenOff = !powerManager.isInteractive,
                    bytesUploaded = bytes
                ))
            } catch (e: Exception) {}
        }
    }

    private fun getForegroundPackageName(): String {
        val end = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(end - 10000, end)
        val event = UsageEvents.Event()
        var lastPkg = "unknown"
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastPkg = event.packageName
        }
        return lastPkg
    }

    private fun showAlert(pkg: String, sensor: String, bytes: Long) {
        val appName = pkg.split(".").last().replaceFirstChar { it.uppercase() }
        val content = if (bytes > 0) "Critical Leak: $appName sent ${formatBytes(bytes)} during $sensor use!" 
                     else "$appName is accessing $sensor in the background."

        val killIntent = Intent(this, NotificationActionActivity::class.java).apply { 
            action = ACTION_KILL_APP
            putExtra(EXTRA_PACKAGE_NAME, pkg)
        }
        val killPending = PendingIntent.getActivity(this, pkg.hashCode(), killIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, alertChannelId)
            .setContentTitle("🛡️ Sensor Shield Alert")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kill App", killPending)
            .setAutoCancel(true).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(pkg.hashCode(), notification)
    }

    private fun formatBytes(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes/1048576f) else "${bytes/1024} KB"

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(channelId, "Monitor", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(alertChannelId, "Alerts", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }

    private fun createNotification(txt: String) = NotificationCompat.Builder(this, channelId)
        .setContentTitle("Sensor Shield Pro").setContentText(txt)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock).setOngoing(true).build()

    override fun onBind(intent: Intent?) = null
}
