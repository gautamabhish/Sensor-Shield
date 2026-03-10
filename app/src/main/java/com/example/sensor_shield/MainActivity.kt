package com.example.sensor_shield

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sensor_shield.data.SensorEvent
import com.example.sensor_shield.data.TrustedApp
import com.example.sensor_shield.service.SensorMonitorService
import com.example.sensor_shield.ui.SensorViewModel
import com.example.sensor_shield.ui.theme.SensorShieldTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SensorShieldTheme {
                PermissionScreen {
                    MainContainer()
                }
            }
        }
        startMonitor(this)
    }
}

@Composable
fun PermissionScreen(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permissionsToRequest = remember {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    var allGranted by remember {
        mutableStateOf(
            permissionsToRequest.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            } && hasUsageStatsPermission(context)
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it } && hasUsageStatsPermission(context)) {
            allGranted = true
        }
    }

    if (allGranted) {
        content()
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    Icons.Rounded.Security,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "Security Clearance Required",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Shield Enterprise requires low-level kernel access to monitor hardware vectors.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                
                if (permissionsToRequest.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
                    Button(
                        onClick = { launcher.launch(permissionsToRequest) },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Grant Hardware Access")
                    }
                } else if (!hasUsageStatsPermission(context)) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            })
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Grant Usage Access")
                    }
                }

                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                    Text("Exit Application", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        
        // Check periodically if usage access was granted
        LaunchedEffect(Unit) {
            while (true) {
                if (permissionsToRequest.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } && 
                    hasUsageStatsPermission(context)) {
                    allGranted = true
                    break
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}

fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

@Composable
fun MainContainer(viewModel: SensorViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val events by viewModel.allEvents.collectAsState(initial = emptyList())
    val trustedApps by viewModel.trustedApps.collectAsState(initial = emptyList())
    val privacyScore = viewModel.getPrivacyScore(events)
    val suspects = viewModel.getTopSuspects(events)
    val suspiciousCount = viewModel.getSuspiciousCount(events)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            IndustryNavigationBar(selectedTab) { selectedTab = it }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(300)).togetherWith(fadeOut(tween(300)))
                }, label = "TabSwitch"
            ) { tab ->
                when (tab) {
                    0 -> Dashboard(events, privacyScore, suspiciousCount, suspects) { selectedTab = 1 }
                    1 -> LogScreen(events)
                    2 -> StatsScreen(events)
                    3 -> SettingsScreen(trustedApps, { viewModel.untrustApp(it) })
                }
            }
        }
    }
}

@Composable
fun Dashboard(events: List<SensorEvent>, score: Int, suspiciousCount: Int, suspects: List<Pair<String, Int>>, onSeeAll: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        item { HeaderSection() }
        item { MainScoreCard(score, suspiciousCount) }
        
        if (suspects.isNotEmpty()) {
            item {
                SectionHeader("Vulnerability Audit", "High risk applications")
                SuspectsCard(suspects)
            }
        }

        item {
            SectionHeader("Active Surveillance", "Real-time hardware status")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val recent = events.filter { it.timestamp > System.currentTimeMillis() - 15000 }
                val cameraEvent = recent.find { it.sensorType.contains("camera", true) }
                val micEvent = recent.find { it.sensorType.contains("audio", true) || it.sensorType.contains("mic", true) }
                val locEvent = recent.find { it.sensorType.contains("location", true) }

                StatusTile("Visual", Icons.Rounded.CameraAlt, cameraEvent != null, cameraEvent?.packageName, Modifier.weight(1f))
                StatusTile("Acoustic", Icons.Rounded.Mic, micEvent != null, micEvent?.packageName, Modifier.weight(1f))
                StatusTile("Geospatial", Icons.Rounded.LocationOn, locEvent != null, locEvent?.packageName, Modifier.weight(1f))
            }
        }

        item {
            SectionHeader("Incidence Timeline", "Last 48 hours activity")
            HeatmapGrid(events)
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                SectionHeader("Security Ledger", "Recent access attempts")
                Text(
                    "View Logs",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { onSeeAll() }.padding(bottom = 8.dp)
                )
            }
            RecentList(events)
        }
    }
}

@Composable
fun HeaderSection() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Sensor Shield",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp
            )
            Text(
                "Secure Kernel Active",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
fun MainScoreCard(score: Int, suspiciousCount: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(90.dp)) {
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round
                )
                CircularProgressIndicator(
                    progress = { score / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    color = if (score > 80) MaterialTheme.colorScheme.tertiary else if (score > 50) Color(0xFFD29922) else MaterialTheme.colorScheme.error,
                    strokeCap = StrokeCap.Round
                )
                Text("${score}%", fontWeight = FontWeight.Black, fontSize = 22.sp)
            }
            Spacer(Modifier.width(24.dp))
            Column {
                Text("Integrity Score", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (score > 80) "Environment Secure" else "Breach Detected",
                    color = if (score > 80) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "$suspiciousCount suspicious patterns analyzed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SuspectsCard(suspects: List<Pair<String, Int>>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            suspects.forEachIndexed { index, (pkg, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.error.copy(0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(pkg.split(".").last().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text("$count", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
                }
                if (index < suspects.size - 1) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun StatusTile(label: String, icon: ImageVector, active: Boolean, packageName: String? = null, modifier: Modifier) {
    val color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (active) color.copy(0.1f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) color.copy(0.5f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = if (active) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            if (active && packageName != null) {
                Text(
                    packageName.split(".").last(),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    if (active) "ACTIVE" else "SECURE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) color else MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun HeatmapGrid(events: List<SensorEvent>) {
    val now = System.currentTimeMillis()
    val buckets = remember(events) {
        List(48) { index ->
            val start = now - (48 - index) * 3600000
            val end = start + 3600000
            events.count { it.timestamp in start..end }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(12) { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { row ->
                            val count = buckets[col * 4 + row]
                            val color = when {
                                count > 5 -> MaterialTheme.colorScheme.error
                                count > 2 -> Color(0xFFD29922)
                                count > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                            Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(color))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Chronological Data", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(4.dp))
                    Text("Critical", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun RecentList(events: List<SensorEvent>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        events.take(4).forEach { event ->
            LogItem(event)
        }
    }
}

@Composable
fun LogScreen(events: List<SensorEvent>) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp)) {
            SectionHeader("Audit Trail", "Comprehensive security ledger")
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(events.sortedByDescending { it.timestamp }) { LogItem(it) }
        }
    }
}

@Composable
fun LogItem(event: SensorEvent) {
    val color = when(event.riskCategory) {
        "CRITICAL" -> MaterialTheme.colorScheme.error
        "UNEXPECTED", "SUSPICIOUS" -> Color(0xFFD29922)
        else -> MaterialTheme.colorScheme.tertiary
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(color.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
                val icon = when {
                    event.sensorType.contains("camera") -> Icons.Rounded.CameraAlt
                    event.sensorType.contains("audio") || event.sensorType.contains("mic") -> Icons.Rounded.Mic
                    else -> Icons.Rounded.LocationOn
                }
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(event.packageName.split(".").last(), fontWeight = FontWeight.Bold)
                Text(
                    "${if(event.isForeground) "Foreground" else "Background"} process",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(event.riskCategory, color = color, fontWeight = FontWeight.Black, fontSize = 10.sp)
                Text(
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatsScreen(events: List<SensorEvent>) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp)) {
        SectionHeader("Threat Analytics", "Vector distribution analysis")
        Spacer(Modifier.height(24.dp))
        
        val camera = events.count { it.sensorType.contains("camera") }
        val mic = events.count { it.sensorType.contains("audio") || it.sensorType.contains("mic") }
        val loc = events.count { it.sensorType.contains("location") }
        val total = (camera + mic + loc).coerceAtLeast(1)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AnalyticsBar("Visual Access", camera, total, MaterialTheme.colorScheme.primary)
                AnalyticsBar("Acoustic Access", mic, total, MaterialTheme.colorScheme.error)
                AnalyticsBar("Geospatial Access", loc, total, MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
fun AnalyticsBar(label: String, count: Int, total: Int, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("$count events", style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { count.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
fun SettingsScreen(trustedApps: List<TrustedApp>, onUntrust: (String) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            SectionHeader("Governance", "System-level privacy control")
            Spacer(Modifier.height(24.dp))
        }
        
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GovernanceTile("Usage Statistics", "Access kernel-level activity logs", Icons.Rounded.Analytics) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                GovernanceTile("Power Management", "Bypass battery restrictions for monitor", Icons.Rounded.BatteryChargingFull) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                GovernanceTile("Alert Protocols", "Configure real-time threat alerts", Icons.Rounded.NotificationsActive) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                    context.startActivity(intent)
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        item {
            SectionHeader("Whitelist Authority", "Exempted applications")
            Spacer(Modifier.height(16.dp))
        }

        if (trustedApps.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        "No apps currently exempted from monitoring.",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(trustedApps) { app ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.packageName.split(".").last().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                            Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onUntrust(app.packageName) }) {
                            Icon(Icons.Rounded.Delete, "Untrust", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GovernanceTile(title: String, desc: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun IndustryNavigationBar(selected: Int, onSelected: (Int) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        val items = listOf(
            Triple(Icons.Rounded.Dashboard, "Console", 0),
            Triple(Icons.AutoMirrored.Rounded.ListAlt, "Ledger", 1),
            Triple(Icons.Rounded.PieChart, "Analytics", 2),
            Triple(Icons.Rounded.AdminPanelSettings, "Admin", 3)
        )
        items.forEach { (icon, label, index) ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelected(index) },
                icon = { Icon(icon, null) },
                label = { Text(label, fontWeight = FontWeight.Bold, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = Color.Transparent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                )
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

private fun startMonitor(context: Context) {
    val intent = Intent(context, SensorMonitorService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}
