package com.example.sensor_shield.engine


object RiskEngine {

    fun calculateRisk(
        packageName: String,
        sensorType: String,
        isForeground: Boolean,
        isScreenOn: Boolean
    ): RiskResult {

        var score = 0.0

        when (sensorType) {
            "android:camera" -> score += 0.35
            "android:record_audio", "android:microphone" -> score += 0.35
            "android:fine_location", "android:coarse_location" -> score += 0.20
        }

        if (!isForeground) {
            score += 0.40
        }

        if (!isScreenOn && sensorType.contains("camera")) {
            score += 0.20
        }

        score += trustAdjustment(packageName)

        val finalScore = score.coerceIn(0.0, 1.0)

        return RiskResult(
            score = finalScore,
            isAnomalous = finalScore >= 0.7
        )
    }

    private fun trustAdjustment(packageName: String): Double {

        return when {

            packageName.contains("incallui") -> -0.20
            packageName.contains("dialer") -> -0.20
            packageName.contains("phone") -> -0.20

            packageName.startsWith("com.android") -> -0.15
            packageName.startsWith("com.google") -> -0.10
            packageName.startsWith("com.whatsapp") -> -0.05

            else -> 0.10
        }
    }

    data class RiskResult(
        val score: Double,
        val isAnomalous: Boolean
    )
}