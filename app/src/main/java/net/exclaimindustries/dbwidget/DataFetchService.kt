package net.exclaimindustries.dbwidget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.JobIntentService
import cz.msebera.android.httpclient.client.methods.HttpGet
import cz.msebera.android.httpclient.impl.client.BasicResponseHandler
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder
import org.json.JSONArray
import java.util.*

/**
 * Service that handles fetching data from the VST and broadcasting it to any widget instances that
 * might be paying attention, which is hopefully all of them, as this is sort of vital to the entire
 * operation.
 */
class DataFetchService : JobIntentService() {
    companion object {
        private const val debugTag = "DataFetchService"

        /** URL to get the current donation total. */
        private const val currentTotalUrl = "https://vst.ninja/milestones/latestTotal"

        /**
         * The year of the first Desert Bus for Hope, for the purposes of calculating the current
         * numbered Desert Bus.
         */
        private const val firstDesertBus = 2007

        /** The job's service ID. */
        private const val serviceJobId = 2001

        /** Convenience method for enqueuing work in to this service. */
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(
                context,
                DataFetchService::class.java,
                serviceJobId,
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
                    now.get(Calendar.YEAR) - firstDesertBus - 1
                } else {
                    // If it's November or later, it's likely THIS year's run.
                    now.get(Calendar.YEAR) - firstDesertBus
                }
            } else {
                // If the year was explicitly given, ignore the month entirely.
                year - firstDesertBus
            }

            // The stats URL uses DB labels that are numbered SEQUENTIALLY, not by "official" name
            // (that is, the Desert Bus that happened in 2017 is DB11, not DB2017).
            return "https://vst.ninja/DB${actualYear}/data/DB${actualYear}_stats.json"
        }
    }

    data class Data(
        val currentDonations: Double,
        val runStartTime: Date,
        val totalHours: Int,
        val costToNextHour: Double
    )

    override fun onHandleWork(intent: Intent) {
        Log.d(debugTag, "Welcome to work handling!")
        // TODO: Caching and rate-limiting?
        // Hello!  We're on a separate thread now!  Isn't that convenient?
        val currentDonations: Double
        val runStartTime: Date
        try {
            currentDonations = fetchCurrentDonations()
            runStartTime = fetchRunStartTime()
        } catch (e: Exception) {
            Log.e(debugTag, "Exception when fetching data:", e)
            TODO("Report a problem somehow; maybe another BroadcastIntent?")
        }

        // Massage the data into whatever's needed for display.
        val toBroadcast = Data(
            currentDonations,
            runStartTime,
            DonationConverter.totalHoursForDonationAmount(currentDonations),
            DonationConverter.toNextHourFromDonationAmount(currentDonations)
        )

        Log.d(debugTag, "Data: $toBroadcast")

        // Then send it out the door!
        TODO("Set up the BroadcastIntent")
    }

    private fun fetchCurrentDonations(): Double {
        // If something throws here, we'll just let onHandleWork handle it.
        val httpClient = HttpClientBuilder.create().build()
        val httpGet = HttpGet(currentTotalUrl)
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
}