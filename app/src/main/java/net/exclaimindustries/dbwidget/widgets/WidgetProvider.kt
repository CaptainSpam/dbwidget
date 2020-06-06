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
import android.net.Uri
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
                Log.d(DEBUG_TAG, "ALARM!!!!  Enqueueing work!")
                // Tell the service to try a fetch.  That will fire off updates on the LiveData
                // object for the widget to find later.  It'll be hilarious, trust me.
                DataFetchService.enqueueWork(
                    context,
                    Intent(DataFetchService.ACTION_FETCH_DATA)
                )

                // Then, reschedule for later down the line.
                scheduleAlarm(context)
            }
        }

        /**
         * Determines if a faster update schedule (every minute, as opposed to every six hours) is
         * needed.  The basic logic is:
         *
         * * If there is no valid event yet, a fast update *IS* needed.
         * * If there is a valid event but it contains no valid data, a fast update *IS* needed.
         * * If there is valid data and the start date is in the future, a fast update *IS* needed.
         * * If there is valid data, the start date is in the past, and the current donations
         *   indicate the end of the run (plus thank-you time) is in the future, a fast update *IS*
         *   needed.
         * * Otherwise (meaning there's valid data, the latest run is over, and we don't know when
         *   the next one begins), a fast update *is NOT* needed.
         */
        private fun needsFastUpdate(): Boolean {
            val data = DataFetchService.Companion.ResultEventLiveData.value?.data

            // Funny thing is, all of those cases neatly condense into this.
            return (data === null || endPlusThankYouMillis(data) > Date().time)
        }

        /**
         * The start of the run indicated by the data, plus the total number of hours that will be
         * bussed according to the current donations, plus a few extra hours for thank-you time.
         */
        private fun endPlusThankYouMillis(data: DataFetchService.Companion.ResultData): Long =
            data.runStartTimeMillis + ((data.totalHours + THANK_YOU_HOURS) * MILLIS_PER_HOUR)

        /**
         * Schedules the next alarm.  The time of said alarm depends on if we need fresh data
         * quickly or not.
         *
         * @see needsFastUpdate
         */
        private fun scheduleAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            if(needsFastUpdate()) {
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
            } else {
                // If we don't need per-minute updates, just schedule an alarm for the next time the
                // banner needs to update.
                Log.d(DEBUG_TAG, "Don't need a fast update; scheduling for the next banner update...")

                // The resolution we're dealing with in this check is the span of a month, so a
                // difference of a few time zones is trivial.  We can therefore always use Pacific
                // Time here, which will come in handy when rescheduling the alarm for banner
                // updates.
                val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"))

                when(cal.get(Calendar.HOUR_OF_DAY)) {
                    in 0..5 -> cal.set(Calendar.HOUR_OF_DAY, 6)
                    in 6..11 -> cal.set(Calendar.HOUR_OF_DAY, 12)
                    in 12..17 -> cal.set(Calendar.HOUR_OF_DAY, 18)
                    in 18..23 -> {
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.add(Calendar.DATE, 1)
                    }
                }
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)

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
            }
        }

        /**
         * Shuts off the alarm.  Should be called at shutdown time.  Note that the alarms normally
         * don't automatically repeat.
         */
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

        /** The right honorable Desert Bus for Hope shifts. */
        sealed class DBShift {
            /** Dawn Guard, the guardian of morning. */
            object DawnGuard: DBShift()
            /** Alpha Flight, the promise of a glorious afternoon. */
            object AlphaFlight: DBShift()
            /** Night Watch, the protector of the night. */
            object NightWatch: DBShift()
            /** Zeta Shift, where we don't even know, man. */
            object ZetaShift: DBShift()
            /** Omega Shift, when it all comes down to the end. */
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
        // When the first widget comes in, do an initial fetch call so we've got some data.
        Log.d(DEBUG_TAG, "Starting up now!")

        DataFetchService.enqueueWork(
            context,
            Intent(DataFetchService.ACTION_FETCH_DATA)
        )

        // Also, render up.
        renderWidgets(context)
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
            if (event?.data?.omegaShift == true) DBShift.OmegaShift
            else getShift(Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")))
        val appWidgetIds =
            appWidgetManager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))

        Log.d(DEBUG_TAG, "Rendering for data: $event")

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

        // If the user clicks anywhere on the widget, go to the DB website.  Even if that reveals
        // that its data may disagree with ours.
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.desertbus.org")),
            0
        )
        views.setOnClickPendingIntent(R.id.widget, pendingIntent)

        // Extract the event data, if there is any.
        val data = event?.data

        if (data === null) {
            // There's no data at all yet.  That means the error block goes on, at least.
            views.setViewVisibility(R.id.current_data, View.GONE)
            views.setViewVisibility(R.id.error_block, View.VISIBLE)

            if(event is DataFetchService.Companion.ResultEvent.ErrorNoConnection
                || event is DataFetchService.Companion.ResultEvent.ErrorGeneral) {
                // There's no data, but there IS an error!  If it's a Fetched or Cached response, we
                // shouldn't have gotten here, so we can just assume we're still in the initial
                // startup phase.
                views.setViewVisibility(R.id.status, View.VISIBLE)

                views.setTextViewText(
                    R.id.status,
                    context.getString(
                        if (event is DataFetchService.Companion.ResultEvent.ErrorNoConnection)
                            R.string.error_noconnection
                        else
                            R.string.error_general))
            } else {
                views.setViewVisibility(R.id.status, View.GONE)
            }
        } else {
            // The valid data block!  Start with the current time.
            val nowMillis = Date().time

            // Reset visibilities...
            views.setViewVisibility(R.id.current_data, View.VISIBLE)
            views.setViewVisibility(R.id.error_block, View.GONE)
            views.setViewVisibility(R.id.nonfresh_error, View.GONE)

            // We always put the current donations up.
            views.setTextViewText(R.id.current_total,
                "\$${DecimalFormat("###,###,###,###.00").format(data.currentDonations)}")

            if(nowMillis > endPlusThankYouMillis(data)) {
                // If we're past the end of the last-known run, display the data in the past tense.
                views.setTextViewText(
                    R.id.hours_bussed,
                    context.getString(R.string.hours_bussed_end, data.totalHours)
                )
                views.setViewVisibility(R.id.to_next_hour, View.GONE)
            } else if(nowMillis < data.runStartTimeMillis) {
                // If we're before the start of the run (implying we know the start of the run and
                // we're waiting for it), display the data in the future tense.
                views.setTextViewText(
                    R.id.hours_bussed,
                    context.getString(
                        R.string.hours_until_bus,
                        DateUtils.getRelativeTimeSpanString(
                            data.runStartTimeMillis,
                            nowMillis,
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
                                data.currentDonations
                            )
                        )}"
                    )
                )
            } else {
                // Otherwise, the run's on!  Full data, now!  Go go go!
                views.setTextViewText(
                    R.id.hours_bussed,
                    context.getString(
                        R.string.hours_bussed,
                        (nowMillis - data.runStartTimeMillis) / MILLIS_PER_HOUR,
                        data.totalHours
                    )
                )
                views.setViewVisibility(R.id.to_next_hour, View.VISIBLE)
                views.setTextViewText(
                    R.id.to_next_hour,
                    context.getString(
                        R.string.to_next_hour,
                        "\$${DecimalFormat("###,###,###,###.00").format(
                            DonationConverter.toNextHourFromDonationAmount(
                                data.currentDonations
                            )
                        )}"
                    )
                )

                // Now, if this is an error and it's been over ERROR_TIMEOUT_MILLIS since the last
                // fresh data, let the user know.
                if ((event is DataFetchService.Companion.ResultEvent.ErrorNoConnection
                            || event is DataFetchService.Companion.ResultEvent.ErrorGeneral)
                    && nowMillis - data.fetchedAtMillis > ERROR_TIMEOUT_MILLIS) {
                    views.setViewVisibility(R.id.nonfresh_error, View.VISIBLE)
                    views.setTextViewText(
                        R.id.nonfresh_error, context.getString(
                            R.string.error_nonfresh, DateUtils.getRelativeTimeSpanString(
                                data.fetchedAtMillis,
                                nowMillis,
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