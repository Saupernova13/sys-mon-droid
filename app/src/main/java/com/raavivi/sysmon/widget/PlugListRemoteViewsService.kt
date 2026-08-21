package com.raavivi.sysmon.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.raavivi.sysmon.R
import com.raavivi.sysmon.ui.common.formatWatts

/** Supplies the all-plugs widget's rows. */
class PlugListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        PlugListFactory(applicationContext)
}

/**
 * Reads rows straight from the cached snapshot.
 *
 * [onDataSetChanged] is deliberately synchronous and network-free: the launcher
 * blocks on it, and the fetch has already happened in the provider (or the
 * action receiver) before it asks for a reload.
 */
private class PlugListFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var plugs: List<WidgetPlug> = emptyList()
    private var currency: String = ""
    private var snapshot: PlugSnapshot = PlugSnapshot()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        snapshot = PlugWidgetRepository.cached(context)
        plugs = snapshot.plugs
        currency = snapshot.currency
    }

    override fun onDestroy() {
        plugs = emptyList()
    }

    override fun getCount(): Int = plugs.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_plug_row)
        val plug = plugs.getOrNull(position) ?: return views

        val pending = snapshot.isPending(plug.id)
        val shownOn = snapshot.shownOn(plug.id)

        views.setTextViewText(R.id.row_name, plug.name)
        views.setTextViewText(
            R.id.row_watts,
            when {
                pending -> "…"
                plug.available && plug.relayOn -> formatWatts(plug.watts)
                else -> ""
            },
        )
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
            viewId = R.id.row_state,
        )

        // Collection rows can't own a PendingIntent; the ListView holds one
        // template and each row fills in what differs. The desired state is the
        // opposite of what this row is showing, so a plug switched by hand can't
        // be driven the wrong way by a stale reading.
        // An empty fill-in carries no plug id, which the receiver drops: while a
        // switch is in flight a further tap would queue a second one against a
        // state that has not settled.
        views.setOnClickFillInIntent(
            R.id.row_state,
            if (pending) {
                Intent()
            } else {
                Intent()
                    .putExtra(PlugWidgets.EXTRA_PLUG_ID, plug.id)
                    .putExtra(PlugWidgets.EXTRA_DESIRED_ON, !plug.relayOn)
            },
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        plugs.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
