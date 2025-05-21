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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import net.exclaimindustries.dbwidget.R
import net.exclaimindustries.dbwidget.services.DataFetchService
import net.exclaimindustries.dbwidget.tools.ActivityTools
import net.exclaimindustries.dbwidget.widgets.WidgetProvider
import java.util.*


class DBWidgetConfigure : AppCompatActivity() {
    private var beeShed = false
    private var vintageOmega = false

    companion object {
        private const val DEBUG_TAG = "DBWidgetConfigure"
        private const val ROTATION_PIVOT_X = 0.40f
        private const val ROTATION_PIVOT_Y = 0.61f
        private const val ROTATION_AMOUNT_DEGREES = 0.05f
        private const val ROTATION_TIME_MILLIS = 1000L
    }

    private lateinit var handler: Handler

    private val rotationRunner: Runnable = Runnable { doRotation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.configure)
        ActivityTools.dealWithInsets(this, R.id.prefs)

        setResult(RESULT_CANCELED, getWidgetIntent())

        val vto = findViewById<ViewGroup>(R.id.prefs)?.viewTreeObserver
        if(vto != null && vto.isAlive) {
            vto.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if(vto.isAlive)
                        vto.removeOnGlobalLayoutListener(this)
                    // Let's set a dumb easter egg in motion.  Set the pivot to not-quite-center.
                    val logo = findViewById<View>(R.id.logo)
                    logo.pivotX = logo.measuredWidth * ROTATION_PIVOT_X
                    logo.pivotY = logo.measuredHeight * ROTATION_PIVOT_Y

                    // Then, make sure the pref icons are updated correctly once layout hits.
                    updateBeeShedIcon()
                    updateVintageOmegaIcon()
                }
            })
        }

        handler = Handler(mainLooper)
    }

    override fun onResume() {
        super.onResume()

        handler.postDelayed(rotationRunner, ROTATION_TIME_MILLIS)
    }

    override fun onPause() {
        super.onPause()

        handler.removeCallbacks(rotationRunner)
    }

    private fun getWidgetId(): Int {
        return intent?.extras?.getInt(
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

        // Write out the setting to preferences.  Apparently, stuff written to WidgetProvider's
        // option bundles isn't persisted, which seems odd.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit {
            this.putBoolean(
                WidgetProvider.prefKeyFor(
                    appWidgetId,
                    WidgetProvider.Companion.Prefs.BEESHED
                ), beeShed
            )
            this.putBoolean(
                WidgetProvider.prefKeyFor(
                    appWidgetId,
                    WidgetProvider.Companion.Prefs.VINTAGEOMEGASHIFT
                ),
                vintageOmega
            )
        }

        val appWidgetManager = AppWidgetManager.getInstance(this)

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

        if(view is CompoundButton) {
            beeShed = view.isChecked
        }
    }

    fun toggleVintageOmega(view: View) {
        if(view is CompoundButton) {
            vintageOmega = view.isChecked
        }
    }

    private fun updateBeeShedIcon() {
        val view = findViewById<CompoundButton>(R.id.bee_shed_switch)

        view?.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (view.isChecked) R.drawable.pref_bee_shed_on else R.drawable.pref_bee_shed_off,
            0
        )
    }

    private fun updateVintageOmegaIcon() {
        val view = findViewById<CompoundButton>(R.id.vintage_omega_switch)

        view?.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (view.isChecked) R.drawable.pref_vintage_omega_on
            else R.drawable.pref_vintage_omega_off,
            0
        )
    }

    private fun doRotation() {
        // Rotate it juuuuuuust a wee bit...
        val logo = findViewById<View>(R.id.logo)
        if(logo != null) logo.rotation += ROTATION_AMOUNT_DEGREES
        handler.postDelayed(rotationRunner, ROTATION_TIME_MILLIS)
    }
}