package cz.mares.zdravipm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6B78),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7F0F5),
    onPrimaryContainer = Color(0xFF04262B),
    secondary = Color(0xFF3E6369),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E9ED),
    onSecondaryContainer = Color(0xFF0E2529),
    tertiary = Color(0xFFB33B4C),
    background = Color(0xFFF5FAFA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EFF1),
    onSurfaceVariant = Color(0xFF3F5154)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66D5E3),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF0D4E58),
    onPrimaryContainer = Color(0xFFD2F6FC),
    secondary = Color(0xFFA9CBD1),
    onSecondary = Color(0xFF13343A),
    secondaryContainer = Color(0xFF284A50),
    onSecondaryContainer = Color(0xFFD5E9ED),
    tertiary = Color(0xFFFF8896),
    background = Color(0xFF0D1517),
    surface = Color(0xFF142023),
    surfaceVariant = Color(0xFF203034),
    onSurfaceVariant = Color(0xFFB8CACD)
)

@Composable
fun App() {
    val darkTheme = isSystemInDarkTheme()
    val bridge = rememberHealthBridge()
    val uiState by bridge.state.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                topBar = {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Zdraví PM",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "© 2026 Created by Pavel Mareš",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                onClick = {
                                    if (!uiState.healthConnectGranted) {
                                        bridge.requestHealthConnectPermissions()
                                    } else {
                                        bridge.syncAllPendingToHealthConnect()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (uiState.healthConnectGranted) {
                                    Color(0xFF1B8755).copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.HealthAndSafety,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (uiState.healthConnectGranted) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        text = if (uiState.healthConnectGranted) "HC aktivní" else "Povolit HC",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (uiState.healthConnectGranted) Color(0xFF1B8755) else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                            label = { Text("Cukr") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.MonitorWeight, contentDescription = null) },
                            label = { Text("Váha & Tělo") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("Nastavení") }
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (selectedTab) {
                        0 -> GlucoseTabContent(uiState = uiState, bridge = bridge)
                        1 -> ScaleTabContent(
                            uiState = uiState,
                            bridge = bridge,
                            onOpenSettings = { selectedTab = 2 }
                        )
                        else -> SettingsTabContent(uiState = uiState, bridge = bridge)
                    }
                }
            }
        }
    }
}
