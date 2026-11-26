package com.raavivi.sysmon

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.raavivi.sysmon.core.auth.SessionManager
import com.raavivi.sysmon.core.auth.TokenRenewer
import com.raavivi.sysmon.core.data.SettingsStore
import com.raavivi.sysmon.core.data.chatindex.ChatIndexRepository
import com.raavivi.sysmon.core.data.chatindex.HttpChatIndexRepository
import com.raavivi.sysmon.core.data.whatsapp.ContentResolverUploadSource
import com.raavivi.sysmon.core.data.whatsapp.HttpWhatsAppRepository
import com.raavivi.sysmon.core.data.whatsapp.UploadSource
import com.raavivi.sysmon.core.data.whatsapp.WhatsAppRepository
import com.raavivi.sysmon.core.log.AppLog
import com.raavivi.sysmon.core.model.ChatOpenRequest
import com.raavivi.sysmon.core.net.ApiProvider
import com.raavivi.sysmon.core.push.PushRegistrar
import com.raavivi.sysmon.core.tracker.TrackerContainer
import com.raavivi.sysmon.core.update.UpdateManager
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

    /** WhatsApp data layer (REST + live socket) and the picked-file reader its uploads use. */
    val whatsapp: WhatsAppRepository = HttpWhatsAppRepository(api)
    val uploads: UploadSource = ContentResolverUploadSource(appContext.contentResolver)

    /** The chat index explorer and the private-words glossary (Settings > Chat index; admin). */
    val chatIndex: ChatIndexRepository = HttpChatIndexRepository(api)

    /** In-app updates downloaded from sys-mon. */
    val updates: UpdateManager = UpdateManager(appContext, settings, api)

    /** Built-in phone tracker (Find Me). Own prefs, client and device token; lazy so it costs nothing until used. */
    val tracker: TrackerContainer by lazy { TrackerContainer(appContext) }

    /** A route a notification asked to open; the nav host takes it and clears it. */
    val pendingRoute: MutableStateFlow<String?> = MutableStateFlow(null)

    /** A WhatsApp chat another screen (the Chat index) asked to open at a moment; the WhatsApp screen takes it and clears it. */
    val pendingChatOpen: MutableStateFlow<ChatOpenRequest?> = MutableStateFlow(null)

    /** Coil loader that reuses the authed OkHttp client so `/api/fs/file` images
     *  and WhatsApp media carry the bearer token. The GIF decoders animate WhatsApp stickers (animated
     *  webp needs ImageDecoder, API 28+; older devices get GIFs only). The server marks media responses
     *  `Cache-Control: private, max-age=604800, immutable`; nothing in the client strips it, so Coil's
     *  disk cache serves repeat loads. */
    val imageLoader: ImageLoader = ImageLoader.Builder(application)
        .okHttpClient(api.okHttpClient)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
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
