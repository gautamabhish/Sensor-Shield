package com.example.sensor_shield.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_events")
data class SensorEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val sensorType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isForeground: Boolean,
    val riskScore: Double,
    val isAnomalous: Boolean = false
)
