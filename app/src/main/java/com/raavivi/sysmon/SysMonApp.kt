package com.raavivi.sysmon

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.auth.TokenRenewer
import com.raavivi.sysmon.core.data.SettingsStore
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.push.PushRegistrar
import com.raavivi.sysmon.core.tracker.TrackerContainer
import kotlinx.coroutines.flow.MutableStateFlow

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
    val pushRegistrar: PushRegistrar = PushRegistrar(appContext, settings, api)
    val tokenRenewer: TokenRenewer = TokenRenewer(settings, api)
    val session: SessionManager = SessionManager(settings, api, pushRegistrar, tokenRenewer)

    /** Built-in phone tracker (Find Me). Own prefs, client and device token; lazy so it costs nothing until used. */
    val tracker: TrackerContainer by lazy { TrackerContainer(appContext) }

    /** A route a notification asked to open; the nav host takes it and clears it. */
    val pendingRoute: MutableStateFlow<String?> = MutableStateFlow(null)

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
        AppLog.init(this)
        container = AppContainer(this)
        // Heater alerts now arrive by FCM push; no foreground watcher to start.
        // Token (re)registration happens after login in SessionManager.
    }
}

/** Provided once at the Compose root so screens/ViewModels can reach dependencies. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
