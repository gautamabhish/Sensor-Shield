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

class SensorViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).sensorDao()

    val allEvents: StateFlow<List<SensorEvent>> = dao.getAllEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val anomalousEvents: StateFlow<List<SensorEvent>> = dao.getAnomalousEvents()
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

        val totalPenalty = (criticalCount * 30) + (suspiciousCount * 10)
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
        return events.groupBy { it.packageName }.map { (pkg, appEvents) ->
            var multiSensorCount = 0
            val sorted = appEvents.filter { it.sensorType != "NETWORK" }.sortedBy { it.timestamp }
            for (i in 0 until sorted.size - 1) {
                if (sorted[i+1].timestamp - sorted[i].timestamp < 10000 && sorted[i+1].sensorType != sorted[i].sensorType) {
                    multiSensorCount++
                }
            }

            val totalCount = appEvents.size
            val foregroundCount = appEvents.count { it.isForeground }
            
            val hourHistogram = IntArray(24)
            appEvents.forEach {
                val cal = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                hourHistogram[cal.get(Calendar.HOUR_OF_DAY)]++
            }
            
            val typicalHours = hourHistogram.mapIndexed { hour, count -> hour to count }
                .sortedByDescending { it.second }
                .take(6)
                .filter { it.second > 0 }
                .map { it.first }
                .sorted()

            val worstRisk = when {
                appEvents.any { it.riskCategory == "CRITICAL" } -> "CRITICAL"
                appEvents.any { it.riskCategory == "SUSPICIOUS" } -> "SUSPICIOUS"
                else -> "EXPECTED"
            }

            AppBehaviorProfile(
                packageName = pkg,
                cameraCount = appEvents.count { it.sensorType.contains("camera", true) },
                micCount = appEvents.count { it.sensorType.contains("audio", true) || it.sensorType.contains("mic", true) },
                locationCount = appEvents.count { it.sensorType.contains("location", true) },
                dataAccessCount = appEvents.count { it.sensorType == "NETWORK" || it.bytesUploaded > 0 },
                backgroundCount = totalCount - foregroundCount,
                totalCount = totalCount,
                foregroundRate = if (totalCount > 0) foregroundCount.toDouble() / totalCount else 1.0,
                typicalHours = typicalHours,
                screenOffCount = appEvents.count { it.isScreenOff },
                multiSensorCount = multiSensorCount,
                uploadSpikes = appEvents.count { it.bytesUploaded > 500 * 1024 },
                totalBytesUploaded = appEvents.sumOf { it.bytesUploaded },
                lastRiskCategory = worstRisk
            )
        }.sortedByDescending { it.uploadSpikes * 15 + it.dataAccessCount * 5 + it.screenOffCount * 5 + it.backgroundCount }
    }
}
