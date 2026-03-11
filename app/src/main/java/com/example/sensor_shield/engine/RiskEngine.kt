package com.example.sensor_shield.engine

enum class AccessCategory {
    EXPECTED, SUSPICIOUS, CRITICAL, STATISTICAL_ANOMALY
}

object RiskEngine {

    fun calculateRisk(
        packageName: String,
        sensorType: String,
        isForeground: Boolean,
        isScreenOn: Boolean,
        installSource: String? = null
    ): RiskResult {

        var score = 0.0

        val isHighlySensitive = sensorType.contains("camera") || 
                               sensorType.contains("record_audio") || 
                               sensorType.contains("microphone")
        
        when {
            sensorType.contains("camera") -> score += 0.30
            sensorType.contains("audio") || sensorType.contains("microphone") -> score += 0.30
            sensorType.contains("location") -> score += 0.15
        }

        if (!isForeground) {
            score += 0.45
        }

        if (!isScreenOn) {
            score += 0.25
            if (isHighlySensitive) score += 0.15
        }

        score += trustAdjustment(packageName)
        score += installSourceAdjustment(installSource)

        val finalScore = score.coerceIn(0.0, 1.0)

        val category = when {
            finalScore >= 0.75 || (isHighlySensitive && !isScreenOn && !isForeground && trustAdjustment(packageName) > -0.1) -> AccessCategory.CRITICAL
            finalScore >= 0.45 -> AccessCategory.SUSPICIOUS
            else -> AccessCategory.EXPECTED
        }

        return RiskResult(
            score = finalScore,
            category = category,
            isAnomalous = category != AccessCategory.EXPECTED
        )
    }

    private fun trustAdjustment(packageName: String): Double {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("incallui") || pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("telephony") -> -0.40
            pkg.startsWith("com.android.") || pkg.startsWith("android.system") -> -0.30
            pkg.startsWith("com.google.android.gms") || pkg.startsWith("com.google.android.apps.maps") -> -0.25
            pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" -> -0.20
            pkg.contains("snapchat") || pkg.contains("instagram") || pkg.contains("facebook") || pkg.contains("telegram") -> -0.15
            pkg.contains("adobe.scan") || pkg.contains("microsoft.office") || pkg.contains("zoom") -> -0.15
            else -> 0.10 
        }
    }

    private fun installSourceAdjustment(installSource: String?): Double {
        return when (installSource) {
            "com.android.vending" -> -0.15
            "com.amazon.venezia", "com.sec.android.app.samsungapps" -> -0.10
            null, "" -> 0.15
            else -> 0.05
        }
    }

    data class RiskResult(
        val score: Double,
        val category: AccessCategory,
        val isAnomalous: Boolean
    )
}
