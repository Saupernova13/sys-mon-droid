package com.raavivi.sysmon

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import com.raavivi.sysmon.core.alerts.HeaterAlertService
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.data.SettingsStore
import com.raavivi.sysmon.core.net.ApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manual dependency container — kept deliberately small so the first build has no
 * annotation-processor (Hilt/kapt) moving parts. Held by [SysMonApp] and exposed to
 * Compose via [LocalAppContainer].
 */
class AppContainer(application: Application) {
    /** Application context for features that need it (e.g. PDF cache files). */
    val appContext: Context = application.applicationContext
    val settings: SettingsStore = SettingsStore(application)
    val api: ApiProvider = ApiProvider()
    val session: SessionManager = SessionManager(settings, api)

    /** Coil loader that reuses the authed OkHttp client so `/api/fs/file` images
     *  and WhatsApp media carry the bearer token. */
    val imageLoader: ImageLoader = ImageLoader.Builder(application)
        .okHttpClient(api.okHttpClient)
        .build()
}

class SysMonApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Bring the heater monitor back up (app update, force-stop, crash) —
        // ensureRunning is a no-op when it can't start from the background.
        CoroutineScope(Dispatchers.Default).launch {
            if (container.settings.heaterAlertsNow()) {
                HeaterAlertService.ensureRunning(this@SysMonApp)
            }
        }
    }
}

/** Provided once at the Compose root so screens/ViewModels can reach dependencies. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
