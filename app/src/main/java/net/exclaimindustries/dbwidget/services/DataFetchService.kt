package net.exclaimindustries.dbwidget.services

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import androidx.core.app.JobIntentService
import androidx.lifecycle.LiveData
import cz.msebera.android.httpclient.client.HttpResponseException
import cz.msebera.android.httpclient.client.methods.HttpGet
import cz.msebera.android.httpclient.impl.client.BasicResponseHandler
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder
import net.exclaimindustries.dbwidget.tools.ConnectionStateAwareApplication.Companion.ConnectivityEvent
import net.exclaimindustries.dbwidget.tools.ConnectionStateAwareApplication.Companion.ConnectivityEventLiveData
import net.exclaimindustries.dbwidget.util.DonationConverter
import net.exclaimindustries.dbwidget.widgets.WidgetProvider
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

        /**
         * The prefix to all data URLs.  Under normal circumstances, this should be the VST's site,
         * "https://vst.ninja/".  This is here to make changing things for testing easier.
         */
        private const val URL_PREFIX = "https://vst.ninja/"

        /** URL to get the current donation total. */
        private const val CURRENT_TOTAL_URL = "${URL_PREFIX}milestones/latestTotal"

        /** URL to get the stats.  Replace "<YEAR>" with the actual numbered DB. */
        private const val STATS_URL_BASE = "${URL_PREFIX}DB<YEAR>/data/DB<YEAR>_stats.json"

        /** URL to tell if it's Omega Shift. */
        private const val OMEGA_CHECK_URL = "${URL_PREFIX}Resources/isitomegashift.html"

        /**
         * The offset used to determine the current numbered Desert Bus.  DB1 was in 2007, meaning
         * we subtract 2006 from 2007 to get 1, and so on.
         */
        private const val DB_YEAR_OFFSET = 2006

        /** The job's service ID. */
        private const val SERVICE_JOB_ID = 2001

        /**
         * The amount of time the data is considered to still be fresh enough to be returned, in
         * millis.  Currently 30 seconds.
         */
        private const val DATA_CACHE_TIME_MILLIS = 30000L

        /** Action name for fetching data. */
        const val ACTION_FETCH_DATA = "net.exclaimindustries.dbwidget.FETCH_DATA"

        /** Broadcast name for fetched data. */
        const val ACTION_DATA_FETCHED = "net.exclaimindustries.dbwidget.DATA_FETCHED"

        const val ERROR_GENERAL = 1
        const val ERROR_NO_NETWORK = 2

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
         * Note that there is no guarantee that the resulting URL will point to an extant file.
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
            return STATS_URL_BASE.replace("<YEAR>", actualYear.toString())
        }

        /** The data from a successful fetch. */
        data class ResultData(
            /** The current donation amount, as a Double. */
            val currentDonations: Double,
            /** The start time of the current run, in millis. */
            val runStartTimeMillis: Long,
            /**
             * The total hours that will be run with the given donations.  Use this with
             * runStartTimeMillis (with some flex to account for Omega Shift and the thank-you hour)
             * to guess if the run is still going on.
             */
            val totalHours: Int,
            /** The donations needed to reach the next hour. */
            val costToNextHour: Double,
            /** When this data was fetched, in millis.  Used for cache purposes. */
            val fetchedAtMillis: Long,
            /** Whether or not it's Omega Shift. */
            val omegaShift: Boolean
        )

        /** A result from a data fetch. */
        sealed class ResultEvent {
            /**
             * Either the live data, the last-known or cached data, or null if we haven't been able
             * to fetch any data yet.
             */
            abstract val data: ResultData?
            /** All is well, results are included. */
            data class Fetched(override val data: ResultData) : ResultEvent()
            /** The last-known data was new enough, so that was returned instead. */
            data class Cached(override val data: ResultData) : ResultEvent()
            /** There's no network connection, last-known results are included. */
            data class ErrorNoConnection(
                override val data: ResultData?,
                val exception: Exception?
            ) : ResultEvent()
            /**
             * There was some sort of error not covered otherwise, last-known results are included.
             */
            data class ErrorGeneral(
                override val data: ResultData?,
                val exception: Exception?
            ) : ResultEvent()
        }

        /** A LiveData observable thingamajig for results. */
        object ResultEventLiveData : LiveData<ResultEvent>() {
            internal fun notify(result: ResultEvent) {
                postValue(result)
            }
        }
    }

    override fun onHandleWork(intent: Intent) {
        // Hello!  We're on a separate thread now!  Isn't that convenient?
        Log.d(DEBUG_TAG, "Welcome to work handling!")

        val lastKnownData = ResultEventLiveData.value?.data
        if (lastKnownData !== null
            && (Date().time - lastKnownData.fetchedAtMillis < DATA_CACHE_TIME_MILLIS)) {
            // The cached data is still new enough, return that immediately.
            dispatchCachedData(lastKnownData)
            return
        }

        val currentDonations: Double
        val runStartTime: Long
        val omegaShift: Boolean
        try {
            currentDonations = fetchCurrentDonations()
            runStartTime = fetchRunStartTime()
            omegaShift = fetchOmegaShift()
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "Exception when fetching data:", e)
            dispatchError(
                if (isNetworkConnected())
                    ERROR_GENERAL
                else
                    ERROR_NO_NETWORK,
                e
            )
            return
        }

        // Massage the data and send it out the door!
        dispatchData(
            currentDonations,
            runStartTime,
            DonationConverter.totalHoursForDonationAmount(currentDonations),
            DonationConverter.toNextHourFromDonationAmount(currentDonations),
            omegaShift
        )
    }

    private fun isNetworkConnected(): Boolean {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val event = ConnectivityEventLiveData.value
            return event === null || event is ConnectivityEvent.ConnectivityAvailable
        } else {
            // If we're before Lollipop, we need to do this synchronously.
            @Suppress("DEPRECATION") val networkInfo =
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo !== null && networkInfo.isConnected
        }
    }

    private fun fetchCurrentDonations(): Double {
        // If something throws here, we'll just let onHandleWork handle it.
        val httpClient = HttpClientBuilder.create().build()
        val httpGet = HttpGet(CURRENT_TOTAL_URL)
        val handler = BasicResponseHandler()

        Log.d(DEBUG_TAG, "Fetching current donations...")
        return httpClient.execute(httpGet, handler).toDouble()
    }

    private fun fetchOmegaShift(): Boolean {
        // First, sanity-check.  If it's not November, it's clearly not Omega Shift.
        if(Calendar.getInstance().get(Calendar.MONTH) != Calendar.NOVEMBER) {
            Log.d(DEBUG_TAG, "It's not November, so returning false for Omega Shift...")
            return false
        }

        return try {
            // If it IS November, Omega Shift is a simple call that returns 1 or 0.
            val httpClient = HttpClientBuilder.create().build()
            val httpGet = HttpGet(OMEGA_CHECK_URL)
            val handler = BasicResponseHandler()
            Log.d(DEBUG_TAG, "Fetching Omega Shift...")
            httpClient.execute(httpGet, handler).trim() === "1"
        } catch(e: Exception) {
            // If, however, that threw an exception, just consider it to be false.  This check is
            // less vital and can be kicked down the road a bit.
            Log.d(DEBUG_TAG, "Omega Shift fetching had a problem, returning false...", e)
            false
        }
    }

    private fun fetchRunStartTime(): Long {
        // Step one, try to fetch THIS year's run data.
        val year = Calendar.getInstance().get(Calendar.YEAR)

        val httpClient = HttpClientBuilder.create().build()
        val handler = BasicResponseHandler()

        var httpGet:HttpGet

        try {
            httpGet = HttpGet(getStatsUrl(year))
            Log.d(DEBUG_TAG, "Attempting stats fetch on ${httpGet.uri}...")
            return JSONArray(httpClient.execute(httpGet, handler)).getJSONObject(0)
                .getLong("Year Start Actual UNIX Time") * 1000
        } catch (e: Exception) {
            // If that threw a 404, try backing off a year.
            if (e is HttpResponseException && e.statusCode == 404) {
                // If it throws THIS time, don't catch it; we've got a legit problem now and the
                // caller should bail out to an error response.
                httpGet = HttpGet(getStatsUrl(year - 1))
                Log.d(DEBUG_TAG, "404'd; backing off a year and trying stats fetch on ${httpGet.uri}...")
                return JSONArray(httpClient.execute(httpGet, handler)).getJSONObject(0)
                    .getLong("Year Start Actual UNIX Time") * 1000
            } else {
                // If it's NOT a 404 (or is some other exception), just throw it back.
                Log.e(DEBUG_TAG, "Fetching stats was a failure:", e)
                throw e
            }
        }
    }

    private fun dispatchData(
        currentDonations: Double,
        runStartTime: Long,
        totalHours: Int,
        costToNextHour: Double,
        omegaShift: Boolean
    ) {
        // Welcome to central dispatch, your official broadcast headquarters.
        Log.d(DEBUG_TAG, "Dispatching data...")

        val result = ResultData(
            currentDonations,
            runStartTime,
            totalHours,
            costToNextHour,
            Date().time,
            omegaShift
        )

        // Update the LiveData; having the last-seen data available on demand is good.
        ResultEventLiveData.notify(ResultEvent.Fetched(result))

        // Then, broadcast an Intent so that the receivers can have a Context with which to mess.
        broadcastDataFetched()
    }

    private fun dispatchCachedData(resultData: ResultData) {
        // Welcome to central dispatch's cache annex.
        Log.d(DEBUG_TAG, "Dispatching cached data...")
        ResultEventLiveData.notify(ResultEvent.Cached(resultData))
        broadcastDataFetched()
    }

    private fun dispatchError(errorCode: Int, exception: Exception? = null) {
        // Welcome to central dispatch's failure wing.
        Log.d(DEBUG_TAG, "Dispatching data for error code $errorCode...")
        val lastData = ResultEventLiveData.value?.data

        ResultEventLiveData.notify(
            if (errorCode == ERROR_NO_NETWORK)
                ResultEvent.ErrorNoConnection(lastData, exception)
            else
                ResultEvent.ErrorGeneral(lastData, exception)
        )
        broadcastDataFetched()
    }

    private fun broadcastDataFetched() {
        // This lets the WidgetProvider know the LiveData's been updated, which in turn means it'll
        // be listening in such a way that gives it a Context where LiveData's Observer doesn't.
        sendBroadcast(Intent(ACTION_DATA_FETCHED).setClass(this, WidgetProvider::class.java))
    }
}