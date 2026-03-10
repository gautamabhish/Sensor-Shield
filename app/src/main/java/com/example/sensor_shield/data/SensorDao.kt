package com.example.sensor_shield.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {
    @Insert
    suspend fun insertEvent(event: SensorEvent)

    @Query("SELECT * FROM sensor_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<SensorEvent>>

    @Query("SELECT * FROM sensor_events WHERE isAnomalous = 1 ORDER BY timestamp DESC")
    fun getAnomalousEvents(): Flow<List<SensorEvent>>

    @Query("SELECT COUNT(*) FROM sensor_events WHERE packageName = :packageName AND sensorType = :op AND timestamp > :since")
    suspend fun getRecentCount(packageName: String, op: String, since: Long): Int
}
