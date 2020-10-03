package net.exclaimindustries.dbwidget.activities

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.CompoundButton
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

    private lateinit var handler: Handler

    private val rotationRunner: Runnable = Runnable { doRotation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.configure)
        setResult(RESULT_CANCELED, getWidgetIntent())

        val vto = findViewById<ViewGroup>(R.id.prefs)?.viewTreeObserver
        if(vto != null && vto.isAlive) {
            vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if(vto.isAlive)
                        vto.removeOnGlobalLayoutListener(this)
                    // Let's set a dumb easter egg in motion.  Set the pivot to not-quite-center.
                    val logo = findViewById<View>(R.id.logo)
                    logo.pivotX = logo.measuredWidth * .40f
                    logo.pivotY = logo.measuredHeight * .61f

                    // Then, make sure the Rustproof Bee Shed icon is updated correctly once layout
                    // hits.
                    updateBeeShedIcon()
                }
            })
        }

        handler = Handler(mainLooper)
    }

    override fun onResume() {
        super.onResume()

        handler.postDelayed(rotationRunner, 1000)
    }

    override fun onPause() {
        super.onPause()

        handler.removeCallbacks(rotationRunner)
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
            if (event?.data?.omegaShift == true) WidgetProvider.Companion.DBShift.OMEGASHIFT
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
        updateBeeShedIcon()
    }

    private fun updateBeeShedIcon() {
        val view = findViewById<CompoundButton>(R.id.bee_shed_switch)

        if(view != null) {
            beeShed = view.isChecked
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                if (beeShed) R.drawable.pref_bee_shed_on else R.drawable.pref_bee_shed_off,
                0
            )
        }
    }

    private fun doRotation() {
        // Rotate it juuuuuuust a wee bit...
        val logo = findViewById<View>(R.id.logo)
        if(logo != null) logo.rotation += 0.05f
        handler.postDelayed(rotationRunner, 1000)
    }
}