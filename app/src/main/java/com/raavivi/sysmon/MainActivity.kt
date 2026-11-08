package com.raavivi.sysmon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raavivi.sysmon.ui.nav.Routes
import com.raavivi.sysmon.ui.nav.SysMonRoot
import com.raavivi.sysmon.ui.theme.SysMonTheme
import com.raavivi.sysmon.ui.theme.ThemeStyle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SysMonApp).container
        // Only a fresh launch: a recreated activity gets the same intent back.
        if (savedInstanceState == null) handleOpen(intent)
        setContent {
            val themeKey by container.settings.themeStyle
                .collectAsStateWithLifecycle(initialValue = ThemeStyle.Classic.key)
            CompositionLocalProvider(LocalAppContainer provides container) {
                SysMonTheme(style = ThemeStyle.fromKey(themeKey)) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SysMonRoot()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    /** A notification asked for a screen: queue it for the nav host to open once signed in. */
    private fun handleOpen(intent: Intent?) {
        val route = when (intent?.getStringExtra(EXTRA_OPEN)) {
            OPEN_FIND_ME -> Routes.FIND_ME
            else -> return
        }
        (application as SysMonApp).container.pendingRoute.value = route
    }

    companion object {
        const val EXTRA_OPEN = "com.raavivi.sysmon.OPEN"
        const val OPEN_FIND_ME = "findme"

        /** Brings the app forward (reusing the running activity) and opens [screen]. */
        fun openIntent(context: Context, screen: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, screen)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
