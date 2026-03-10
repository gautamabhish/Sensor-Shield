package com.example.sensor_shield.engine

enum class AccessCategory {
    EXPECTED, SUSPICIOUS, UNEXPECTED, CRITICAL
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

        // 1. Base Sensitivity
        val isHighlySensitive = sensorType.contains("camera") || 
                               sensorType.contains("record_audio") || 
                               sensorType.contains("microphone")
        
        when {
            sensorType.contains("camera") -> score += 0.30
            sensorType.contains("audio") || sensorType.contains("microphone") -> score += 0.30
            sensorType.contains("location") -> score += 0.15
        }

        // 2. Contextual Red Flags - Background usage is the primary concern
        if (!isForeground) {
            score += 0.45
        }

        if (!isScreenOn) {
            score += 0.25
            if (isHighlySensitive) score += 0.15
        }

        // 3. Trust Adjustment - Recognizes common legitimate apps
        score += trustAdjustment(packageName)

        // 4. Install Source Adjustment
        score += installSourceAdjustment(installSource)

        val finalScore = score.coerceIn(0.0, 1.0)

        // 5. Categorization Logic
        val category = when {
            finalScore >= 0.85 || (isHighlySensitive && !isScreenOn && !isForeground && trustAdjustment(packageName) > -0.1) -> AccessCategory.CRITICAL
            finalScore >= 0.65 -> AccessCategory.UNEXPECTED
            finalScore >= 0.45 -> AccessCategory.SUSPICIOUS
            else -> AccessCategory.EXPECTED
        }

        return RiskResult(
            score = finalScore,
            category = category,
            isAnomalous = category == AccessCategory.UNEXPECTED || category == AccessCategory.CRITICAL
        )
    }

    private fun trustAdjustment(packageName: String): Double {
        val pkg = packageName.lowercase()
        return when {
            // System and Core Apps (Highest Trust)
            pkg.contains("incallui") || pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("telephony") -> -0.40
            pkg.startsWith("com.android.") || pkg.startsWith("android.system") -> -0.30
            pkg.startsWith("com.google.android.gms") || pkg.startsWith("com.google.android.apps.maps") -> -0.25
            
            // Trusted Social & Communication (Known to use sensors frequently)
            pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" -> -0.20
            pkg.contains("snapchat") -> -0.15
            pkg.contains("instagram") -> -0.15
            pkg.contains("facebook.katana") || pkg.contains("facebook.orca") -> -0.15
            pkg.contains("telegram.messenger") || pkg.contains("org.telegram") -> -0.15
            
            // Productivity & Scanning (Expected Camera use)
            pkg.contains("adobe.scan") || pkg.contains("com.adobe.reader") -> -0.15
            pkg.contains("microsoft.office") || pkg.contains("microsoft.teams") || pkg.contains("skype") -> -0.15
            
            // Multimedia & Conferencing
            pkg.contains("zoom.videomeetings") || pkg.contains("webex") -> -0.15
            pkg.contains("spotify") || pkg.contains("youtube") -> -0.10

            // Default for unknown apps
            else -> 0.10 
        }
    }

    private fun installSourceAdjustment(installSource: String?): Double {
        return when (installSource) {
            "com.android.vending" -> -0.15 // Google Play Store (High Trust)
            "com.amazon.venezia" -> -0.10 // Amazon Appstore
            "com.sec.android.app.samsungapps" -> -0.10 // Samsung Galaxy Store
            null, "" -> 0.15 // Sideloaded (ADB or unknown) - Higher risk
            else -> 0.05 // Other sources (Third party stores)
        }
    }

    data class RiskResult(
        val score: Double,
        val category: AccessCategory,
        val isAnomalous: Boolean
    )
}
