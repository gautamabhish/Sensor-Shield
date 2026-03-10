package com.example.sensor_shield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sensor_shield.data.AppDatabase
import com.example.sensor_shield.data.SensorEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SensorViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).sensorDao()

    val allEvents: StateFlow<List<SensorEvent>> = dao.getAllEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val anomalousEvents: StateFlow<List<SensorEvent>> = dao.getAnomalousEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getPrivacyScore(events: List<SensorEvent>): Int {
        if (events.isEmpty()) return 100
        val avgRisk = events.map { it.riskScore }.average()
        return (100 - (avgRisk * 100)).toInt().coerceIn(0, 100)
    }
}
