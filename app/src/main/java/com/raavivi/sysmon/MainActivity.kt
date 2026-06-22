package com.raavivi.sysmon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.raavivi.sysmon.ui.nav.SysMonRoot
import com.raavivi.sysmon.ui.theme.SysMonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as SysMonApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                SysMonTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        SysMonRoot()
                    }
                }
            }
        }
    }
}
