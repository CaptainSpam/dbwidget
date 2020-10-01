package net.exclaimindustries.dbwidget.activities

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.FragmentActivity
import net.exclaimindustries.dbwidget.R
import net.exclaimindustries.dbwidget.services.DataFetchService
import net.exclaimindustries.dbwidget.widgets.WidgetProvider
import java.util.*


class DBWidgetConfigure : FragmentActivity() {
    private var beeShed = false

    companion object {
        private const val DEBUG_TAG = "DBWidgetConfigure"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.configure)
        setResult(RESULT_CANCELED, getWidgetIntent())
        updateBeeShedDescription()
    }

    private fun getWidgetId(): Int {
        val extras = intent?.extras
        return extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun getWidgetIntent(): Intent {
        val result = Intent()
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, getWidgetId())
        return result
    }

    fun createWidget(view: View) {
        val appWidgetId: Int = getWidgetId()

        // If we didn't get a widget ID, well, we're hosed.
        if(appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
        }

        Log.d(DEBUG_TAG, "Today's widget ID is $appWidgetId")

        val appWidgetManager = AppWidgetManager.getInstance(this)

        val widgetBundle = appWidgetManager.getAppWidgetOptions(appWidgetId)
        widgetBundle.putBoolean(WidgetProvider.BEE_SHED_BANNERS, beeShed)
        appWidgetManager.updateAppWidgetOptions(appWidgetId, widgetBundle)

        // Get the widget updated.  Remember, merely updating the options does NOT call for an
        // update!  Also, we have to call into WidgetProvider's companion stuff.  Fortunately, we
        // have almost everything we need.
        val event = DataFetchService.Companion.ResultEventLiveData.value
        WidgetProvider.renderWidget(
            this,
            appWidgetManager,
            appWidgetId,
            if (event?.data?.omegaShift == true) WidgetProvider.Companion.DBShift.OmegaShift
            else WidgetProvider.getShift(
                Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")),
                beeShed
            ),
            event
        )

        // Wrap it up and get a result out the door!
        setResult(RESULT_OK, getWidgetIntent())
        finish()
    }

    fun toggleBeeShed(view: View) {
        if(view is SwitchCompat) {
            beeShed = view.isChecked
            updateBeeShedDescription()
        }
    }

    private fun updateBeeShedDescription() {
        findViewById<TextView>(R.id.bee_shed_description)
            ?.setText(if (beeShed) R.string.pref_bee_shed_on else R.string.pref_bee_shed_off)
    }
}