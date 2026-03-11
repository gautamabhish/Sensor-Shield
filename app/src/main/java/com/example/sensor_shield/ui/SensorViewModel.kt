package com.example.sensor_shield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sensor_shield.data.AppDatabase
import com.example.sensor_shield.data.AppBehaviorProfile
import com.example.sensor_shield.data.SensorEvent
import com.example.sensor_shield.data.TrustedApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.pow
import kotlin.math.sqrt

class SensorViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).sensorDao()

    val allEvents: StateFlow<List<SensorEvent>> = dao.getAllEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val trustedApps: StateFlow<List<TrustedApp>> = dao.getAllTrustedApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun trustApp(packageName: String) {
        viewModelScope.launch {
            dao.insertTrustedApp(TrustedApp(packageName))
        }
    }

    fun untrustApp(packageName: String) {
        viewModelScope.launch {
            dao.deleteTrustedApp(TrustedApp(packageName))
        }
    }

    fun getPrivacyScore(events: List<SensorEvent>): Int {
        val recentEvents = events.filter { it.timestamp > System.currentTimeMillis() - 86400000 }
        if (recentEvents.isEmpty()) return 100

        val criticalCount = recentEvents.count { it.riskCategory == "CRITICAL" }
        val suspiciousCount = recentEvents.count { it.riskCategory == "SUSPICIOUS" }

        val totalPenalty = (criticalCount * 40) + (suspiciousCount * 15)
        return (100 - totalPenalty).coerceIn(0, 100)
    }

    fun getSuspiciousCount(events: List<SensorEvent>): Int {
        return events.count { it.riskCategory != "EXPECTED" }
    }

    fun getTopSuspects(events: List<SensorEvent>): List<Pair<String, Int>> {
        val recentWindow = System.currentTimeMillis() - (48 * 3600000)
        return events.filter { it.timestamp > recentWindow && it.riskCategory != "EXPECTED" }
            .groupBy { it.packageName }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
    }

    fun buildBehaviorProfiles(events: List<SensorEvent>): List<AppBehaviorProfile> {
        val allEventsByPackage = events.groupBy { it.packageName }

        return allEventsByPackage.map { (pkg, appEvents) ->
            val totalBytes = appEvents.sumOf { it.bytesUploaded }
            
            // Calculate proper usage window (hours where > 10% of total app activity happens)
            val hourlyDistribution = IntArray(24)
            appEvents.forEach {
                val hour = Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.HOUR_OF_DAY)
                hourlyDistribution[hour]++
            }
            
            val activeHours = hourlyDistribution.mapIndexed { index, count -> index to count }
                .filter { it.second > (appEvents.size * 0.05) } // Significant hours
                .map { it.first }
                .sorted()

            // Statistical Anomaly logic
            var statisticalAnomalyCount = 0
            val networkEvents = appEvents.filter { it.sensorType == "NETWORK" && it.bytesUploaded > 0 }
            
            if (networkEvents.isNotEmpty()) {
                val mean = networkEvents.map { it.bytesUploaded }.average()
                val stdDev = sqrt(networkEvents.map { (it.bytesUploaded - mean).pow(2) }.average())
                
                // Flag spikes that are 2 standard deviations above mean
                statisticalAnomalyCount = networkEvents.count { it.bytesUploaded > (mean + 2 * stdDev) && it.bytesUploaded > 50 * 1024 }
            }

            val worstRisk = when {
                appEvents.any { it.riskCategory == "CRITICAL" } -> "CRITICAL"
                appEvents.any { it.riskCategory == "SUSPICIOUS" } -> "SUSPICIOUS"
                statisticalAnomalyCount > 0 -> "SUSPICIOUS"
                else -> "EXPECTED"
            }

            AppBehaviorProfile(
                packageName = pkg,
                cameraCount = appEvents.count { it.sensorType == "CAMERA" },
                micCount = appEvents.count { it.sensorType == "MICROPHONE" },
                locationCount = appEvents.count { it.sensorType == "LOCATION" },
                dataAccessCount = appEvents.count { it.sensorType == "NETWORK" || it.bytesUploaded > 0 },
                backgroundCount = appEvents.count { !it.isForeground },
                totalCount = appEvents.size,
                foregroundRate = if (appEvents.isNotEmpty()) appEvents.count { it.isForeground }.toDouble() / appEvents.size else 1.0,
                typicalHours = activeHours,
                screenOffCount = appEvents.count { it.isScreenOff },
                multiSensorCount = 0,
                uploadSpikes = appEvents.count { it.bytesUploaded > 1024 * 1024 }, // 1MB spikes
                statisticalAnomalyCount = statisticalAnomalyCount,
                totalBytesUploaded = totalBytes,
                lastRiskCategory = worstRisk
            )
        }.sortedByDescending { it.totalBytesUploaded }
    }
}
