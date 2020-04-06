package net.exclaimindustries.dbwidget

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.*

class DataFetchService : Service() {
    /** URL to get the current donation total. */
    private val currentTotalUrl = "https://vst.ninja/milestones/latestTotal"
    /**
     * The year of the first Desert Bus for Hope, for the purposes of calculating the current
     * numbered Desert Bus.
     */
    private val firstDesertBus = 2007

    private fun getStatsUrl(year: Int?): String {
        val actualYear: Int
        actualYear = if (year === null) {
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

        // The stats URL uses DB labels that are numbered SEQUENTIALLY, not by "official" name (that
        // is, the Desert Bus that happened in 2017 is DB11, not DB2017).
        return "https://vst.ninja/DB${actualYear}/data/DB${actualYear}_stats.json"
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}