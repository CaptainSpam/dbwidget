package net.exclaimindustries.dbwidget.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.JobIntentService
import cz.msebera.android.httpclient.client.methods.HttpGet
import cz.msebera.android.httpclient.impl.client.BasicResponseHandler
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder
import net.exclaimindustries.dbwidget.util.DonationConverter
import org.json.JSONArray
import java.util.*

/**
 * Service that handles fetching data from the VST and broadcasting it to any widget instances that
 * might be paying attention, which is hopefully all of them, as this is sort of vital to the entire
 * operation.
 */
class DataFetchService : JobIntentService() {
    companion object {
        private const val DEBUG_TAG = "DataFetchService"

        /** URL to get the current donation total. */
        private const val CURRENT_TOTAL_URL = "https://vst.ninja/milestones/latestTotal"

        /**
         * The offset used to determine the current numbered Desert Bus.  DB1 was in 2007, meaning
         * we subtract 2006 from 2007 to get 1, and so on.
         */
        private const val DB_YEAR_OFFSET = 2006

        /** The job's service ID. */
        private const val SERVICE_JOB_ID = 2001

        /** Action name for fetching data. */
        const val ACTION_FETCH_DATA = "net.exclaimindustries.dbwidget.FETCH_DATA"

        /** Action name for the response broadcast. */
        const val ACTION_FETCH_RESPONSE = "net.exclaimindustries.dbwidget.FETCH_RESPONSE"

        const val EXTRA_STUFF = "net.exclaimindustries.dbwidget.EXTRA_STUFF"
        const val EXTRA_CURRENT_DONATIONS =
            "net.exclaimindustries.dbwidget.EXTRA_CURRENT_DONATIONS"
        const val EXTRA_RUN_START = "net.exclaimindustries.dbwidget.EXTRA_RUN_START"
        const val EXTRA_TOTAL_HOURS = "net.exclaimindustries.dbwidget.EXTRA_TOTAL_HOURS"
        const val EXTRA_COST_TO_NEXT_HOUR =
            "net.exclaimindustries.dbwidget.EXTRA_COST_TO_NEXT_HOUR"

        /** Convenience method for enqueuing work in to this service. */
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(
                context,
                DataFetchService::class.java,
                SERVICE_JOB_ID,
                work
            )
        }

        /**
         * Generates the stats URL based on either the current date or the given year.  Stats are
         * kept for all DB runs, and there's no default URL that pulls the "current" run, so we need
         * to assemble a URL for any data beyond the current donation total.
         *
         * If the year is null, this will use the current year minus one if it's before November
         * (in which case the current run has no meaningful data, so check the PREVIOUS year) or the
         * current year if it's November or December (in which case either the run is live, we at
         * least know when it'll start, or the run has concluded and the ending stats are what the
         * user wants).
         *
         * If the year is not null, this will use the given year unmodified, regardless of the
         * current date.
         *
         * @param year the year to use, or null for today's year
         */
        private fun getStatsUrl(year: Int? = null): String {
            val actualYear = if (year === null) {
                val now = Calendar.getInstance()
                if (now.get(Calendar.MONTH) < Calendar.NOVEMBER) {
                    // If it's before November, we're likely talking about LAST year's DB run.
                    now.get(Calendar.YEAR) - DB_YEAR_OFFSET - 1
                } else {
                    // If it's November or later, it's likely THIS year's run.
                    now.get(Calendar.YEAR) - DB_YEAR_OFFSET
                }
            } else {
                // If the year was explicitly given, ignore the month entirely.
                year - DB_YEAR_OFFSET
            }

            // The stats URL uses DB labels that are numbered SEQUENTIALLY, not by "official" name
            // (that is, the Desert Bus that happened in 2017 is DB11, not DB2017).
            return "https://vst.ninja/DB${actualYear}/data/DB${actualYear}_stats.json"
        }
    }

    override fun onHandleWork(intent: Intent) {
        Log.d(DEBUG_TAG, "Welcome to work handling!")
        // TODO: Caching and rate-limiting?
        // Hello!  We're on a separate thread now!  Isn't that convenient?
        val currentDonations: Double
        val runStartTime: Date
        try {
            currentDonations = fetchCurrentDonations()
            runStartTime = fetchRunStartTime()
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "Exception when fetching data:", e)
            TODO("Report a problem somehow; maybe another BroadcastIntent?")
        }

        // Massage the data and send it out the door!
        dispatchIntent(
            currentDonations,
            runStartTime,
            DonationConverter.totalHoursForDonationAmount(currentDonations),
            DonationConverter.toNextHourFromDonationAmount(currentDonations)
        )
    }

    private fun fetchCurrentDonations(): Double {
        // If something throws here, we'll just let onHandleWork handle it.
        val httpClient = HttpClientBuilder.create().build()
        val httpGet = HttpGet(CURRENT_TOTAL_URL)
        val handler = BasicResponseHandler()
        return httpClient.execute(httpGet, handler).toDouble()
    }

    private fun fetchRunStartTime(): Date {
        // Same thing, just with more potential for parsing errors...
        val httpClient = HttpClientBuilder.create().build()
        val httpGet = HttpGet(getStatsUrl())
        val handler = BasicResponseHandler()
        // ...because THIS time, it's JSON!
        val json = JSONArray(httpClient.execute(httpGet, handler)).getJSONObject(0)
        val startTime = json.getLong("Year Start Actual UNIX Time") * 1000

        return Date(startTime)
    }

    private fun dispatchIntent(
        currentDonations: Double,
        runStartTime: Date,
        totalHours: Int,
        costToNextHour: Double
    ) {
        // Welcome to central Intent dispatch, your official broadcast headquarters.
        val intent = Intent(ACTION_FETCH_RESPONSE)

        val bun = Bundle()
        bun.putDouble(EXTRA_CURRENT_DONATIONS, currentDonations)
        bun.putSerializable(EXTRA_RUN_START, runStartTime)
        bun.putInt(EXTRA_TOTAL_HOURS, totalHours)
        bun.putDouble(EXTRA_COST_TO_NEXT_HOUR, costToNextHour)

        intent.putExtra(EXTRA_STUFF, bun)

        // And away it goes!
        Log.d(DEBUG_TAG,"Dispatching intent...")
        sendBroadcast(intent)
    }
}