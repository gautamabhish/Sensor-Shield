package com.example.sensor_shield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sensor_shield.data.AppDatabase
import com.example.sensor_shield.data.SensorEvent
import com.example.sensor_shield.data.TrustedApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
        // Only calculate score based on recent events (last 24h) for accuracy
        val recentEvents = events.filter { it.timestamp > System.currentTimeMillis() - 86400000 }
        if (recentEvents.isEmpty()) return 100

        // Penalty-based scoring aligned with RiskEngine thresholds
        val criticalCount = recentEvents.count { it.riskScore >= 0.85 }
        val unexpectedCount = recentEvents.count { it.riskScore >= 0.65 && it.riskScore < 0.85 }
        val suspiciousCount = recentEvents.count { it.riskScore >= 0.45 && it.riskScore < 0.65 }

        val totalPenalty = (criticalCount * 30) + (unexpectedCount * 12) + (suspiciousCount * 3)
        return (100 - totalPenalty).coerceIn(0, 100)
    }

    fun getSuspiciousCount(events: List<SensorEvent>): Int {
        // Total count of events that were flagged as SUSPICIOUS or higher (> 0.45)
        return events.count { it.riskScore >= 0.45 }
    }

    fun getTopSuspects(events: List<SensorEvent>): List<Pair<String, Int>> {
        // Focus on apps with risky behavior in the last 48 hours
        val recentWindow = System.currentTimeMillis() - (48 * 3600000)
        return events.filter { it.timestamp > recentWindow && it.riskScore >= 0.45 }
            .groupBy { it.packageName }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
    }
}
