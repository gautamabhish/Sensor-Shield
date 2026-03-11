package com.example.sensor_shield.data

data class AppBehaviorProfile(
    val packageName: String,
    val cameraCount: Int,
    val micCount: Int,
    val locationCount: Int,
    val dataAccessCount: Int = 0,
    val backgroundCount: Int,
    val totalCount: Int,
    val foregroundRate: Double,
    val typicalHours: List<Int>,
    val screenOffCount: Int = 0,
    val multiSensorCount: Int = 0,
    val uploadSpikes: Int = 0,
    val statisticalAnomalyCount: Int = 0, // Added this new field
    val totalBytesUploaded: Long = 0,
    val lastRiskCategory: String = "EXPECTED"
)
