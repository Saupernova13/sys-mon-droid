package com.raavivi.sysmon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.raavivi.sysmon.R
import com.raavivi.sysmon.ui.common.formatWatts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Every plug in one resizable tile, each row with its own toggle, plus the
 * aggregate draw in the header. No configuration step — it always shows whatever
 * the server currently reports, so a plug added later simply appears.
 *
 * The rows come from [PlugListRemoteViewsService]; tapping the header refreshes.
 */
class AllPlugsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { render(context, manager, it) }
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            PlugWidgetRepository.refresh(appContext)
            updateAll(appContext, AppWidgetManager.getInstance(appContext))
        }
    }

    companion object {

        @Suppress("DEPRECATION") // see the note on `render`
        fun updateAll(context: Context, manager: AppWidgetManager) {
            val ids = PlugWidgets.ids(context, manager, AllPlugsWidgetProvider::class.java)
            if (ids.isEmpty()) return
            ids.forEach { render(context, manager, it) }
            // The list has its own adapter; redrawing the frame doesn't reload
            // the rows, so the factory has to be told the data moved.
            manager.notifyAppWidgetViewDataChanged(ids, R.id.plug_list)
        }

        /**
         * Uses the `setRemoteAdapter(Intent)` collection API, deprecated in API
         * 31 in favour of pushing `RemoteCollectionItems` straight into the
         * RemoteViews. That replacement doesn't exist below 31 and this module
         * ships to minSdk 26, so taking it would mean maintaining two row
         * pipelines for one list. The deprecated path still works on current
         * Android; revisit if minSdk ever reaches 31.
         */
        @Suppress("DEPRECATION")
        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_plugs_list)
            val snapshot = PlugWidgetRepository.cached(context)

            views.setTextViewText(R.id.list_total, formatWatts(snapshot.totalWatts))
            views.setTextViewText(
                R.id.list_stamp,
                snapshot.error?.let { "!" } ?: PlugWidgets.stampOf(snapshot),
            )

            val adapter = Intent(context, PlugListRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                // RemoteViewsService intents are cached by identity, and extras
                // are ignored in that comparison — without a unique data URI,
                // every instance would share the first one's factory.
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.plug_list, adapter)
            views.setEmptyView(R.id.plug_list, R.id.list_empty)
            views.setTextViewText(
                R.id.list_empty,
                when {
                    snapshot.signedOut -> "Tap to sign in to sys-mon"
                    snapshot.error != null -> snapshot.error
                    else -> "No smart plugs reported"
                },
            )

            views.setOnClickPendingIntent(
                R.id.list_header,
                PlugWidgets.refreshPendingIntent(context),
            )
            views.setOnClickPendingIntent(
                R.id.list_empty,
                if (snapshot.signedOut) PlugWidgets.openAppPendingIntent(context)
                else PlugWidgets.refreshPendingIntent(context),
            )
            views.setPendingIntentTemplate(R.id.plug_list, PlugWidgets.toggleTemplate(context))

            manager.updateAppWidget(widgetId, views)
        }
    }
}
