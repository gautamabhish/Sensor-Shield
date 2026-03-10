package com.example.sensor_shield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sensor_shield.data.AppDatabase
import com.example.sensor_shield.data.SensorEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SensorViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).sensorDao()

    val allEvents: StateFlow<List<SensorEvent>> = dao.getAllEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val anomalousEvents: StateFlow<List<SensorEvent>> = dao.getAnomalousEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getPrivacyScore(events: List<SensorEvent>): Int {
        // Only calculate score based on recent events (last 24h) for accuracy
        val recentEvents = events.filter { it.timestamp > System.currentTimeMillis() - 86400000 }
        if (recentEvents.isEmpty()) return 100

        // Penalty-based scoring is more accurate for security than simple average
        val criticalCount = recentEvents.count { it.riskScore >= 0.75 }
        val unexpectedCount = recentEvents.count { it.riskScore >= 0.5 && it.riskScore < 0.75 }
        val suspiciousCount = recentEvents.count { it.riskScore >= 0.3 && it.riskScore < 0.5 }

        val totalPenalty = (criticalCount * 25) + (unexpectedCount * 10) + (suspiciousCount * 2)
        return (100 - totalPenalty).coerceIn(0, 100)
    }

    fun getSuspiciousCount(events: List<SensorEvent>): Int {
        // Total count of events that were flagged as SUSPICIOUS, UNEXPECTED, or CRITICAL
        return events.count { it.riskScore >= 0.3 }
    }

    fun getTopSuspects(events: List<SensorEvent>): List<Pair<String, Int>> {
        // Focus on apps with risky behavior in the last 48 hours
        val recentWindow = System.currentTimeMillis() - (48 * 3600000)
        return events.filter { it.timestamp > recentWindow && it.riskScore >= 0.4 }
            .groupBy { it.packageName }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
            .take(3)
    }
}
