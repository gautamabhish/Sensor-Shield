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
        val unexpectedCount = recentEvents.count { it.riskCategory == "UNEXPECTED" }
        val suspiciousCount = recentEvents.count { it.riskCategory == "SUSPICIOUS" }

        val totalPenalty = (criticalCount * 30) + (unexpectedCount * 12) + (suspiciousCount * 3)
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
            val sorted = appEvents.sortedBy { it.timestamp }
            for (i in 0 until sorted.size - 1) {
                if (sorted[i+1].timestamp - sorted[i].timestamp < 10000 && sorted[i+1].sensorType != sorted[i].sensorType) {
                    multiSensorCount++
                }
            }

            AppBehaviorProfile(
                packageName = pkg,
                cameraCount = appEvents.count { it.sensorType.contains("camera", true) },
                micCount = appEvents.count { it.sensorType.contains("audio", true) || it.sensorType.contains("mic", true) },
                locationCount = appEvents.count { it.sensorType.contains("location", true) },
                backgroundCount = appEvents.count { !it.isForeground },
                screenOffCount = appEvents.count { it.isScreenOff },
                multiSensorCount = multiSensorCount,
                uploadSpikes = appEvents.count { it.bytesUploaded > 500 * 1024 },
                totalBytesUploaded = appEvents.sumOf { it.bytesUploaded },
                lastRiskCategory = appEvents.maxByOrNull { it.timestamp }?.riskCategory ?: "EXPECTED"
            )
        }.sortedByDescending { it.uploadSpikes * 10 + it.screenOffCount * 5 + it.backgroundCount }
    }
}
