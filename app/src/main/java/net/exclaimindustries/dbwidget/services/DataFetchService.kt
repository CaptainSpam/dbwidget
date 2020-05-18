package net.exclaimindustries.dbwidget.services

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.JobIntentService
import androidx.lifecycle.LiveData
import cz.msebera.android.httpclient.client.methods.HttpGet
import cz.msebera.android.httpclient.impl.client.BasicResponseHandler
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder
import net.exclaimindustries.dbwidget.tools.ConnectionStateAwareApplication.Companion.ConnectivityEvent
import net.exclaimindustries.dbwidget.tools.ConnectionStateAwareApplication.Companion.ConnectivityEventLiveData
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

        /**
         * The amount of time the data is considered to still be fresh enough to be returned, in
         * millis.  Currently 60 seconds.
         */
        private const val DATA_CACHE_TIME_MILLIS = 60000L

        /** Action name for fetching data. */
        const val ACTION_FETCH_DATA = "net.exclaimindustries.dbwidget.FETCH_DATA"

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

        /** The data from a successful fetch. */
        data class ResultData(
            /**
             * The current donation amount, as a Double.
             *
             * TODO: Consider making a Long or String instead?  Precision is always two digits, and
             * though it's highly unlikely DB will reach high enough to mess up a Double's accuracy,
             * defensive coding is still a Good Thing™.
             */
            val currentDonations: Double,
            /** The start time of the current run, in millis. */
            val runStartTimeMillis: Long,
            /**
             * The total hours that will be run with the given donations.  Use this with
             * runStartTimeMillis (with some flex to account for Omega Shift and the thank-you hour)
             * to guess if the run is still going on.
             */
            val totalHours: Int,
            /**
             * The donations needed to reach the next hour.
             *
             * TODO: Same thing as with currentDonations.
             */
            val costToNextHour: Double,
            /** When this data was fetched, in millis.  Used for cache purposes. */
            val fetchedAtMillis: Long
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

    private var networkIsUp = false
    private val networkStateObserver =
        androidx.lifecycle.Observer<ConnectivityEvent> { event ->
            networkIsUp = event is ConnectivityEvent.ConnectivityAvailable
        }

    override fun onCreate() {
        super.onCreate()

        ConnectivityEventLiveData.observeForever(networkStateObserver)
    }

    override fun onDestroy() {
        super.onDestroy()

        ConnectivityEventLiveData.removeObserver(networkStateObserver)
    }

    override fun onHandleWork(intent: Intent) {
        // Hello!  We're on a separate thread now!  Isn't that convenient?
        Log.d(DEBUG_TAG, "Welcome to work handling!")

        val lastKnownData = ResultEventLiveData.value?.data
        if (lastKnownData !== null && (Date().time - lastKnownData.fetchedAtMillis < DATA_CACHE_TIME_MILLIS)) {
            // The cached data is still new enough, return that immediately.
            dispatchCachedData(lastKnownData)
            return
        }

        val currentDonations: Double
        val runStartTime: Long
        try {
            currentDonations = fetchCurrentDonations()
            runStartTime = fetchRunStartTime()
        } catch (e: Exception) {
            Log.e(DEBUG_TAG, "Exception when fetching data:", e)
            dispatchError(if (!networkIsUp) ERROR_NO_NETWORK else ERROR_GENERAL, e)
            return
        }

        // Massage the data and send it out the door!
        dispatchData(
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

    private fun fetchRunStartTime(): Long {
        // Same thing, just with more potential for parsing errors...
        val httpClient = HttpClientBuilder.create().build()
        val httpGet = HttpGet(getStatsUrl())
        val handler = BasicResponseHandler()
        // ...because THIS time, it's JSON!
        val json = JSONArray(httpClient.execute(httpGet, handler)).getJSONObject(0)

        return json.getLong("Year Start Actual UNIX Time") * 1000
    }

    private fun dispatchData(
        currentDonations: Double,
        runStartTime: Long,
        totalHours: Int,
        costToNextHour: Double
    ) {
        // Welcome to central LiveData dispatch, your official broadcast headquarters.
        Log.d(DEBUG_TAG, "Dispatching data...")
        ResultEventLiveData.notify(
            ResultEvent.Fetched(
                ResultData(
                    currentDonations,
                    runStartTime,
                    totalHours,
                    costToNextHour,
                    Date().time
                )
            )
        )
    }

    private fun dispatchCachedData(resultData: ResultData) {
        // Welcome to central LiveData dispatch's cache annex.
        Log.d(DEBUG_TAG, "Dispatching cached data...")
        ResultEventLiveData.notify(ResultEvent.Cached(resultData))
    }

    private fun dispatchError(errorCode: Int, exception: Exception? = null) {
        // Welcome to central LiveData dispatch's failure wing.
        Log.d(DEBUG_TAG, "Dispatching data for error code $errorCode...")
        ResultEventLiveData.notify(
            if (errorCode == ERROR_NO_NETWORK)
                ResultEvent.ErrorNoConnection(ResultEventLiveData.value?.data, exception)
            else
                ResultEvent.ErrorGeneral(ResultEventLiveData.value?.data, exception)
        )
    }
}