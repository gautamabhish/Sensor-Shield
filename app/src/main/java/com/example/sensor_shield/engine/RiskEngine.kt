package com.example.sensor_shield.engine

enum class AccessCategory {
    EXPECTED, SUSPICIOUS, UNEXPECTED, CRITICAL
}

object RiskEngine {

    fun calculateRisk(
        packageName: String,
        sensorType: String,
        isForeground: Boolean,
        isScreenOn: Boolean
    ): RiskResult {

        var score = 0.0

        // 1. Base Sensitivity
        val isHighlySensitive = sensorType.contains("camera") || 
                               sensorType.contains("record_audio") || 
                               sensorType.contains("microphone")
        
        when {
            sensorType.contains("camera") -> score += 0.35
            sensorType.contains("audio") || sensorType.contains("microphone") -> score += 0.35
            sensorType.contains("location") -> score += 0.20
        }

        // 2. Contextual Red Flags
        if (!isForeground) {
            score += 0.40
        }

        if (!isScreenOn) {
            score += 0.20
            if (isHighlySensitive) score += 0.20
        }

        // 3. Trust Adjustment
        score += trustAdjustment(packageName)

        val finalScore = score.coerceIn(0.0, 1.0)

        // 4. Categorization Logic
        val category = when {
            finalScore >= 0.8 || (isHighlySensitive && !isScreenOn && !isForeground) -> AccessCategory.CRITICAL
            finalScore >= 0.65 -> AccessCategory.UNEXPECTED
            finalScore >= 0.4 -> AccessCategory.SUSPICIOUS
            else -> AccessCategory.EXPECTED
        }

        return RiskResult(
            score = finalScore,
            category = category,
            isAnomalous = category == AccessCategory.UNEXPECTED || category == AccessCategory.CRITICAL
        )
    }

    private fun trustAdjustment(packageName: String): Double {
        return when {
            packageName.contains("incallui") || packageName.contains("dialer") || packageName.contains("phone") -> -0.25
            packageName.startsWith("com.android") -> -0.20
            packageName.startsWith("com.google") -> -0.15
            packageName.startsWith("com.whatsapp") -> -0.05
            else -> 0.15 // Unknown apps get a penalty
        }
    }

    data class RiskResult(
        val score: Double,
        val category: AccessCategory,
        val isAnomalous: Boolean
    )
}