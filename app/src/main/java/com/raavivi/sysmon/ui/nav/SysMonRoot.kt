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
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.dashboard.DashboardScreen
import com.raavivi.sysmon.ui.files.FilesScreen
import com.raavivi.sysmon.ui.files.PdfViewerScreen
import com.raavivi.sysmon.ui.files.TextEditorScreen
import com.raavivi.sysmon.ui.modellog.ModelLogScreen
import com.raavivi.sysmon.ui.more.MoreScreen
import com.raavivi.sysmon.ui.power.PowerScreen
import com.raavivi.sysmon.ui.processes.ProcessesScreen
import com.raavivi.sysmon.ui.screen.ScreenShareScreen
import com.raavivi.sysmon.ui.setup.LoginScreen
import com.raavivi.sysmon.ui.terminal.TerminalScreen
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
    val role by container.session.role.collectAsStateWithLifecycle()
    val features by container.session.features.collectAsStateWithLifecycle()
    val isAdmin = role == SessionManager.ROLE_ADMIN

    // Model Log is both admin-only and feature-gated (its router 404s when off),
    // so its tab only exists when the server actually answers for it.
    val visibleDests = TopDest.entries.filter { dest ->
        when (dest) {
            TopDest.ModelLog -> isAdmin && features?.modelLog == true
            else -> true
        }
    }

    // If the tab or tool we're on disappears (role/flag change), fall back home.
    LaunchedEffect(visibleDests, currentRoute, isAdmin, features) {
        val onHiddenTab = TopDest.entries.any { it.route == currentRoute } &&
            visibleDests.none { it.route == currentRoute }
        val onHiddenTool = when (currentRoute) {
            Routes.TERMINAL, Routes.SCREEN -> !isAdmin
            Routes.WHATSAPP -> !isAdmin || features?.whatsapp != true
            else -> false
        }
        if (onHiddenTab || onHiddenTool) {
            nav.navigate(TopDest.Dashboard.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
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
            startDestination = TopDest.Dashboard.route,
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
            composable(TopDest.ModelLog.route) { ModelLogScreen() }
            composable(TopDest.More.route) {
                MoreScreen(
                    onOpenTerminal = { nav.navigate(Routes.TERMINAL) },
                    onOpenScreen = { nav.navigate(Routes.SCREEN) },
                    onOpenWhatsApp = { nav.navigate(Routes.WHATSAPP) },
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
            composable(Routes.WHATSAPP) { WhatsAppScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.POWER) { PowerScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

/** Windows paths contain `:` and `\`; URL-safe Base64 keeps them out of the route. */
private fun encodePath(path: String): String =
    Base64.encodeToString(path.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun decodePath(token: String): String =
    runCatching { String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
        .getOrDefault("")
