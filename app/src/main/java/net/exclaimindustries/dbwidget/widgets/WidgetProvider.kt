package net.exclaimindustries.dbwidget.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import net.exclaimindustries.dbwidget.R
import java.util.*

class WidgetProvider : AppWidgetProvider() {
    companion object {
        sealed class DBShift {
            object DawnGuard: DBShift()
            object AlphaFlight: DBShift()
            object NightWatch: DBShift()
            object ZetaShift: DBShift()
            object OmegaShift: DBShift()
        }

        private fun getShift(cal: Calendar): DBShift = when (cal.get(Calendar.HOUR_OF_DAY)) {
            // TODO: Needs Omega Shift!  That might take this out of the companion object...
            in 0..5 -> DBShift.ZetaShift
            in 6..11 -> DBShift.DawnGuard
            in 12..17 -> DBShift.AlphaFlight
            else -> DBShift.NightWatch
        }

        @DrawableRes
        private fun getShiftDrawable(shift: DBShift): Int = when (shift) {
            is DBShift.DawnGuard -> R.drawable.dbdawnguard
            is DBShift.AlphaFlight -> R.drawable.dbalphaflight
            is DBShift.NightWatch -> R.drawable.dbnightwatch
            is DBShift.ZetaShift -> R.drawable.dbzetashift
            is DBShift.OmegaShift -> R.drawable.dbomegashift
        }

        @ColorRes
        private fun getShiftBackgroundColor(shift: DBShift): Int = when (shift) {
            is DBShift.DawnGuard -> R.color.background_dawnguard
            is DBShift.AlphaFlight -> R.color.background_alphaflight
            is DBShift.NightWatch -> R.color.background_nightwatch
            is DBShift.ZetaShift -> R.color.background_zetashift
            is DBShift.OmegaShift -> R.color.background_omegashift
        }

        private fun resolveColor(res: Resources, @ColorRes color: Int): Int =
            @Suppress("DEPRECATION")
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                res.getColor(color, null)
            else
                res.getColor(color)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val shift = getShift(Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles")))

        appWidgetIds.forEach { id ->
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
}