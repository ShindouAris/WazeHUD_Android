package com.chisadin.hudwz.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chisadin.hudwz.ui.bluetooth.ConnectionScreen
import com.chisadin.hudwz.ui.debug.DebugScreen
import com.chisadin.hudwz.ui.editor.EditorScreen
import com.chisadin.hudwz.ui.editor.ProfilesScreen
import com.chisadin.hudwz.ui.hud.HudScreen
import com.chisadin.hudwz.ui.settings.SettingsScreen
import com.chisadin.hudwz.viewmodel.HudViewModel

private enum class AppRoute(val route: String, val label: String, val icon: ImageVector) {
    CONNECT("connect", "Kết nối", Icons.Rounded.Bluetooth),
    PROFILES("profiles", "Hồ sơ", Icons.Rounded.DashboardCustomize),
    SETTINGS("settings", "Cài đặt", Icons.Rounded.Settings),
    DEBUG("debug", "Chẩn đoán", Icons.Rounded.BugReport),
}

@Composable
fun HudApp(
    viewModel: HudViewModel,
    onWorkspaceModeChanged: @Composable (hudActive: Boolean, editorActive: Boolean) -> Unit,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val hudMode = currentRoute == "hud"
    val editorMode = currentRoute == "editor"
    onWorkspaceModeChanged(hudMode, editorMode)

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val hudState by viewModel.hudState.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val rawPackets by viewModel.rawPackets.collectAsStateWithLifecycle()
    val parsedPacket by viewModel.parsedPacket.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (!hudMode && currentRoute != "editor") {
                NavigationBar {
                    AppRoute.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoute.CONNECT.route,
            modifier = if (hudMode || currentRoute == "editor") Modifier else Modifier.padding(innerPadding),
        ) {
            composable(AppRoute.CONNECT.route) {
                ConnectionScreen(
                    devices = devices,
                    connection = connection,
                    settings = settings,
                    scanning = scanning,
                    onRefreshPaired = viewModel::refreshPairedDevices,
                    onScan = viewModel::scanBle,
                    onStopScan = viewModel::stopScan,
                    onConnect = viewModel::connect,
                    onListen = viewModel::listen,
                    onDisconnect = viewModel::disconnect,
                    onForget = viewModel::forgetSavedDevice,
                    onOpenHud = { navController.navigate("hud") },
                )
            }
            composable(AppRoute.PROFILES.route) {
                ProfilesScreen(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    onSelect = viewModel::selectProfile,
                    onCreate = viewModel::createProfile,
                    onDuplicate = viewModel::duplicateProfile,
                    onRename = viewModel::renameProfile,
                    onDelete = viewModel::deleteProfile,
                    onEdit = { navController.navigate("editor") },
                    onUpdateOrientationMode = viewModel::updateProfileOrientationMode,
                )
            }
            composable(AppRoute.SETTINGS.route) {
                SettingsScreen(settings) { next -> viewModel.updateSettings { next } }
            }
            composable(AppRoute.DEBUG.route) {
                DebugScreen(
                    connection = connection,
                    metrics = metrics,
                    parsedPacket = parsedPacket,
                    rawPackets = rawPackets,
                    events = events,
                    onInject = viewModel::acceptDebugPacket,
                    diagnostics = viewModel::diagnosticsText,
                )
            }
            composable("editor") {
                EditorScreen(
                    profile = activeProfile,
                    fontScale = settings.fontScale,
                    onBack = navController::popBackStack,
                    onElementChange = { element, isPortrait -> viewModel.updateElement(activeProfile.id, element, isPortrait) },
                    onScaleChange = { viewModel.updateProfileScale(activeProfile.id, it) },
                    onAddElement = { type, x, y, isPortrait -> viewModel.addElement(activeProfile.id, type, x, y, isPortrait) },
                    onRemoveElement = { id, isPortrait -> viewModel.removeElement(activeProfile.id, id, isPortrait) },
                    onMoveElement = { id, move, isPortrait -> viewModel.moveElement(activeProfile.id, id, move, isPortrait) },
                    onUpdateOrientationMode = { mode -> viewModel.updateProfileOrientationMode(activeProfile.id, mode) },
                )
            }
            composable("hud") {
                HudScreen(
                    state = hudState,
                    profile = activeProfile,
                    settings = settings,
                    onExit = navController::popBackStack,
                    onMirrorChanged = { mirror -> viewModel.updateSettings { it.copy(mirrorMode = mirror) } },
                    onOrientationChanged = { orient -> viewModel.updateSettings { it.copy(orientation = orient) } },
                )
            }
        }
    }
}
