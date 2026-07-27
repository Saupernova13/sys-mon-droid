package com.raavivi.sysmon.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.raavivi.sysmon.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared plumbing for both home-screen widgets: the intents they send, the
 * per-widget plug binding, and the "redraw everything" entry point.
 *
 * Widget work is deliberately routed through one broadcast receiver
 * ([PlugWidgetActionReceiver]) rather than each provider's own `onReceive`,
 * because a toggle in either widget has to be reflected in *both* — the same
 * plug can appear in a per-plug tile and in the list at the same time.
 */
object PlugWidgets {

    const val ACTION_TOGGLE = "com.raavivi.sysmon.widget.TOGGLE"
    const val ACTION_REFRESH = "com.raavivi.sysmon.widget.REFRESH"

    const val EXTRA_PLUG_ID = "plug_id"
    const val EXTRA_DESIRED_ON = "desired_on"

    private const val PREFS = "sysmon_widget_bindings"

    // ── per-widget plug binding ──────────────────────────────────────────────

    /** The plug a single-plug widget instance was configured for. */
    fun boundPlug(context: Context, widgetId: Int): String? =
        prefs(context).getString(key(widgetId), null)

    fun bindPlug(context: Context, widgetId: Int, plugId: String) {
        prefs(context).edit().putString(key(widgetId), plugId).apply()
    }

    /** Called when instances are deleted, so bindings don't accumulate forever. */
    fun clearBindings(context: Context, widgetIds: IntArray) {
        val editor = prefs(context).edit()
        widgetIds.forEach { editor.remove(key(it)) }
        editor.apply()
    }

    // ── redraw ───────────────────────────────────────────────────────────────

    /** Redraw every widget of both kinds from the cached snapshot. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        SinglePlugWidgetProvider.updateAll(context, manager)
        AllPlugsWidgetProvider.updateAll(context, manager)
    }

    /**
     * Ask the widgets to pull fresh data. Safe to call from anywhere — the app
     * after a toggle, the FCM service on a plug alert — and a no-op in effect
     * when no widgets are placed.
     */
    fun requestRefresh(context: Context) {
        if (!anyPlaced(context)) return
        context.sendBroadcast(
            Intent(context, PlugWidgetActionReceiver::class.java).setAction(ACTION_REFRESH),
        )
    }

    private fun anyPlaced(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return ids(context, manager, SinglePlugWidgetProvider::class.java).isNotEmpty() ||
            ids(context, manager, AllPlugsWidgetProvider::class.java).isNotEmpty()
    }

    fun ids(context: Context, manager: AppWidgetManager, provider: Class<*>): IntArray =
        manager.getAppWidgetIds(ComponentName(context, provider))

    // ── intents ──────────────────────────────────────────────────────────────

    fun togglePendingIntent(context: Context, plugId: String, desiredOn: Boolean): PendingIntent {
        val intent = Intent(context, PlugWidgetActionReceiver::class.java)
            .setAction(ACTION_TOGGLE)
            .putExtra(EXTRA_PLUG_ID, plugId)
            .putExtra(EXTRA_DESIRED_ON, desiredOn)
            // Extras alone don't make PendingIntents distinct, so the plug id
            // goes in the data URI too — otherwise every plug's toggle would
            // collapse onto whichever one was registered first.
            .setData(android.net.Uri.parse("sysmon://plug/$plugId"))
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun refreshPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PlugWidgetActionReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun openAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * A toggle template for the list widget's rows. Collection items can't carry
     * their own PendingIntent — the ListView gets one template and each row
     * supplies the differing extras through a fill-in intent.
     */
    fun toggleTemplate(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, PlugWidgetActionReceiver::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    // ── formatting ───────────────────────────────────────────────────────────

    private val stamp = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** When the shown reading was taken. Widgets update on a 30-minute floor, so
     *  saying how old the data is matters more here than it does in the app. */
    fun stampOf(snapshot: PlugSnapshot): String =
        if (snapshot.takenAtMs <= 0) "" else stamp.format(Date(snapshot.takenAtMs))

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(widgetId: Int) = "widget_$widgetId"
}
