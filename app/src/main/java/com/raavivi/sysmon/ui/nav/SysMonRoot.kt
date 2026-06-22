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
import com.raavivi.sysmon.ui.common.LoadingBox
import com.raavivi.sysmon.ui.dashboard.DashboardScreen
import com.raavivi.sysmon.ui.files.FilesScreen
import com.raavivi.sysmon.ui.files.TextEditorScreen
import com.raavivi.sysmon.ui.modellog.ModelLogScreen
import com.raavivi.sysmon.ui.more.MoreScreen
import com.raavivi.sysmon.ui.processes.ProcessesScreen
import com.raavivi.sysmon.ui.setup.LoginScreen

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
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopDest.entries.forEach { dest ->
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
            composable(TopDest.Dashboard.route) { DashboardScreen() }
            composable(TopDest.Processes.route) { ProcessesScreen() }
            composable(TopDest.Files.route) {
                FilesScreen(onEditFile = { path ->
                    nav.navigate("${Routes.TEXT_EDITOR}/${encodePath(path)}")
                })
            }
            composable(TopDest.ModelLog.route) { ModelLogScreen() }
            composable(TopDest.More.route) { MoreScreen() }
            composable(
                route = "${Routes.TEXT_EDITOR}/{path}",
                arguments = listOf(navArgument("path") { type = NavType.StringType }),
            ) { entry ->
                val path = decodePath(entry.arguments?.getString("path").orEmpty())
                TextEditorScreen(path = path, onBack = { nav.popBackStack() })
            }
        }
    }
}

/** Windows paths contain `:` and `\`; URL-safe Base64 keeps them out of the route. */
private fun encodePath(path: String): String =
    Base64.encodeToString(path.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun decodePath(token: String): String =
    runCatching { String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }
        .getOrDefault("")
