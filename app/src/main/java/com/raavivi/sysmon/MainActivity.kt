package com.raavivi.sysmon

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
import com.raavivi.sysmon.ui.nav.SysMonRoot
import com.raavivi.sysmon.ui.theme.SysMonTheme
import com.raavivi.sysmon.ui.theme.ThemeStyle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SysMonApp).container
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
}
