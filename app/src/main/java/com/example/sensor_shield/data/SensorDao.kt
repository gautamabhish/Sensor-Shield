package com.example.sensor_shield.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {
    @Insert
    suspend fun insertEvent(event: SensorEvent)

    @Query("SELECT * FROM sensor_events WHERE packageName NOT IN (SELECT packageName FROM trusted_apps) ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<SensorEvent>>

    @Query("SELECT * FROM sensor_events WHERE isAnomalous = 1 AND packageName NOT IN (SELECT packageName FROM trusted_apps) ORDER BY timestamp DESC")
    fun getAnomalousEvents(): Flow<List<SensorEvent>>

    @Query("SELECT COUNT(*) FROM sensor_events WHERE packageName = :packageName AND sensorType = :op AND timestamp > :since")
    suspend fun getRecentCount(packageName: String, op: String, since: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrustedApp(app: TrustedApp)

    @Delete
    suspend fun deleteTrustedApp(app: TrustedApp)

    @Query("SELECT * FROM trusted_apps")
    fun getAllTrustedApps(): Flow<List<TrustedApp>>

    @Query("SELECT EXISTS(SELECT 1 FROM trusted_apps WHERE packageName = :packageName)")
    suspend fun isTrusted(packageName: String): Boolean
}
