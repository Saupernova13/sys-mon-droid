package com.raavivi.sysmon.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.raavivi.sysmon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks which plug a per-plug widget should control, when it is dropped on the
 * home screen.
 *
 * Deliberately a plain View activity rather than Compose: it is a one-shot list
 * launched by the launcher, and the result has to be set before the widget is
 * considered placed — a Compose setup here would cost startup time and buy
 * nothing.
 *
 * The result is pre-set to CANCELED, so backing out leaves no half-configured
 * widget on the home screen.
 */
class PlugWidgetConfigureActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_configure)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val status = findViewById<TextView>(R.id.configure_status)
        val list = findViewById<ListView>(R.id.configure_list)
        status.text = "Loading plugs…"

        // Show whatever is cached at once, then refresh so a newly added plug
        // appears without the user having to open the app first.
        render(list, status, PlugWidgetRepository.cached(this))
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { PlugWidgetRepository.refresh(this@PlugWidgetConfigureActivity) }
            render(list, status, snapshot)
        }
    }

    private fun render(list: ListView, status: TextView, snapshot: PlugSnapshot) {
        val plugs = snapshot.plugs
        status.text = when {
            snapshot.signedOut -> "Sign in to sys-mon first, then add the widget."
            plugs.isEmpty() -> snapshot.error ?: "No smart plugs reported by the server."
            else -> "This widget will switch the plug you choose."
        }
        list.visibility = if (plugs.isEmpty()) View.GONE else View.VISIBLE
        if (plugs.isEmpty()) return

        list.adapter = object : ArrayAdapter<WidgetPlug>(
            this,
            R.layout.widget_configure_row,
            plugs,
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.widget_configure_row, parent, false)
                val plug = getItem(position)!!
                view.findViewById<TextView>(R.id.configure_row_name).text = plug.name
                view.findViewById<TextView>(R.id.configure_row_detail).text =
                    detail(plug, snapshot.currency)
                return view
            }
        }
        list.setOnItemClickListener { _, _, position, _ -> choose(plugs[position]) }
    }

    private fun choose(plug: WidgetPlug) {
        PlugWidgets.bindPlug(this, widgetId, plug.id)
        SinglePlugWidgetProvider.render(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(
            RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        )
        finish()
    }
}
