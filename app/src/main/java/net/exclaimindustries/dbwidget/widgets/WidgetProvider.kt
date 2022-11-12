package net.exclaimindustries.dbwidget.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import net.exclaimindustries.dbwidget.R
import net.exclaimindustries.dbwidget.services.DataFetchService
import net.exclaimindustries.dbwidget.util.DonationConverter
import java.text.DecimalFormat
import java.util.*

class WidgetProvider : AppWidgetProvider() {
    companion object {
        private const val DEBUG_TAG = "WidgetProvider"

        /** The alarm timeout, in millis.  After this much time, a new fetch will be attempted. */
        private const val ALARM_TIMEOUT_MILLIS = 120000L

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

        /** Action name for forcing a refresh. */
        private const val FORCE_REFRESH = "net.exclaimindustries.dbwidget.FORCE_REFRESH"

        /** Pref key fragment for using Rustproof Bee Shed banners. */
        private const val PREF_BEESHED = "RustproofBeeShed"

        /** Pref key fragment for using the vintage Omega Shift banner. */
        private const val PREF_VINTAGEOMEGASHIFT = "VintageOmegaShift"

        /** The known prefs. */
        enum class Prefs {
            /** Corresponds to PREF_BEESHED. */
            BEESHED,
            /** Corresponds to PREF_VINTAGEOMEGASHIFT. */
            VINTAGEOMEGASHIFT,
        }

        /** Get the API-appropriate PendingIntent mutability flag. */
        private fun pendingIntentMutabilityFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        /** Get the key for the given pref of the given widget ID. */
        fun prefKeyFor(id: Int, pref: Prefs): String = "widget${id}${
            when (pref) {
                Prefs.BEESHED -> PREF_BEESHED
                Prefs.VINTAGEOMEGASHIFT -> PREF_VINTAGEOMEGASHIFT
            }
        }"

        /**
         * Determines if a faster update schedule (ALARM_TIMEOUT_MILLIS, as opposed to waiting for
         * the next shift change) is needed.  The basic logic is:
         *
         * * If there is no valid event yet, a fast update *IS* needed.
         * * If there is a valid event but it contains no valid data, a fast update *IS* needed.
         * * If there is valid data and the start date is in the future, a fast update *IS* needed.
         * * If there is valid data, the start date is in the past, and the current donations
         *   indicate the end of the run (plus thank-you time) is in the future, a fast update *IS*
         *   needed.
         * * Otherwise (meaning there's valid data, the latest run is over, and we don't know when
         *   the next one begins), a fast update *is NOT* needed.
         *
         * That is to say, a fast update is needed if there is an active run, meaning we want more
         * frequent updates as donations come in.  If the run isn't active, and thus the donation
         * count generally isn't going up, we only need to update the display for shift banners.
         */
        private fun needsFastUpdate(): Boolean {
            val data = DataFetchService.Companion.ResultEventLiveData.value?.data

            Log.d(DEBUG_TAG, "Checking fast update; ${if (data === null) "null" else endPlusThankYouMillis(data)} against ${Date().time}")

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
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.add(Calendar.MILLISECOND, ALARM_TIMEOUT_MILLIS.toInt())

                Log.d(
                    DEBUG_TAG,
                    "Setting alarm for ${cal.timeInMillis}..."
                )

                // We'll use plain alarms and reschedule as needed.  We'll do this primarily because
                // we DON'T want this to use a wakeup alarm, and we DO want it to be able to drift
                // as need be.  Excessive checks would be pointless.
                alarmManager.set(
                    AlarmManager.RTC,
                    cal.timeInMillis,
                    PendingIntent.getBroadcast(
                        context.applicationContext,
                        0,
                        Intent(context, WidgetProvider::class.java).setAction(CHECK_ALARM_ACTION),
                        pendingIntentMutabilityFlag()
                    )
                )
            } else {
                // If we don't need per-minute updates, just schedule an alarm for the next time the
                // banner needs to update.  This one should be Pacific time (potentially with DST)
                // to match the Moonbase.
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

                Log.d(DEBUG_TAG, "Setting alarm for the next banner update (${cal.timeInMillis})...")

                alarmManager.set(
                    AlarmManager.RTC,
                    cal.timeInMillis,
                    PendingIntent.getBroadcast(
                        context.applicationContext,
                        0,
                        Intent(context, WidgetProvider::class.java).setAction(CHECK_ALARM_ACTION),
                        pendingIntentMutabilityFlag()
                    )
                )
            }
        }

        /**
         * Shuts off the alarm.  Should be called at shutdown time.  Note that the alarms don't
         * automatically repeat.
         */
        private fun cancelAlarm(context: Context) {
            Log.d(DEBUG_TAG, "Canceling the alarm...")
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            alarmManager.cancel(
                PendingIntent.getBroadcast(
                    context.applicationContext,
                    0,
                    Intent(context, WidgetProvider::class.java).setAction(CHECK_ALARM_ACTION),
                    pendingIntentMutabilityFlag()
                )
            )
        }

        /** The right honorable Desert Bus for Hope shifts. */
        enum class DBShift {
            /** Dawn Guard, the guardian of morning. */
            DAWNGUARD,
            /** Alpha Flight, the promise of a glorious afternoon. */
            ALPHAFLIGHT,
            /** Night Watch, the protector of the night. */
            NIGHTWATCH,
            /** Zeta Shift, where we don't even know, man. */
            ZETASHIFT,
            /** Omega Shift, when it all comes down to the end. */
            OMEGASHIFT,
            /** Beta Flight, when the bees just show up for some reason. */
            BETAFLIGHT,
            /** Dusk Guard, because bees?  The lore's still kinda hazy there. */
            DUSKGUARD
        }

        /** Gets the shift associated with the given Calendar. */
        fun getShift(cal: Calendar, beeShed: Boolean = false): DBShift = when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 0..5 -> DBShift.ZETASHIFT
            in 6..11 -> DBShift.DAWNGUARD
            in 12..17 -> if(beeShed) DBShift.BETAFLIGHT else DBShift.ALPHAFLIGHT
            else -> if(beeShed) DBShift.DUSKGUARD else DBShift.NIGHTWATCH
        }

        /** Gets the banner Drawable associated with the given shift. */
        @DrawableRes
        private fun getShiftDrawable(shift: DBShift, vintageOmega: Boolean = false): Int = when (shift) {
            DBShift.DAWNGUARD -> R.drawable.dbdawnguard
            DBShift.ALPHAFLIGHT -> R.drawable.dbalphaflight
            DBShift.BETAFLIGHT -> R.drawable.dbbetaflight
            DBShift.NIGHTWATCH -> R.drawable.dbnightwatch
            DBShift.DUSKGUARD -> R.drawable.dbduskguard
            DBShift.ZETASHIFT -> R.drawable.dbzetashift
            DBShift.OMEGASHIFT -> when(vintageOmega) {
                true -> R.drawable.dbomegashift
                false -> R.drawable.dbomegashift2021
            }
        }

        /** Gets the background color associated with the given shift. */
        @ColorRes
        private fun getShiftBackgroundColor(shift: DBShift, vintageOmega: Boolean = false): Int = when (shift) {
            DBShift.DAWNGUARD -> R.color.background_dawnguard
            DBShift.ALPHAFLIGHT -> R.color.background_alphaflight
            DBShift.BETAFLIGHT -> R.color.background_betaflight
            DBShift.NIGHTWATCH -> R.color.background_nightwatch
            DBShift.DUSKGUARD -> R.color.background_duskguard
            DBShift.ZETASHIFT -> R.color.background_zetashift
            DBShift.OMEGASHIFT -> when(vintageOmega) {
                true -> R.color.background_omegashift
                false -> R.color.background_omegashift2021
            }
        }

        /**
         * Render all the widgets known to the given Context (which should hopefully mean the DB
         * Widget instance).
         */
        private fun renderWidgets(context: Context) {
            val event = DataFetchService.Companion.ResultEventLiveData.value
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds =
                appWidgetManager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))

            Log.d(DEBUG_TAG, "Rendering for data: $event")

            appWidgetIds.forEach { id ->
                run {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    renderWidget(
                        context,
                        appWidgetManager,
                        id,
                        if (event?.data?.omegaShift == true) DBShift.OMEGASHIFT
                        else getShift(
                            Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")),
                            prefs.getBoolean(prefKeyFor(id, Prefs.BEESHED), false)
                        ),
                        event
                    )
                }
            }
        }

        /** Actually render a widget. */
        fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            id: Int,
            shift: DBShift,
            event: DataFetchService.Companion.ResultEvent?
        ) {
            val views = RemoteViews(context.packageName, R.layout.dbwidget)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val vintageOmega = prefs.getBoolean(prefKeyFor(id, Prefs.VINTAGEOMEGASHIFT), false)
            views.setImageViewResource(R.id.banner, getShiftDrawable(shift, vintageOmega))
            views.setInt(
                R.id.banner,
                "setBackgroundColor",
                ResourcesCompat.getColor(context.resources,
                    getShiftBackgroundColor(shift, vintageOmega),
                    null)
            )

            // If the user clicks anywhere on the widget, go to the DB website, even if that reveals
            // that its data may disagree with ours.
            val pendingIntent = PendingIntent.getActivity(
                context.applicationContext,
                0,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.desertbus.org")),
                pendingIntentMutabilityFlag()
            )
            views.setOnClickPendingIntent(R.id.widget, pendingIntent)

            // Or, if the user clicks on the refresh button (or spinner), force a refresh.
            val refreshIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                Intent(context, WidgetProvider::class.java).setAction(FORCE_REFRESH),
                pendingIntentMutabilityFlag()
            )
            views.setOnClickPendingIntent(R.id.spinner, refreshIntent)
            views.setOnClickPendingIntent(R.id.refresh, refreshIntent)

            // Set the spinner a-spinnin', if need be.  If need not be, stop it from a-spinnin'.
            if(event is DataFetchService.Companion.ResultEvent.Fetching) {
                Log.d(DEBUG_TAG, "Fetch in progress, spinning the spinny thing...")
                views.setViewVisibility(R.id.refresh, View.GONE)
                views.setViewVisibility(R.id.spinner, View.VISIBLE)
            } else {
                Log.d(DEBUG_TAG, "Not actively fetching, stopping the spinny bits...")
                views.setViewVisibility(R.id.refresh, View.VISIBLE)
                views.setViewVisibility(R.id.spinner, View.GONE)
            }

            // Extract the event data, if there is any.
            val data = event?.data

            if (data === null) {
                // There's no data at all yet.  That means the error block goes on, at least.
                views.setViewVisibility(R.id.current_data, View.GONE)
                views.setViewVisibility(R.id.error_block, View.VISIBLE)

                if(event is DataFetchService.Companion.ResultEvent.ErrorNoConnection
                    || event is DataFetchService.Companion.ResultEvent.ErrorGeneral) {
                    // There's no data, but there IS an error!  If it's a Fetched or Cached
                    // response, we shouldn't have gotten here, so we can just assume we're still in
                    // the initial startup phase.
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
                views.setTextViewText(
                    R.id.current_total,
                    "\$${DecimalFormat("###,###,###,##0.00").format(data.currentDonations)}"
                )

                if(nowMillis > endPlusThankYouMillis(data)) {
                    // If we're past the end of the last-known run, display the data in the past
                    // tense.
                    views.setTextViewText(
                        R.id.hours_bussed,
                        context.resources.getQuantityString(
                            R.plurals.hours_bussed_end,
                            data.totalHours,
                            data.totalHours
                        )
                    )
                    views.setViewVisibility(R.id.to_next_hour, View.GONE)
                } else if(nowMillis < data.runStartTimeMillis) {
                    // If we're before the start of the run (implying we know the start of the run
                    // and we're waiting for it), display the data in the future tense.
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
                            "\$${
                                DecimalFormat("###,###,###,##0.00").format(
                                    DonationConverter.toNextHourFromDonationAmount(
                                        data.currentDonations
                                    )
                                )
                            }"
                        )
                    )
                } else {
                    // Otherwise, the run's on!  Full data, now!  Go go go!
                    val hoursBussed =
                        ((nowMillis - data.runStartTimeMillis) / MILLIS_PER_HOUR).toInt()

                    views.setTextViewText(
                        R.id.hours_bussed,
                        context.resources.getQuantityString(
                            R.plurals.hours_bussed,
                            hoursBussed, hoursBussed, data.totalHours
                        )
                    )
                    views.setViewVisibility(R.id.to_next_hour, View.VISIBLE)
                    views.setTextViewText(
                        R.id.to_next_hour,
                        context.getString(
                            R.string.to_next_hour,
                            "\$${
                                DecimalFormat("###,###,###,##0.00").format(
                                    DonationConverter.toNextHourFromDonationAmount(
                                        data.currentDonations
                                    )
                                )
                            }"
                        )
                    )

                    // Now, if this is an error and it's been over ERROR_TIMEOUT_MILLIS since the
                    // last fresh data, let the user know.
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // If this is the data fetched action, the superclass didn't handle it.  That's where we
        // step in...
        when(intent.action) {
            DataFetchService.ACTION_DATA_FETCHED, DataFetchService.ACTION_FETCHING -> renderWidgets(
                context
            )
            CHECK_ALARM_ACTION, FORCE_REFRESH -> {
                Log.d(
                    DEBUG_TAG, when (intent.action) {
                        CHECK_ALARM_ACTION -> "ALARM!!!!  Enqueueing work!"
                        FORCE_REFRESH -> "FORCED REFRESH!!!!  Enqueueing work!"
                        else -> "I GUESS WE'RE JUST GOING NOW?"
                    }
                )
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

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // A widget's gone away, so we should clean up its prefs.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        appWidgetIds.forEach{ id -> run {
                prefs.edit {
                    this.remove(prefKeyFor(id, Prefs.BEESHED))
                    this.remove(prefKeyFor(id, Prefs.VINTAGEOMEGASHIFT))
                }
            }
        }
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
}