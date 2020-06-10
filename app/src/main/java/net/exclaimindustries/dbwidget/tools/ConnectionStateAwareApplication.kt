package net.exclaimindustries.dbwidget.tools

import android.app.Application
import android.content.Context
import android.net.*
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.LiveData

/**
 * This Application subclass is in play to make sure we subscribe to network change events right
 * away, as we lost the ability to synchronously check them when Android Q rolled around.
 *
 * It is very important that you realize just how incorrect this solution probably is.  Subclassing
 * Application for something like this is what's generally referred to, in professional circles, as
 * something they call a "mistake".
 */
class ConnectionStateAwareApplication : Application() {
    companion object {
        /** Enum of connectivity data.  Subject to change for any reason. */
        sealed class ConnectivityEvent {
            /** Connectivity was just lost for the given Network. */
            data class ConnectivityLost(val network: Network) : ConnectivityEvent()
            /** Connectivity is now available for the given Network. */
            data class ConnectivityAvailable(val network: Network) : ConnectivityEvent()
        }

        /**
         * The LiveData thingy that sends out connectivity events.  Observe this to be on your way.
         *
         * Note that this depends on functionality not introduced until Lollipop.  You will need to
         * API-guard any calls to this.
         */
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        object ConnectivityEventLiveData : LiveData<ConnectivityEvent>() {
            internal fun notify(event: ConnectivityEvent) {
                postValue(event)
            }
        }

        /** Internal callback listener.  This does the listening. */
        @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
        private class ApplicationNetworkCallback :
            ConnectivityManager.NetworkCallback() {

            override fun onLost(network: Network) {
                super.onLost(network)
                ConnectivityEventLiveData.notify(ConnectivityEvent.ConnectivityLost(network))
            }

            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                ConnectivityEventLiveData.notify(ConnectivityEvent.ConnectivityAvailable(network))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // None of this existed before Lollipop, so if this is before then, this class effectively
        // does nothing at all.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Register the callback and hope for the best!
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NET_CAPABILITY_INTERNET).build(),
                ApplicationNetworkCallback()
            )
        }
    }
}