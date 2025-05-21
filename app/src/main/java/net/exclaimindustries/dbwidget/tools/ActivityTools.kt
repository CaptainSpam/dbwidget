package net.exclaimindustries.dbwidget.tools

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat


/**
 * `ActivityTools` encompasses some stuff that's handy for Activities
 * but isn't, y'know, IN an Activity.
 */
object ActivityTools {
    /**
     * Deal with window insets.  The DB widget app isn't a Compose app yet, and since the only
     * Activity is the initial config, I'm torn between making it a Compose app or not even
     * bothering, so this will have to do for now.  Call this AFTER setContentView.
     */
    fun dealWithInsets(activity: AppCompatActivity, @IdRes id: Int) {
        val view = activity.findViewById<View>(id)
        val originalLayoutParams =
            MarginLayoutParams(view.layoutParams as MarginLayoutParams)

        // I guess we've got insets to deal with now?  Fine, let's deal with
        // them here.
        ViewCompat.setOnApplyWindowInsetsListener(
            view,
            OnApplyWindowInsetsListener { v: View?, windowInsets: WindowInsetsCompat? ->
                val insets = windowInsets!!.getInsets(WindowInsetsCompat.Type.systemBars())
                val mlp = v!!.layoutParams as MarginLayoutParams
                mlp.topMargin = originalLayoutParams.topMargin + insets.top
                mlp.leftMargin = originalLayoutParams.leftMargin + insets.left
                mlp.bottomMargin = originalLayoutParams.bottomMargin + insets.bottom
                mlp.rightMargin = originalLayoutParams.rightMargin + insets.right
                v.layoutParams = mlp
                WindowInsetsCompat.CONSUMED
            })

        // Also, API 35 does some... weird things with the status/navigation bar
        // colors.  We'll force the proper situation here.
        val dayMode = (activity.getResources()
            .configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        val insetsController =
            WindowCompat.getInsetsController(activity.window, activity.findViewById<View?>(id))
        insetsController.isAppearanceLightStatusBars = dayMode
        insetsController.isAppearanceLightNavigationBars = dayMode
    }
}
