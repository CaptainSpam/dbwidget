package net.exclaimindustries.dbwidget

import android.util.Log
import java.util.*
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Various static convenience methods for converting donations into hours.
 */
class DonationConverter {
    companion object {
        private const val DEBUG_TAG = "DonationConverter"

        /**
         * List of donation totals needed for each hour.  At time of writing, this holds enough data
         * for 182 hours of bussing, which is way more than has ever been bussed.  If it doesn't
         * exist in this list, chances are we don't need to be really accurate, so we fall back to
         * floating point math.
         */
        private val HOUR_THRESHOLDS = arrayOf(
            1.00,
            2.07,
            3.21,
            4.44,
            5.75,
            7.15,
            8.65,
            10.26,
            11.98,
            13.82,
            15.78,
            17.89,
            20.14,
            22.55,
            25.13,
            27.89,
            30.84,
            34.00,
            37.38,
            41.00,
            44.87,
            49.01,
            53.44,
            58.18,
            63.25,
            68.68,
            74.48,
            80.70,
            87.35,
            94.46,
            102.07,
            110.22,
            118.93,
            128.26,
            138.24,
            148.91,
            160.34,
            172.56,
            185.64,
            199.64,
            214.61,
            230.63,
            247.78,
            266.12,
            285.75,
            306.75,
            329.22,
            353.27,
            379.00,
            406.53,
            435.99,
            467.50,
            501.23,
            537.32,
            575.93,
            617.24,
            661.45,
            708.75,
            759.36,
            813.52,
            871.47,
            933.47,
            999.81,
            1070.80,
            1146.76,
            1228.03,
            1314.99,
            1408.04,
            1507.60,
            1614.13,
            1728.12,
            1850.09,
            1980.60,
            2120.24,
            2269.66,
            2429.53,
            2600.60,
            2783.64,
            2979.50,
            3189.06,
            3413.30,
            3653.23,
            3909.95,
            4184.65,
            4478.58,
            4793.08,
            5129.59,
            5489.66,
            5874.94,
            6287.19,
            6728.29,
            7200.27,
            7705.29,
            8245.66,
            8823.85,
            9442.52,
            10104.50,
            10812.81,
            11570.71,
            12381.66,
            13249.38,
            14177.83,
            15171.28,
            16234.27,
            17371.67,
            18588.69,
            19890.90,
            21284.26,
            22775.16,
            24370.42,
            26077.35,
            27903.76,
            29858.03,
            31949.09,
            34186.52,
            36580.58,
            39142.22,
            41883.18,
            44816.00,
            47954.12,
            51311.91,
            54904.74,
            58749.07,
            62862.51,
            67263.88,
            71973.36,
            77012.49,
            82404.37,
            88173.67,
            94346.83,
            100952.11,
            108019.75,
            115582.14,
            123673.89,
            132332.06,
            141596.30,
            151509.04,
            162115.68,
            173464.77,
            185608.31,
            198601.89,
            212505.02,
            227381.37,
            243299.07,
            260331.00,
            278555.17,
            298055.04,
            318919.89,
            341245.28,
            365133.45,
            390693.79,
            418043.36,
            447307.39,
            478619.91,
            512124.30,
            547974.01,
            586333.19,
            627377.51,
            671294.93,
            718286.58,
            768567.64,
            822368.37,
            879935.16,
            941531.62,
            1007439.84,
            1077961.62,
            1153419.94,
            1234160.33,
            1320552.56,
            1412992.24,
            1511902.69,
            1617736.88,
            1730979.46,
            1852149.03,
            1981800.46,
            2120527.49,
            2268965.41,
            2427793.99,
            2597740.57,
            2779583.41,
            2974155.25,
            3182347.12
        )

        /** Helper function to create the donation-to-hour lookup table. */
        private fun makeHourMap(): NavigableMap<Double, Int> {
            val map = TreeMap<Double, Int>()

            var hour = 1
            for (donation in HOUR_THRESHOLDS) {
                map[donation] = hour
                hour++
            }

            return map
        }

        private val HOUR_MAP = makeHourMap()

        /**
         * Calculates the total hours that will be bussed given the donation total.  This returns an
         * Int; it will not return fractional hours.  This may lose accuracy at very high values.
         *
         * @param current the donation total
         * @return the number of whole hours to be bussed
         */
        fun totalHoursForDonationAmount(current: Double): Int {
            // Due to wackiness involving floating point values, we're using a lookup table.
            if (current >= 3405112.42) {
                Log.w(
                    DEBUG_TAG,
                    "Donations of \$${current} shoot past ${HOUR_THRESHOLDS.size} hours and thus the end of the lookup table, so this may not be accurate..."
                )
                // ...unless we've shot past the table, in which case we're going to guess and it
                // likely won't be accurate (see aforementioned wackiness involving floating point
                // values).  Also, this means they've been bussing for a very long time.
                return floor(log10(1 + 0.07 * current) / log10(1.07)).toInt()
            }

            val entry = HOUR_MAP.floorEntry(current)
            if (entry === null)
                return 0
            return entry.value
        }

        private fun calculateTotalNeededForHour(hour: Int): Double {
            return ((1 - 1.07.pow(hour)) / .07) * -1
        }

        private fun calculateAmountNeededToNextHour(hour: Int): Double {
            return 1.07.pow(hour)
        }

        /**
         * Calculates the amount of donations needed for the next hour.  This should not return
         * zero in most cases; if Team Order has succeeded in bringing the total to exactly the
         * amount needed for an hour, this should return the NEXT hour's requirement.  However,
         * owing to floating point goofiness, at high enough values it may return inaccurate
         * results, including zero.
         *
         * @param current the current donation total
         * @return the amount needed for the next hour
         */
        fun toNextHourFromDonationAmount(current: Double): Double {
            var entry = HOUR_MAP.ceilingEntry(current)

            if (entry === null) {
                // The entry will be null if we've sailed past the end of the lookup table.  If so,
                // fall back to floating point math.
                return calculateTotalNeededForHour(totalHoursForDonationAmount(current) + 1) - current
            }

            var amount = entry.key

            if (amount <= current) {
                // If we're EXACTLY at the hour, try again with the next hour.  If the amount came
                // back as less than the hour, we might be dealing with floating point shenanigans,
                // so try again with the next hour anyway by bumping up the donations.
                entry = HOUR_MAP.ceilingEntry(current + 0.10)

                if (entry === null) {
                    // Same fallback, just with an extra hour tacked on.
                    return calculateTotalNeededForHour(totalHoursForDonationAmount(current) + 2) - current
                }

                amount = entry.key
            }

            return amount - current
        }
    }
}