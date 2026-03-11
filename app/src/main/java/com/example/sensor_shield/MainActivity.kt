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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sensor_shield.data.AppBehaviorProfile
import com.example.sensor_shield.data.SensorEvent
import com.example.sensor_shield.data.TrustedApp
import com.example.sensor_shield.service.SensorMonitorService
import com.example.sensor_shield.ui.SensorViewModel
import com.example.sensor_shield.ui.theme.SensorShieldTheme
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

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

    var hardwareGranted by remember {
        mutableStateOf(permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    
    var usageGranted by remember {
        mutableStateOf(hasUsageStatsPermission(context))
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hardwareGranted = result.values.all { it }
    }

    if (hardwareGranted && usageGranted) {
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
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                
                if (!hardwareGranted) {
                    Button(
                        onClick = { launcher.launch(permissionsToRequest) },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Grant Hardware Access")
                    }
                } else if (!usageGranted) {
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
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
        
        LaunchedEffect(Unit) {
            while (true) {
                hardwareGranted = permissionsToRequest.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                usageGranted = hasUsageStatsPermission(context)
                if (hardwareGranted && usageGranted) break
                delay(1000)
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
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    
    val events by viewModel.allEvents.collectAsState(initial = emptyList())
    val trustedApps by viewModel.trustedApps.collectAsState(initial = emptyList())
    val privacyScore = viewModel.getPrivacyScore(events)
    val suspects = viewModel.getTopSuspects(events)
    val suspiciousCount = viewModel.getSuspiciousCount(events)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            IndustryNavigationBar(selectedTab) { 
                selectedTab = it 
                if (it != 2) selectedPackage = null // Clear selection if leaving behavior tab
            }
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
                    0 -> Dashboard(
                        events, 
                        privacyScore, 
                        suspiciousCount, 
                        suspects, 
                        onSeeAll = { selectedTab = 1 },
                        onAppClick = { pkg ->
                            selectedPackage = pkg
                            selectedTab = 2
                        }
                    )
                    1 -> LogScreen(events)
                    2 -> BehaviorAnalysisScreen(
                        viewModel.buildBehaviorProfiles(events), 
                        events,
                        selectedPackage,
                        onTrust = { viewModel.trustApp(it) },
                        onPackageSelected = { selectedPackage = it }
                    )
                    3 -> SettingsScreen(trustedApps) { viewModel.untrustApp(it) }
                }
            }
        }
    }
}

@Composable
fun Dashboard(
    events: List<SensorEvent>, 
    score: Int, 
    suspiciousCount: Int, 
    suspects: List<Pair<String, Int>>, 
    onSeeAll: () -> Unit,
    onAppClick: (String) -> Unit
) {
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
                SuspectsCard(suspects, onAppClick)
            }
        }

        item {
            SectionHeader("Active Surveillance", "Real-time hardware status")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val recent = events.filter { it.timestamp > System.currentTimeMillis() - 15000 }
                val cameraEvent = recent.find { it.sensorType.contains("camera", true) }
                val micEvent = recent.find { it.sensorType.contains("audio", true) || it.sensorType.contains("mic", true) }
                val locEvent = recent.find { it.sensorType.contains("location", true) }
                val networkEvent = recent.find { it.sensorType.contains("NETWORK", true) || it.sensorType.contains("EXFILTRATION", true) || it.bytesUploaded > 500 * 1024 }

                StatusTile("Visual", Icons.Rounded.CameraAlt, cameraEvent != null, cameraEvent?.packageName, Modifier.weight(1f))
                StatusTile("Acoustic", Icons.Rounded.Mic, micEvent != null, micEvent?.packageName, Modifier.weight(1f))
                StatusTile("Geospatial", Icons.Rounded.LocationOn, locEvent != null, locEvent?.packageName, Modifier.weight(1f))
                StatusTile("Network", Icons.Rounded.NetworkCheck, networkEvent != null, networkEvent?.packageName, Modifier.weight(1f))
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
fun SuspectsCard(suspects: List<Pair<String, Int>>, onAppClick: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            suspects.forEachIndexed { index, (pkg, count) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onAppClick(pkg) },
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
        border = BorderStroke(1.dp, if (active) color.copy(0.5f) else MaterialTheme.colorScheme.outlineVariant)
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
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    val buckets = remember(events) {
        List(48) { index ->
            val start = now - (48 - index) * 3600000
            val end = start + 3600000
            val bucketEvents = events.filter { it.timestamp in start..end }
            BucketData(start = start, end = end, events = bucketEvents)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(12) { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { row ->
                            val index = col * 4 + row
                            val bucket = buckets[index]
                            val count = bucket.events.size
                            val color = when {
                                count > 5 -> MaterialTheme.colorScheme.error
                                count > 2 -> Color(0xFFD29922)
                                count > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                            val isSelected = selectedIndex == index
                            Box(
                                Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(color)
                                    .border(if (isSelected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                    .clickable {
                                        if (selectedIndex == index) {
                                            selectedIndex = null
                                            isExpanded = false
                                        } else {
                                            selectedIndex = index
                                            isExpanded = false
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            selectedIndex?.let { index ->
                val bucket = buckets[index]
                val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                val startTime = formatter.format(Date(bucket.start))
                val endTime = formatter.format(Date(bucket.end))

                Column {
                    Text("$startTime - $endTime", fontWeight = FontWeight.Bold)
                    Text("${bucket.events.size} sensor events", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))

                    val displayEvents = if (isExpanded) bucket.events else bucket.events.take(3)
                    displayEvents.forEach {
                        Text("• ${it.packageName.substringAfterLast(".")} (${it.sensorType})", style = MaterialTheme.typography.labelSmall)
                    }

                    if (bucket.events.size > 3 && !isExpanded) {
                        Text(
                            "+ ${bucket.events.size - 3} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { isExpanded = true }.padding(vertical = 4.dp),
                            fontWeight = FontWeight.Bold
                        )
                    } else if (isExpanded) {
                        Text(
                            "Show less",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { isExpanded = false }.padding(vertical = 4.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

data class BucketData(
    val start: Long,
    val end: Long,
    val events: List<SensorEvent>
)

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
    val context = LocalContext.current
    val color = when(event.riskCategory) {
        "CRITICAL" -> MaterialTheme.colorScheme.error
        "UNEXPECTED", "SUSPICIOUS" -> Color(0xFFD29922)
        else -> MaterialTheme.colorScheme.tertiary
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", event.packageName, null)
            }
            context.startActivity(intent)
        },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(color.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
                val icon = when {
                    event.sensorType.contains("camera") -> Icons.Rounded.CameraAlt
                    event.sensorType.contains("audio") || event.sensorType.contains("mic") -> Icons.Rounded.Mic
                    event.sensorType.contains("EXFILTRATION") -> Icons.Rounded.LeakAdd
                    event.sensorType.contains("NETWORK") -> Icons.Rounded.NetworkCheck
                    else -> Icons.Rounded.LocationOn
                }
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(event.packageName.split(".").last(), fontWeight = FontWeight.Bold)
                val detail = when {
                    event.sensorType == "DELAYED_EXFILTRATION" -> "Harvest-then-Leak (${formatBytes(event.bytesUploaded)})"
                    event.sensorType == "NETWORK_SPIKE" -> "Sudden Background Upload (${formatBytes(event.bytesUploaded)})"
                    event.bytesUploaded > 0 -> "${if(event.isForeground) "Foreground" else "Background"} + ${formatBytes(event.bytesUploaded)} sent"
                    else -> "${if(event.isForeground) "Foreground" else "Background"} process"
                }
                Text(
                    detail,
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

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

@Composable
fun BehaviorAnalysisScreen(
    profiles: List<AppBehaviorProfile>, 
    events: List<SensorEvent>,
    selectedPackage: String?,
    onTrust: (String) -> Unit,
    onPackageSelected: (String?) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }

    if (selectedPackage != null) {
        val profile = profiles.find { it.packageName == selectedPackage }
        val appEvents = events.filter { it.packageName == selectedPackage }
        if (profile != null) {
            AppBehaviorDetailScreen(profile, appEvents, onTrust) { onPackageSelected(null) }
        } else {
            onPackageSelected(null)
        }
        return
    }

    val filteredProfiles = profiles.filter { profile ->
        val risk = profile.lastRiskCategory
        val matchesFilter = filter == "ALL" || (filter == risk)
        val matchesSearch = profile.packageName.contains(searchQuery, ignoreCase = true)
        matchesFilter && matchesSearch
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp)) {
            SectionHeader("Behavior Analysis", "App profiling and pattern detection")
            
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search application") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
            
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL", "EXPECTED", "SUSPICIOUS", "CRITICAL").forEach { type ->
                    FilterChip(
                        selected = filter == type,
                        onClick = { filter = type },
                        label = { Text(type, fontSize = 12.sp) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(filteredProfiles) { profile ->
                BehaviorProfileCard(profile) { onPackageSelected(profile.packageName) }
            }
        }
    }
}

@Composable
fun AppBehaviorDetailScreen(profile: AppBehaviorProfile, events: List<SensorEvent>, onTrust: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(profile.packageName.split(".").last().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(profile.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(24.dp)) {
                        val activeHours = profile.typicalHours
                        val windowText = if (activeHours.isNotEmpty()) {
                            val start = activeHours.first()
                            val end = activeHours.last()
                            if (start == end) "$start:00" else "$start:00 – $end:00"
                        } else {
                            "Baseline under development"
                        }
                        
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("High-Activity Window", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(windowText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Foreground Rate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${(profile.foregroundRate * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        
                        Spacer(Modifier.height(20.dp))
                        
                        Text("Usage Profile", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
                            Box(Modifier.fillMaxHeight().weight(profile.foregroundRate.toFloat().coerceAtLeast(0.01f)).background(MaterialTheme.colorScheme.primary))
                            Box(Modifier.fillMaxHeight().weight((1.0 - profile.foregroundRate).toFloat().coerceAtLeast(0.01f)).background(MaterialTheme.colorScheme.error.copy(0.5f)))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Foreground", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text("Background", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        
                        Spacer(Modifier.height(20.dp))
                        Text("Cumulative Network Traffic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatBytes(profile.totalBytesUploaded), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color(0xFF9C27B0))
                    }
                }
            }

            item {
                SectionHeader("Average daily vector access", "")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        val firstEvent = events.minByOrNull { it.timestamp }?.timestamp ?: System.currentTimeMillis()
                        val days = ((System.currentTimeMillis() - firstEvent) / 86400000.0).coerceAtLeast(1.0)
                        
                        PatternSummaryBar("Camera usage", profile.cameraCount, days, MaterialTheme.colorScheme.primary)
                        PatternSummaryBar("Mic usage", profile.micCount, days, MaterialTheme.colorScheme.error)
                        PatternSummaryBar("Location usage", profile.locationCount, days, MaterialTheme.colorScheme.tertiary)
                        PatternSummaryBar("Data access", profile.dataAccessCount, days, Color(0xFF9C27B0))
                    }
                }
            }

            item {
                SectionHeader("Sensor activity over time (Last 24h)", "")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    TimeSeriesGraph(events)
                }
            }

            item {
                SectionHeader("Anomaly Detection", "Baseline deviations detected")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                        
                        // Statistical Anomaly Alerts
                        if (profile.statisticalAnomalyCount > 0) {
                            PatternAlert("Network Volatility", "Detected ${profile.statisticalAnomalyCount} instances of statistically improbable data spikes.")
                        }

                        if (profile.typicalHours.isNotEmpty() && !profile.typicalHours.contains(currentHour)) {
                            PatternAlert("Atypical Access", "This application is currently accessing sensors outside its statistically normal usage window.")
                        }
                        
                        if (profile.screenOffCount > 0) PatternAlert("Inactivity Surveillance", "App accessed hardware while the device was completely inactive.")
                        
                        if (events.any { it.sensorType == "DELAYED_EXFILTRATION" }) {
                             PatternAlert("Harvest-and-Leak Pattern", "Detected a delay between sensor access and data upload, suggesting harvesting.")
                        }

                        if (profile.backgroundCount > 3 && profile.foregroundRate > 0.95) {
                            PatternAlert("Background Deviation", "This app is strictly foreground-only, but background access was recorded.")
                        }

                        if (profile.lastRiskCategory == "EXPECTED" && profile.statisticalAnomalyCount == 0 && profile.screenOffCount == 0) {
                            Text("No major behavioral deviations identified against baseline profile.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            onTrust(profile.packageName)
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {

                        Text("Mark Application as Trusted")
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", profile.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Enforce Permission Restrictions")
                    }
                }
            }
        }
    }
}

@Composable
fun PatternSummaryBar(label: String, count: Int, days: Double, color: Color) {
    val perDay = (count / days).toInt()
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("$perDay/day", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = color)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant)) {
            val progress = (perDay / 20f).coerceIn(0.05f, 1f)
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(color))
        }
    }
}

@Composable
fun TimeSeriesGraph(events: List<SensorEvent>) {
    val now = System.currentTimeMillis()
    val last24h = now - 24 * 3600000
    val eventsLast24h = events.filter { it.timestamp > last24h }
    
    val cameraBuckets = IntArray(24)
    val micBuckets = IntArray(24)
    val locBuckets = IntArray(24)
    val netBuckets = IntArray(24)

    eventsLast24h.forEach {
        val hourAgo = ((now - it.timestamp) / 3600000).toInt()
        if (hourAgo in 0..23) {
            val bucketIdx = 23 - hourAgo
            when {
                it.sensorType.contains("camera", true) -> cameraBuckets[bucketIdx]++
                it.sensorType.contains("audio", true) || it.sensorType.contains("mic", true) -> micBuckets[bucketIdx]++
                it.sensorType.contains("location", true) -> locBuckets[bucketIdx]++
                it.sensorType.contains("NETWORK", true) || it.sensorType.contains("EXFILTRATION", true) -> netBuckets[bucketIdx]++
            }
        }
    }
    
    val max = (0..23).maxOf { (cameraBuckets[it] + micBuckets[it] + locBuckets[it] + netBuckets[it]) }.coerceAtLeast(1)

    Column(Modifier.padding(20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            for (i in 0..23) {
                val cam = cameraBuckets[i]
                val mic = micBuckets[i]
                val loc = locBuckets[i]
                val net = netBuckets[i]
                val total = cam + mic + loc + net
                
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Spacer(Modifier.weight(1f))
                    if (total > 0) {
                        if (net > 0) Box(Modifier.fillMaxWidth().weight(net.toFloat() / max).background(Color(0xFF9C27B0)))
                        if (loc > 0) Box(Modifier.fillMaxWidth().weight(loc.toFloat() / max).background(MaterialTheme.colorScheme.tertiary))
                        if (mic > 0) Box(Modifier.fillMaxWidth().weight(mic.toFloat() / max).background(MaterialTheme.colorScheme.error))
                        if (cam > 0) Box(Modifier.fillMaxWidth().weight(cam.toFloat() / max).background(MaterialTheme.colorScheme.primary))
                    } else {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            LegendItem("Camera", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            LegendItem("Mic", MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            LegendItem("Loc", MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            LegendItem("Net", Color(0xFF9C27B0))
        }
    }
}

@Composable
fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun BehaviorProfileCard(profile: AppBehaviorProfile, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Analytics, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    val appName = profile.packageName.split(".").last().replaceFirstChar { it.uppercase() }
                    Text(appName, fontWeight = FontWeight.Bold)
                    Text(profile.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val risk = profile.lastRiskCategory
                Text(risk, color = when(risk) {
                    "CRITICAL" -> MaterialTheme.colorScheme.error
                    "SUSPICIOUS" -> Color(0xFFD29922)
                    else -> MaterialTheme.colorScheme.tertiary
                }, fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
            
            Spacer(Modifier.height(20.dp))
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BehaviorStat("Camera", profile.cameraCount, Icons.Rounded.CameraAlt)
                BehaviorStat("Mic", profile.micCount, Icons.Rounded.Mic)
                BehaviorStat("Loc", profile.locationCount, Icons.Rounded.LocationOn)
                BehaviorStat("Data", profile.dataAccessCount, Icons.Rounded.NetworkCheck)
                BehaviorStat("Sent", 0, Icons.Rounded.Upload, formatBytes(profile.totalBytesUploaded))
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            val activeHours = profile.typicalHours
            val windowText = if (activeHours.isNotEmpty()) {
                val start = activeHours.first()
                val end = activeHours.last()
                if (start == end) "$start:00" else "$start:00 – $end:00"
            } else {
                "Calculating..."
            }
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Baseline Window", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(windowText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Integrity", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${(profile.foregroundRate * 100).toInt()}% Foreground", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            
            if (profile.screenOffCount > 0 || profile.uploadSpikes > 0 || profile.statisticalAnomalyCount > 0) {
                Spacer(Modifier.height(12.dp))
                if (profile.statisticalAnomalyCount > 0) PatternAlert("Network Volatility", "Improbable data spikes detected")
                else if (profile.screenOffCount > 0) PatternAlert("Screen-Off Surveillance", "Detected while inactive")
            }
        }
    }
}

@Composable
fun BehaviorStat(label: String, count: Int, icon: ImageVector, customValue: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(customValue ?: "$count", fontWeight = FontWeight.Black, fontSize = 14.sp)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PatternAlert(title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
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
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
            Triple(Icons.Rounded.PieChart, "Behavior", 2),
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
