package net.exclaimindustries.dbwidget.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import net.exclaimindustries.dbwidget.R
import net.exclaimindustries.dbwidget.services.DataFetchService
import net.exclaimindustries.dbwidget.util.DonationConverter
import java.text.DecimalFormat
import java.util.*

class WidgetProvider : AppWidgetProvider() {
    companion object {
        private const val DEBUG_TAG = "WidgetProvider"

        /** The alarm timeout, in millis.  After this much time, a new fetch will be attempted. */
        private const val ALARM_TIMEOUT_MILLIS = 60000L

        /**
         * After this amount of time with errors, a line of text will show up saying how long it's
         * been since the data was fresh.
         */
        private const val ERROR_TIMEOUT_MILLIS = 60000L * 10L

        /**
         * The number of millis in an hour (1000 millis per second * 60 seconds per minute * 60
         * minutes per hour).
         */
        private const val MILLIS_PER_HOUR = 1000L * 60L * 60L

        /**
         * The number of hours we're considering to be part of Thank You Time, which generally runs
         * well past Omega Shift.
         */
        private const val THANK_YOU_HOURS = 3

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
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val cal = Calendar.getInstance()

            if(cal.get(Calendar.MONTH) != Calendar.NOVEMBER) {
                // If it's NOT November, just schedule a wakeup on the first of November.  Sure, why
                // not?  Someone might leave their device awake until November.
                Log.d(DEBUG_TAG, "It's not November; setting something to wake me up when October ends...")

                cal.set(Calendar.MONTH, Calendar.NOVEMBER)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                if(cal.get(Calendar.MONTH) == Calendar.DECEMBER) {
                    // It's December (and thus past November); wait until next year.
                    cal.add(Calendar.YEAR, 1)
                }

                alarmManager.set(
                    AlarmManager.RTC,
                    cal.timeInMillis,
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, AlarmReceiver::class.java).setAction(CHECK_ALARM_ACTION),
                        0
                    )
                )
            } else {
                Log.d(
                    DEBUG_TAG,
                    "Setting alarm for ${SystemClock.elapsedRealtime() + ALARM_TIMEOUT_MILLIS}..."
                )

                // We'll use plain alarms and reschedule as needed.  We'll do this primarily because
                // we DON'T want this to use a wakeup alarm, and we DO want it to be able to drift
                // as need be.  Excessive checks would be pointless.
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
        }

        private fun cancelAlarm(context: Context) {
            Log.d(DEBUG_TAG, "Canceling the alarm...")
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // If this is the data fetched action, the superclass didn't handle it.  That's where we
        // step in...
        if(intent.action === DataFetchService.ACTION_DATA_FETCHED) {
            renderWidgets(context)
        }
    }

    override fun onEnabled(context: Context) {
        // When the first widget comes in, log it.  The actual business end happens in onUpdate.
        Log.d(DEBUG_TAG, "Starting up now!")
    }

    override fun onDisabled(context: Context) {
        // The last widget's going away, so shut off the alarm.
        Log.d(DEBUG_TAG, "Shutting down now!")
        cancelAlarm(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // The only times we get onUpdate should be when a new widget is added or if the APK is
        // overwritten.  Do the alarms and fetchwork here.
        Log.d(DEBUG_TAG, "onUpdate!")

        cancelAlarm(context)
        scheduleAlarm(context)
        renderWidgets(context)
        DataFetchService.enqueueWork(
            context,
            Intent(DataFetchService.ACTION_FETCH_DATA)
        )
    }

    private fun renderWidgets(context: Context) {
        val event = DataFetchService.Companion.ResultEventLiveData.value
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val shift =
            if (event !== null && event.data !== null && event.data!!.omegaShift) DBShift.OmegaShift
            else getShift(Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")))
        val appWidgetIds =
            appWidgetManager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))

        Log.d(DEBUG_TAG, "Data came in: $event")

        appWidgetIds.forEach { id -> renderWidget(context, appWidgetManager, id, shift, event) }
    }

    private fun renderWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        id: Int,
        shift: DBShift,
        event: DataFetchService.Companion.ResultEvent?
    ) {
        val views = RemoteViews(context.packageName, R.layout.dbwidget)
        views.setImageViewResource(R.id.banner, getShiftDrawable(shift))
        views.setInt(
            R.id.banner,
            "setBackgroundColor",
            resolveColor(context.resources, getShiftBackgroundColor(shift))
        )

        if (event === null
            || ((event is DataFetchService.Companion.ResultEvent.Fetched
                    || event is DataFetchService.Companion.ResultEvent.Cached)
                    && event.data === null)) {
            // Either nothing's in the LiveData at all yet, or we somehow got a Fetched/Cached event
            // with no data.  Either way, we're quite confused.
            views.setViewVisibility(R.id.current_data, View.GONE)
            views.setViewVisibility(R.id.status, View.GONE)
            views.setViewVisibility(R.id.error, View.VISIBLE)
        } else if ((event is DataFetchService.Companion.ResultEvent.ErrorNoConnection
            || event is DataFetchService.Companion.ResultEvent.ErrorGeneral)
            && event.data === null) {
            // An error with no data!  Report the error as text.
                // If we've got no data at all, report the error as text.
                views.setViewVisibility(R.id.current_data, View.GONE)
                views.setViewVisibility(R.id.status, View.VISIBLE)
                views.setViewVisibility(R.id.error, View.GONE)

                views.setTextViewText(
                    R.id.status,
                    context.getString(
                        if (event is DataFetchService.Companion.ResultEvent.ErrorNoConnection)
                            R.string.error_noconnection
                        else
                            R.string.error_general))
        } else {
            // The valid data block!  Start with the current time.
            val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

            // Then, a new Calendar object for the end of the run, plus a few bonus hours to account
            // for Thank You Time running well over.
            val currentDonations = event.data!!.currentDonations
            val totalHours = DonationConverter.totalHoursForDonationAmount(currentDonations)
            val endPlusThankYou = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            endPlusThankYou.timeInMillis = event.data!!.runStartTimeMillis
            endPlusThankYou.add(Calendar.HOUR, totalHours + THANK_YOU_HOURS)

            // Reset visibilities...
            views.setViewVisibility(R.id.current_data, View.VISIBLE)
            views.setViewVisibility(R.id.status, View.GONE)
            views.setViewVisibility(R.id.error, View.GONE)
            views.setViewVisibility(R.id.nonfresh_error, View.GONE)

            // We always put the current donations up.
            views.setTextViewText(R.id.current_total,
                "\$${DecimalFormat("###,###,###,###.00").format(currentDonations)}")

            if(now.get(Calendar.MONTH) < Calendar.NOVEMBER
                || now.timeInMillis > endPlusThankYou.timeInMillis) {
                // If this is before November OR we're past the end of the run, display the data in
                // the past tense.
                views.setTextViewText(
                    R.id.hours_bussed,
                    context.getString(R.string.hours_bussed_end, totalHours)
                )
                views.setViewVisibility(R.id.to_next_hour, View.GONE)
            } else if(now.timeInMillis < event.data!!.runStartTimeMillis) {
                // If it's November and we're before the start of the run (implying we know the
                // start of the run and we're waiting for it), display the data in the future tense.
                views.setTextViewText(
                    R.id.hours_bussed,
                    context.getString(
                        R.string.hours_until_bus,
                        DateUtils.getRelativeTimeSpanString(
                            now.timeInMillis,
                            event.data!!.runStartTimeMillis,
                            DateUtils.MINUTE_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE
                        )
                    )
                )
                views.setViewVisibility(R.id.to_next_hour, View.VISIBLE)
                views.setTextViewText(
                    R.id.to_next_hour,
                    context.getString(
                        R.string.to_next_hour,
                        "\$${DecimalFormat("###,###,###,###.00").format(
                            DonationConverter.toNextHourFromDonationAmount(
                                currentDonations
                            )
                        )}"
                    )
                )
            } else {
                // The run's on!  Full data, now!  Go go go!
                views.setTextViewText(
                    R.id.hours_bussed,
                    context.getString(
                        R.string.hours_bussed,
                        (now.timeInMillis - event.data!!.runStartTimeMillis) / MILLIS_PER_HOUR,
                        totalHours
                    )
                )
                views.setViewVisibility(R.id.to_next_hour, View.VISIBLE)
                views.setTextViewText(
                    R.id.to_next_hour,
                    context.getString(
                        R.string.to_next_hour,
                        "\$${DecimalFormat("###,###,###,###.00").format(
                            DonationConverter.toNextHourFromDonationAmount(
                                currentDonations
                            )
                        )}"
                    )
                )

                // Now, if this is an error and it's been over ERROR_TIMEOUT_MILLIS since the last
                // fresh data, let the user know.
                if ((event is DataFetchService.Companion.ResultEvent.ErrorNoConnection
                            || event is DataFetchService.Companion.ResultEvent.ErrorGeneral)
                    && now.timeInMillis - event.data!!.fetchedAtMillis > ERROR_TIMEOUT_MILLIS) {
                    views.setViewVisibility(R.id.nonfresh_error, View.VISIBLE)
                    views.setTextViewText(
                        R.id.nonfresh_error, context.getString(
                            R.string.error_nonfresh, DateUtils.getRelativeTimeSpanString(
                                now.timeInMillis,
                                event.data!!.fetchedAtMillis,
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.FORMAT_ABBREV_RELATIVE
                            )
                        )
                    )
                } else {
                    views.setViewVisibility(R.id.nonfresh_error, View.GONE)
                }
            }
        }

        // With everything set, update the widget!
        appWidgetManager.updateAppWidget(id, views)
    }
}