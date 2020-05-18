package net.exclaimindustries.dbwidget.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import net.exclaimindustries.dbwidget.R
import net.exclaimindustries.dbwidget.services.DataFetchService
import java.util.*

class WidgetProvider : AppWidgetProvider() {
    companion object {
        private const val DEBUG_TAG = "WidgetProvider"

        /** The alarm timeout, in millis.  After this much time, a new fetch will be attempted. */
        private const val ALARM_TIMEOUT_MILLIS = 60000L

        /** Action name for the alarm. */
        private const val CHECK_ALARM_ACTION = "net.exclaimindustries.dbwidget.CHECK_ALARM"

        /** This does whatever needs doing for the alarm. */
        class AlarmReceiver : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(DEBUG_TAG, "ALARM!!!!!")
                // ALARM!  Tell the service to try a fetch.  That will fire off updates on the
                // LiveData object for the widget to find later.  It'll be hilarious, trust me.
                DataFetchService.enqueueWork(
                    context,
                    Intent(DataFetchService.ACTION_FETCH_DATA)
                )

                // Then, reschedule for another minute down the line.
                scheduleAlarm(context)
            }
        }

        private fun scheduleAlarm(context: Context) {
            /* TODO: The alarm shouldn't be this simple.  It also needs to account for if it's
               November or not and schedule appropriately. */
            Log.d(
                DEBUG_TAG,
                "Setting alarm for ${SystemClock.elapsedRealtime() + ALARM_TIMEOUT_MILLIS}..."
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // We'll use plain alarms and reschedule as needed.  We'll do this primarily because we
            // DON'T want this to use a wakeup alarm, and we DO want it to be able to drift as need
            // be.  Excessive checks would be pointless.
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ALARM_TIMEOUT_MILLIS,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, AlarmReceiver::class.java).setAction(CHECK_ALARM_ACTION),
                    0
                )
            )
        }

        private fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            alarmManager.cancel(
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, AlarmReceiver::class.java).setAction(CHECK_ALARM_ACTION),
                    0
                )
            )
        }

        sealed class DBShift {
            object DawnGuard: DBShift()
            object AlphaFlight: DBShift()
            object NightWatch: DBShift()
            object ZetaShift: DBShift()
            object OmegaShift: DBShift()
        }

        /** Gets the shift associated with the given Calendar. */
        private fun getShift(cal: Calendar): DBShift = when (cal.get(Calendar.HOUR_OF_DAY)) {
            // TODO: Needs Omega Shift!  That might take this out of the companion object...
            in 0..5 -> DBShift.ZetaShift
            in 6..11 -> DBShift.DawnGuard
            in 12..17 -> DBShift.AlphaFlight
            else -> DBShift.NightWatch
        }

        /** Gets the banner Drawable associated with the given shift. */
        @DrawableRes
        private fun getShiftDrawable(shift: DBShift): Int = when (shift) {
            is DBShift.DawnGuard -> R.drawable.dbdawnguard
            is DBShift.AlphaFlight -> R.drawable.dbalphaflight
            is DBShift.NightWatch -> R.drawable.dbnightwatch
            is DBShift.ZetaShift -> R.drawable.dbzetashift
            is DBShift.OmegaShift -> R.drawable.dbomegashift
        }

        /** Gets the background color associated with the given shift. */
        @ColorRes
        private fun getShiftBackgroundColor(shift: DBShift): Int = when (shift) {
            is DBShift.DawnGuard -> R.color.background_dawnguard
            is DBShift.AlphaFlight -> R.color.background_alphaflight
            is DBShift.NightWatch -> R.color.background_nightwatch
            is DBShift.ZetaShift -> R.color.background_zetashift
            is DBShift.OmegaShift -> R.color.background_omegashift
        }

        /**
         * Resolves a color reference to an Int.  This is largely a convenience method that decides
         * whether to use the proper two-param version of getColor if we're using Marshmallow or
         * later, or use the deprecated single-param version if we're before Marshmallow.
         */
        @ColorInt
        private fun resolveColor(res: Resources, @ColorRes color: Int): Int =
            @Suppress("DEPRECATION")
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                res.getColor(color, null)
            else
                res.getColor(color)
    }

    override fun onEnabled(context: Context) {
        // When the first widget comes in, we need to start the alarm.  Schedule it up!
        Log.d(DEBUG_TAG, "Starting up now!")
        scheduleAlarm(context)

        // TODO: We need to start observing the LiveData thingy here.

        // TODO: We also need to fire off an initial call to the service to make sure the data has
        // been fetched at least once, otherwise we might have empty data until the first alarm.
    }

    override fun onDisabled(context: Context) {
        // The last widget's going away, so shut off the alarm.
        Log.d(DEBUG_TAG, "Shutting down now!")
        cancelAlarm(context)

        // TODO: Also, stop observing the LiveData thingy.
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val shift = getShift(Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")))

        appWidgetIds.forEach { id ->
            renderWidget(context, appWidgetManager, id, shift)
        }
    }

    private fun renderWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        id: Int,
        shift: DBShift
    ) {
        // TODO: Actually apply the data!
        val views = RemoteViews(context.packageName, R.layout.dbwidget)
        views.setImageViewResource(R.id.banner, getShiftDrawable(shift))

        views.setInt(
            R.id.banner,
            "setBackgroundColor",
            resolveColor(context.resources, getShiftBackgroundColor(shift))
        )

        appWidgetManager.updateAppWidget(id, views)
    }
}