package com.example.sensor_shield.engine

object RiskEngine {

    fun calculateRisk(
        packageName: String,
        sensorType: String,
        isForeground: Boolean,
        isScreenOn: Boolean
    ): RiskResult {

        var score = 0.0

        // Sensor sensitivity
        when (sensorType) {
            "android:camera" -> score += 0.25
            "android:record_audio", "android:microphone" -> score += 0.25
            "android:fine_location", "android:coarse_location" -> score += 0.15
        }

        // Background usage
        if (!isForeground) {
            score += 0.30
        }

        // Screen off suspicious behavior
        if (!isScreenOn && (sensorType.contains("camera") || sensorType.contains("audio"))) {
            score += 0.20
        }

        // Trust level
        val trustScore = trustAdjustment(packageName)
        score += trustScore

        // Fake ML score (placeholder for TFLite later)
        val mlScore = simulateMLInference(packageName, sensorType)
        score += mlScore

        val finalScore = score.coerceIn(0.0, 1.0)

        return RiskResult(
            score = finalScore,
            isAnomalous = finalScore >= 0.7
        )
    }

    private fun trustAdjustment(packageName: String): Double {

        return when {
            packageName.startsWith("com.android") -> -0.15
            packageName.startsWith("com.google") -> -0.10
            packageName.startsWith("com.whatsapp") -> -0.05
            else -> 0.10
        }
    }

    private fun simulateMLInference(
        packageName: String,
        sensorType: String
    ): Double {

        // Stub ML anomaly signal
        if (packageName.contains("system", ignoreCase = true)) {
            return 0.05
        }

        return 0.10
    }

    data class RiskResult(
        val score: Double,
        val isAnomalous: Boolean
    )
}