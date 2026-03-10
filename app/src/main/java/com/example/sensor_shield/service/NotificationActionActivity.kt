package com.example.sensor_shield.service

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast

class NotificationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val pkg = intent.getStringExtra(SensorMonitorService.EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }
        
        val action = intent.action
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(pkg.hashCode())

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        when (action) {
            SensorMonitorService.ACTION_KILL_APP -> {
                Log.w("SensorShield", "Kill action requested for: $pkg")
                try {
                    activityManager.killBackgroundProcesses(pkg)
                } catch (e: Exception) { /* ignore */ }
                
                try {
                    startActivity(settingsIntent)
                    Toast.makeText(this, "Tap 'FORCE STOP' to terminate $pkg", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("SensorShield", "Failed to open settings: ${e.message}")
                }
            }
            SensorMonitorService.ACTION_REVOKE_APP -> {
                Log.w("SensorShield", "Revoke action requested for: $pkg")
                try {
                    startActivity(settingsIntent)
                    Toast.makeText(this, "Go to 'Permissions' to revoke access for $pkg", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("SensorShield", "Failed to open settings: ${e.message}")
                }
            }
        }
        
        finish()
    }
}
