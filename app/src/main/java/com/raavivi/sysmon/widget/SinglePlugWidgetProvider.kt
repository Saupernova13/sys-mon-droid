package com.raavivi.sysmon.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.raavivi.sysmon.R
import com.raavivi.sysmon.ui.common.formatWatts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One home-screen tile per plug: its name, live draw, and a pill that switches
 * the relay. Which plug an instance shows is chosen in
 * [PlugWidgetConfigureActivity] when the widget is dropped.
 *
 * Tapping the pill toggles; tapping anywhere else refreshes. That second target
 * matters because Android floors widget updates at 30 minutes — without a way to
 * ask for a reading now, a plug switched by hand would look wrong for half an
 * hour.
 */
class SinglePlugWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        // Paint the cache immediately so the tile is never blank, then fetch.
        widgetIds.forEach { render(context, manager, it) }
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            PlugWidgetRepository.refresh(appContext)
            widgetIds.forEach { render(appContext, AppWidgetManager.getInstance(appContext), it) }
        }
    }

    override fun onDeleted(context: Context, widgetIds: IntArray) {
        PlugWidgets.clearBindings(context, widgetIds)
    }

    companion object {

        /** Redraw every placed instance from the cached snapshot (no network). */
        fun updateAll(context: Context, manager: AppWidgetManager) {
            PlugWidgets.ids(context, manager, SinglePlugWidgetProvider::class.java)
                .forEach { render(context, manager, it) }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_plug_single)
            val snapshot = PlugWidgetRepository.cached(context)
            val plugId = PlugWidgets.boundPlug(context, widgetId)
            val plug = plugId?.let { snapshot.plug(it) }

            when {
                snapshot.signedOut -> {
                    views.setTextViewText(R.id.plug_name, "sys-mon")
                    views.setTextViewText(R.id.plug_detail, "Tap to sign in")
                    views.setTextViewText(R.id.plug_footer, "")
                    pill(context, views, text = "—", style = PillStyle.UNAVAILABLE)
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PlugWidgets.openAppPendingIntent(context),
                    )
                }

                // Configured for a plug the server no longer reports — say so
                // rather than showing a dead toggle.
                plugId != null && plug == null -> {
                    views.setTextViewText(R.id.plug_name, plugId)
                    views.setTextViewText(
                        R.id.plug_detail,
                        if (snapshot.plugs.isEmpty()) "No reading yet" else "Plug not found",
                    )
                    views.setTextViewText(R.id.plug_footer, footer(snapshot))
                    pill(context, views, text = "—", style = PillStyle.UNAVAILABLE)
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PlugWidgets.refreshPendingIntent(context),
                    )
                }

                plug == null -> {
                    views.setTextViewText(R.id.plug_name, "sys-mon")
                    views.setTextViewText(R.id.plug_detail, "Not configured")
                    views.setTextViewText(R.id.plug_footer, "")
                    pill(context, views, text = "—", style = PillStyle.UNAVAILABLE)
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PlugWidgets.openAppPendingIntent(context),
                    )
                }

                else -> {
                    val pending = snapshot.isPending(plug.id)
                    val shownOn = snapshot.shownOn(plug.id)
                    views.setTextViewText(R.id.plug_name, plug.name)
                    views.setTextViewText(
                        R.id.plug_detail,
                        if (pending) (if (shownOn) "Switching on…" else "Switching off…")
                        else detail(plug, snapshot.currency),
                    )
                    views.setTextViewText(R.id.plug_footer, footer(snapshot))
                    pill(
                        context,
                        views,
                        text = when {
                            pending -> if (shownOn) "ON" else "OFF"
                            !plug.available -> "?"
                            plug.relayOn -> "ON"
                            else -> "OFF"
                        },
                        style = when {
                            pending -> PillStyle.PENDING
                            !plug.available -> PillStyle.UNAVAILABLE
                            plug.relayOn -> PillStyle.ON
                            else -> PillStyle.OFF
                        },
                    )
                    // Tapping asks for the opposite of what is shown — never a
                    // blind flip, so a plug switched physically can't be
                    // toggled the wrong way by a stale reading. While a switch
                    // is in flight the tap is dropped rather than queueing a
                    // second one against a state that has not settled.
                    views.setOnClickPendingIntent(
                        R.id.plug_state,
                        if (pending) PlugWidgets.refreshPendingIntent(context)
                        else PlugWidgets.togglePendingIntent(context, plug.id, !plug.relayOn),
                    )
                    views.setOnClickPendingIntent(
                        R.id.widget_root,
                        PlugWidgets.refreshPendingIntent(context),
                    )
                }
            }

            manager.updateAppWidget(widgetId, views)
        }
    }
}

internal enum class PillStyle { ON, OFF, UNAVAILABLE, PENDING }

/**
 * Style the on/off pill. `setTextColor` takes a resolved colour rather than a
 * resource id (the resource-aware overload is API 31+), so it is looked up
 * against the context here.
 */
internal fun pill(
    context: Context,
    views: RemoteViews,
    text: String,
    style: PillStyle,
    viewId: Int = R.id.plug_state,
) {
    views.setTextViewText(viewId, text)
    val (background, colour) = when (style) {
        PillStyle.ON -> R.drawable.widget_pill_on to R.color.widget_power_on_text
        PillStyle.OFF -> R.drawable.widget_pill_off to R.color.widget_on_surface_muted
        PillStyle.UNAVAILABLE -> R.drawable.widget_pill_unavailable to R.color.widget_on_surface_muted
        PillStyle.PENDING -> R.drawable.widget_pill_pending to R.color.widget_power
    }
    views.setInt(viewId, "setBackgroundResource", background)
    views.setTextColor(viewId, ContextCompat.getColor(context, colour))
}

/** A plug that isn't passing power has no meaningful watts to show — the server
 *  reports the pre-switch figure until its next poll, which would read as if the
 *  plug were still drawing. */
internal fun detail(plug: WidgetPlug, currency: String): String = when {
    !plug.available -> "Unreachable"
    !plug.relayOn -> "Switched off"
    else -> "${formatWatts(plug.watts)} · $currency${String.format(Locale.US, "%.2f", plug.costPerHour)}/h"
}

internal fun footer(snapshot: PlugSnapshot): String {
    snapshot.error?.let { return it }
    val stamp = PlugWidgets.stampOf(snapshot)
    return if (stamp.isEmpty()) "" else "as of $stamp · tap to refresh"
}
