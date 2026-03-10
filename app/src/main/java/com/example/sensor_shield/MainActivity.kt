package com.example.sensor_shield

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sensor_shield.data.SensorEvent
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
                MainContainer()
            }
        }
    }
}

@Composable
fun MainContainer(viewModel: SensorViewModel = viewModel()) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val events by viewModel.allEvents.collectAsState(initial = emptyList())
    val privacyScore = viewModel.getPrivacyScore(events)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> if (isGranted) startMonitor(context) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else { startMonitor(context) }
        } else { startMonitor(context) }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PremiumBackground()
        
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                PremiumNavigationBar(selectedTab) { selectedTab = it }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(500)) + slideInHorizontally(tween(500)) { it * direction })
                            .togetherWith(fadeOut(tween(400)) + slideOutHorizontally(tween(400)) { -it * direction })
                    }, label = "TabSwitch"
                ) { tab ->
                    when (tab) {
                        0 -> Dashboard(events, privacyScore) { selectedTab = 1 }
                        1 -> LogScreen(events)
                        2 -> StatsScreen(events)
                        3 -> SettingsScreen()
                    }
                }
            }
        }
    }
}

@Composable
fun PremiumBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -100f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "x"
    )
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -50f,
        targetValue = 50f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "y"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(450.dp)
                .offset(x = (-150).dp + offsetX.dp, y = (-100).dp + offsetY.dp)
                .blur(120.dp)
                .background(MaterialTheme.colorScheme.primary.copy(0.12f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(350.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 100.dp - offsetX.dp, y = 100.dp - offsetY.dp)
                .blur(120.dp)
                .background(MaterialTheme.colorScheme.secondary.copy(0.1f), CircleShape)
        )
    }
}

@Composable
fun Dashboard(events: List<SensorEvent>, score: Int, onSeeAll: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item { HeaderSection() }
        item { ScoreCard(score, events.size) }
        item {
            SectionTitle("Shield Status")
            LiveIndicators(events)
        }
        item {
            SectionTitle("Activity Grid")
            HeatmapGrid(events)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recent Detections")
                Text(
                    "View All",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { onSeeAll() }.padding(bottom = 12.dp)
                )
            }
            RecentList(events)
        }
    }
}

@Composable
fun HeaderSection() {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Privacy Shield", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
            Text("Active Protection Enabled", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        IconButton(
            onClick = {},
            modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(0.5f), CircleShape)
        ) {
            Icon(Icons.Rounded.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun ScoreCard(score: Int, eventCount: Int) {
    val animatedScore by animateFloatAsState(
        targetValue = score.toFloat(),
        animationSpec = tween(2000, easing = FastOutSlowInEasing),
        label = "score"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp).copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 12.dp,
                    strokeCap = StrokeCap.Round,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(0.3f)
                )
                CircularProgressIndicator(
                    progress = { animatedScore / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 12.dp,
                    strokeCap = StrokeCap.Round,
                    color = if (score > 75) Color(0xFF4CAF50) else if (score > 40) Color(0xFFFFA000) else Color(0xFFF44336)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${animatedScore.toInt()}%", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                    Text("Secure", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            Spacer(Modifier.width(24.dp))
            Column {
                Text("Defense Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (score > 75) "Optimal Protection" else if (score > 40) "Moderate Risk" else "Highly Vulnerable",
                    color = if (score > 75) Color(0xFF4CAF50) else if (score > 40) Color(0xFFFFA000) else Color(0xFFF44336),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text("$eventCount logs analyzed", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun LiveIndicators(events: List<SensorEvent>) {
    val recent = events.filter { it.timestamp > System.currentTimeMillis() - 20000 }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        SensorBlob("Camera", Icons.Rounded.CameraAlt, recent.any { it.sensorType.contains("camera", true) })
        SensorBlob("Mic", Icons.Rounded.Mic, recent.any { it.sensorType.contains("audio", true) || it.sensorType.contains("mic", true) })
        SensorBlob("Location", Icons.Rounded.LocationOn, recent.any { it.sensorType.contains("location", true) })
    }
}

@Composable
fun SensorBlob(label: String, icon: ImageVector, active: Boolean) {
    val color = if (active) Color(0xFFF44336) else MaterialTheme.colorScheme.primary.copy(0.4f)
    val scale by animateFloatAsState(if (active) 1.25f else 1f, spring(Spring.DampingRatioHighBouncy), label = "")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .scale(scale)
                .background(color.copy(0.05f), CircleShape)
                .border(1.5.dp, color.copy(0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (active) {
                Box(Modifier.size(58.dp).background(color.copy(0.1f), CircleShape))
            }
            Icon(icon, null, tint = if (active) Color(0xFFF44336) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium)
    }
}

@Composable
fun HeatmapGrid(events: List<SensorEvent>) {
    val now = System.currentTimeMillis()
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val buckets = remember(events) {
        List(48) { index ->
            val start = now - (48 - index) * 3600000
            val end = start + 3600000
            events.filter { it.timestamp in start..end }
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp).copy(alpha = 0.7f))
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(12) { col ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(4) { row ->
                            val index = col * 4 + row
                            val eventsInBucket = buckets[index]
                            val intensity = eventsInBucket.size
                            val color = when {
                                intensity > 5 -> Color(0xFF4CAF50)
                                intensity > 2 -> Color(0xFF81C784)
                                intensity > 0 -> Color(0xFFA5D6A7)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(.2f)
                            }
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(color)
                                    .clickable { selectedIndex = if (selectedIndex == index) null else index }
                                    .border(if (selectedIndex == index) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                            )
                        }
                    }
                }
            }
            
            AnimatedVisibility(visible = selectedIndex != null) {
                selectedIndex?.let { index ->
                    val bucketEvents = buckets[index]
                    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                    val time = formatter.format(Date(now - (48 - index) * 3600000))
                    Column(Modifier.padding(top = 16.dp)) {
                        Text("Activity at $time", fontWeight = FontWeight.Bold)
                        Text("${bucketEvents.size} events detected", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
            }
            
            if (selectedIndex == null) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last 48 Hours history", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text("Activity Intensity", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun RecentList(events: List<SensorEvent>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        events.take(4).forEach { LogItemPremium(it) }
    }
}

@Composable
fun LogScreen(events: List<SensorEvent>) {
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, bottom = 16.dp, horizontal = 20.dp)
        ) {
            Text("Activity Timeline", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp, bottom = 20.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (events.isEmpty()) {
                item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("No logs found.", color = Color.Gray) } }
            }
            items(events.sortedByDescending { it.timestamp }) { event ->
                LogItemPremium(event)
            }
        }
    }
}

@Composable
fun LogItemPremium(event: SensorEvent) {
    val sensorIcon = when {
        event.sensorType.contains("camera", true) -> Icons.Rounded.CameraAlt
        event.sensorType.contains("audio", true) || event.sensorType.contains("mic", true) -> Icons.Rounded.Mic
        event.sensorType.contains("location", true) -> Icons.Rounded.LocationOn
        else -> Icons.Rounded.Sensors
    }

    // Assign light BG based on risk score (0.0 to 1.0)
    val colorData = when {
        event.riskScore > 0.7 -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "CRITICAL")
        event.riskScore > 0.3 -> Triple(Color(0xFFFFF3E0), Color(0xFFE65100), "WARNING")
        else -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "SAFE")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colorData.first.copy(alpha = 0.85f))
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(colorData.second.copy(0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(sensorIcon, null, tint = colorData.second, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.packageName.split(".").last().replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
                Text(
                    event.sensorType.replace("android:", "").replace("_", " ").uppercase(),
                    fontSize = 10.sp,
                    color = colorData.second.copy(0.8f),
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                Modifier
                    .background(colorData.second.copy(0.2f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(colorData.third, style = MaterialTheme.typography.labelSmall, color = colorData.second, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
fun StatsScreen(events: List<SensorEvent>) {
    var statFilter by remember { mutableIntStateOf(0) }
    val cameraCount = events.count { it.sensorType.contains("camera", true) }
    val micCount = events.count { it.sensorType.contains("audio", true) || it.sensorType.contains("mic", true) }
    val locationCount = events.count { it.sensorType.contains("location", true) }
    val total = (cameraCount + micCount + locationCount).coerceAtLeast(1)

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, bottom = 16.dp, horizontal = 20.dp)
        ) {
            Text("Insights & Analytics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PremiumFilterChip("Hardware Distribution", statFilter == 0) { statFilter = 0 }
                    PremiumFilterChip("Risk Breakdown", statFilter == 1) { statFilter = 1 }
                }
            }

            item {
                AnimatedContent(
                    targetState = statFilter,
                    transitionSpec = {
                        (fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 2 })
                            .togetherWith(fadeOut(tween(300)))
                    }, label = "StatAnim"
                ) { filter ->
                    if (filter == 0) {
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(32.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp).copy(alpha = 0.7f))
                        ) {
                            Column(Modifier.padding(24.dp)) {
                                Text("Sensor Usage Overview", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(24.dp))
                                AnimatedStatBar("Visual (Camera)", cameraCount, total, Color(0xFF2196F3))
                                AnimatedStatBar("Aural (Mic)", micCount, total, Color(0xFFF44336))
                                AnimatedStatBar("Geospatial (Loc)", locationCount, total, Color(0xFF4CAF50))
                            }
                        }
                    } else {
                        val critical = events.count { it.riskScore > 0.7 }
                        val warning = events.count { it.riskScore in 0.3..0.7 }
                        val safe = events.count { it.riskScore < 0.3 }
                        
                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(32.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp).copy(alpha = 0.7f))
                        ) {
                            Column(Modifier.padding(24.dp)) {
                                Text("Threat Level Statistics", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(24.dp))
                                AnimatedStatBar("Critical Threats", critical, events.size.coerceAtLeast(1), Color(0xFFC62828))
                                AnimatedStatBar("Moderate Warnings", warning, events.size.coerceAtLeast(1), Color(0xFFE65100))
                                AnimatedStatBar("Safe Validations", safe, events.size.coerceAtLeast(1), Color(0xFF2E7D32))
                            }
                        }
                    }
                }
            }
            
            item {
                SectionTitle("Security Grid")
                HeatmapGrid(events)
            }
        }
    }
}

@Composable
fun AnimatedStatBar(label: String, count: Int, total: Int, color: Color) {
    val progress = count.toFloat() / total
    val animatedProgress by animateFloatAsState(progress, tween(1200, easing = FastOutSlowInEasing), label = "")

    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("$count incidents", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxWidth().height(14.dp).clip(CircleShape),
            color = color,
            trackColor = color.copy(alpha = 0.1f),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
fun PremiumFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f), label = "")
    val contentColor by animateColorAsState(if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, label = "")

    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.height(44.dp).animateContentSize()
    ) {
        Box(Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Text(label, color = contentColor, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
    }
}

@Composable
fun PremiumNavigationBar(selected: Int, onSelected: (Int) -> Unit) {
    NavigationBar(
        modifier = Modifier.clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
        containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp).copy(0.96f),
        tonalElevation = 0.dp
    ) {
        val items = listOf(
            Icons.Rounded.Security to "Shield",
            Icons.Rounded.History to "Logs",
            Icons.Rounded.Analytics to "Insights",
            Icons.Rounded.Tune to "Config"
        )
        items.forEachIndexed { index, pair ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelected(index) },
                icon = { 
                    val scale by animateFloatAsState(if (selected == index) 1.3f else 1f, label = "")
                    Icon(pair.first, null, modifier = Modifier.scale(scale)) 
                },
                label = { Text(pair.second, fontWeight = if(selected == index) FontWeight.ExtraBold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(0.5f)
                )
            )
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, bottom = 16.dp, horizontal = 20.dp)
        ) {
            Text("Configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCardPremium("Privacy Analytics", "Detect hidden sensor leakages in real-time", Icons.Rounded.LockOpen) {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            SettingsCardPremium("Shield Persistence", "Keep the monitor active even in standby", Icons.Rounded.Bolt) {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            SettingsCardPremium("Alert System", "Configure real-time interception alerts", Icons.Rounded.NotificationsActive) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }
        }
    }
}

@Composable
fun SettingsCardPremium(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp).copy(alpha = 0.7f)),
        border = androidx.compose.foundation.BorderStroke(1.2.dp, MaterialTheme.colorScheme.outlineVariant.copy(0.4f))
    ) {
        Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary.copy(0.1f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, fontSize = 12.sp, color = Color.Gray)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
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
