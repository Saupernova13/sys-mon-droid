package com.raavivi.sysmon.ui.nav

import android.util.Base64
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raavivi.sysmon.LocalAppContainer
import com.raavivi.sysmon.core.auth.AuthState
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.ui.alerts.PlugAlertsScreen
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.dashboard.DashboardScreen
import com.raavivi.sysmon.ui.files.FilesScreen
import com.raavivi.sysmon.ui.findme.FindMeScreen
import com.raavivi.sysmon.ui.health.HealthScreen
import com.raavivi.sysmon.ui.files.PdfViewerScreen
import com.raavivi.sysmon.ui.files.TextEditorScreen
import com.raavivi.sysmon.ui.logs.LogsScreen
import com.raavivi.sysmon.ui.modellog.ModelLogScreen
import com.raavivi.sysmon.ui.more.MoreScreen
import com.raavivi.sysmon.ui.notifications.NotificationsScreen
import com.raavivi.sysmon.ui.power.PowerScreen
import com.raavivi.sysmon.ui.processes.ProcessesScreen
import com.raavivi.sysmon.ui.screen.ScreenShareScreen
import com.raavivi.sysmon.ui.chatindex.ChatIndexScreen
import com.raavivi.sysmon.ui.settings.CardSettingsScreen
import com.raavivi.sysmon.ui.settings.JellyfinScreen
import com.raavivi.sysmon.ui.settings.SettingsScreen
import com.raavivi.sysmon.ui.setup.LoginScreen
import com.raavivi.sysmon.ui.theme.LocalThemeStyle
import com.raavivi.sysmon.ui.theme.ThemeStyle
import com.raavivi.sysmon.ui.terminal.TerminalScreen
import com.raavivi.sysmon.ui.update.UpdateScreen
import com.raavivi.sysmon.ui.whatsapp.WhatsAppScreen

@Composable
fun SysMonRoot() {
    val container = LocalAppContainer.current
    val state by container.session.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { container.session.bootstrap() }

    when (state) {
        AuthState.Loading -> LoadingBox()
        AuthState.LoggedOut -> LoginScreen()
        AuthState.LoggedIn -> MainScaffold()
    }
}

@Composable
private fun MainScaffold() {
    val container = LocalAppContainer.current
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    LaunchedEffect(currentRoute) { currentRoute?.let { AppLog.i("Nav", it) } }
    val role by container.session.role.collectAsStateWithLifecycle()
    val features by container.session.features.collectAsStateWithLifecycle()
    val isAdmin = role == SessionManager.ROLE_ADMIN

    // WhatsApp is both admin-only and feature-gated (its router 404s when off),
    // so its tab only exists when the server actually answers for it.
    val style = LocalThemeStyle.current
    val minimal = style == ThemeStyle.Minimal
    val visibleDests = bottomTabs(style, isAdmin, features)

    // Silent update check whenever the app comes to the foreground (throttled to 12 h inside).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { container.updates.silentCheck() }
    }

    // If the tab or tool we're on disappears (role/flag change), fall back home.
    LaunchedEffect(visibleDests, currentRoute, isAdmin, features, style) {
        val onHiddenTab = isStrandedOnTab(style, currentRoute, visibleDests)
        val onHiddenTool = when (currentRoute) {
            Routes.TERMINAL, Routes.SCREEN, Routes.SETTINGS, Routes.JELLYFIN, Routes.CARD_SETTINGS, Routes.CHAT_INDEX -> !isAdmin
            Routes.MODEL_LOG -> !isAdmin || features?.modelLog != true
            Routes.FIND_ME -> !isAdmin || features?.findme != true
            else -> false
        }
        if (onHiddenTab || onHiddenTool) {
            nav.navigate(startTab(style).route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }

    // A notification asked for a screen (MainActivity.openIntent). Wait for the
    // feature flags, or the hidden-tool check above would bounce straight back.
    val pendingRoute by container.pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRoute, isAdmin, features, style) {
        val route = pendingRoute ?: return@LaunchedEffect
        val flags = features ?: return@LaunchedEffect
        container.pendingRoute.value = null
        if (visibleDests.any { it.route == route }) {
            // Minimal: Health and Find me are tabs, so open them directly.
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        } else if (route == Routes.FIND_ME && isAdmin && flags.findme) {
            nav.navigate(TopDest.More.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            nav.navigate(Routes.FIND_ME) { launchSingleTop = true }
        } else if (route == Routes.HEALTH || route == Routes.UPDATE) {
            nav.navigate(TopDest.More.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            nav.navigate(route) { launchSingleTop = true }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                visibleDests.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = startTab(style).route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopDest.Dashboard.route) {
                DashboardScreen(onOpenPower = { nav.navigate(Routes.POWER) })
            }
            composable(TopDest.Processes.route) { ProcessesScreen() }
            composable(TopDest.Files.route) {
                FilesScreen(
                    onEditFile = { path -> nav.navigate("${Routes.TEXT_EDITOR}/${encodePath(path)}") },
                    onOpenPdf = { path -> nav.navigate("${Routes.PDF_VIEWER}/${encodePath(path)}") },
                )
            }
            composable(TopDest.WhatsApp.route) { WhatsAppScreen(onBack = null, onOpenCardSettings = { nav.navigate(Routes.CARD_SETTINGS) }) }
            composable(TopDest.More.route) {
                MoreScreen(
                    onOpenTerminal = { nav.navigate(Routes.TERMINAL) },
                    onOpenScreen = { nav.navigate(Routes.SCREEN) },
                    onOpenModelLog = { nav.navigate(Routes.MODEL_LOG) },
                    onOpenFindMe = { nav.navigate(Routes.FIND_ME) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenJellyfin = { nav.navigate(Routes.JELLYFIN) },
                    onOpenCardSettings = { nav.navigate(Routes.CARD_SETTINGS) },
                    onOpenChatIndex = { nav.navigate(Routes.CHAT_INDEX) },
                    onOpenNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                    onOpenLogs = { nav.navigate(Routes.LOGS) },
                    onOpenHealth = { nav.navigate(Routes.HEALTH) },
                    onOpenUpdate = { nav.navigate(Routes.UPDATE) },
                    onOpenSchedules = { nav.navigate(Routes.POWER_SCHEDULES) },
                    onOpenStats = { nav.navigate(TopDest.Dashboard.route) },
                    onOpenProcesses = { nav.navigate(TopDest.Processes.route) },
                    onOpenFiles = { nav.navigate(TopDest.Files.route) },
                )
            }
            composable(
                route = "${Routes.TEXT_EDITOR}/{path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { entry ->
                val path = decodePath(entry.arguments?.getString("path").orEmpty())
                TextEditorScreen(path = path, onBack = { nav.popBackStack() })
            }
            composable(
                route = "${Routes.PDF_VIEWER}/{path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { entry ->
                val path = decodePath(entry.arguments?.getString("path").orEmpty())
                PdfViewerScreen(path = path, onBack = { nav.popBackStack() })
            }
            composable(Routes.TERMINAL) { TerminalScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.SCREEN) { ScreenShareScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.MODEL_LOG) { ModelLogScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.FIND_ME) {
                FindMeScreen(onBack = if (minimal) null else ({ nav.popBackStack() }))
            }
            composable(
                route = "${Routes.POWER}?tab={tab}",
                arguments = listOf(
                    navArgument("tab") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                PowerScreen(
                    onBack = { nav.popBackStack() },
                    initialTab = entry.arguments?.getString("tab"),
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.NOTIFICATIONS) {
                NotificationsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenPlugAlerts = { nav.navigate(Routes.PLUG_ALERTS) },
                )
            }
            composable(Routes.LOGS) { LogsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.JELLYFIN) { JellyfinScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CARD_SETTINGS) { CardSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.CHAT_INDEX) {
                ChatIndexScreen(
                    onBack = { nav.popBackStack() },
                    onOpenChat = { req ->
                        container.pendingChatOpen.value = req
                        nav.navigate(TopDest.WhatsApp.route) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.PLUG_ALERTS) { PlugAlertsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HEALTH) {
                HealthScreen(onBack = if (minimal) null else ({ nav.popBackStack() }))
            }
            composable(Routes.UPDATE) { UpdateScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/** Windows paths contain `:` and `\`; URL-safe Base64 keeps them out of the route. */
private fun encodePath(path: String): String =
    Base64.encodeToString(path.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun decodePath(token: String): String =
    runCatching { String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
        .getOrDefault("")
