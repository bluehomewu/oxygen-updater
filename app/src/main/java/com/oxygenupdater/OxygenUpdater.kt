package com.oxygenupdater

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.getSystemService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.oxygenupdater.extensions.attachWithLocale
import com.oxygenupdater.internal.settings.PrefManager
import com.oxygenupdater.utils.DatabaseMigrations
import com.oxygenupdater.utils.Logger.logError
import com.oxygenupdater.utils.MD5
import com.oxygenupdater.utils.NotificationUtils
import com.oxygenupdater.utils.ThemeUtils
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class OxygenUpdater : Application() {

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        /**
         * This callback is used in both [ConnectivityManager.registerDefaultNetworkCallback] and [ConnectivityManager.registerNetworkCallback].
         *
         * The former can be used only in API 24 and above, while the latter is recommended for API 21+.
         * The former is more robust, as it presents an accurate network availability status for all connections,
         * while the latter only works for the [NetworkRequest] that's passed into the function.
         *
         * This has the undesired effect of marking the network connection as "lost" after a period of time.
         * To combat this on older API levels, we're using the deprecated API to confirm the network connectivity status.
         */
        override fun onLost(network: Network) {
            @Suppress("DEPRECATION")
            val networkAvailability = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                getSystemService<ConnectivityManager>()
                    ?.activeNetworkInfo
                    ?.isConnectedOrConnecting == true
            } else {
                false
            }

            _isNetworkAvailable.postValue(networkAvailability)
        }

        override fun onAvailable(network: Network) {
            _isNetworkAvailable.postValue(true)
        }
    }

    override fun attachBaseContext(
        base: Context
    ) = super.attachBaseContext(base.attachWithLocale())

    override fun onCreate() {
        setupKoin()
        AppCompatDelegate.setDefaultNightMode(ThemeUtils.translateThemeToNightMode(this))
        super.onCreate()

        setupCrashReporting()
        setupNetworkCallback()
        setupMobileAds()

        val notificationUtils by inject<NotificationUtils>()
        // Support functions for Android 8.0 "Oreo" and up.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationUtils.deleteOldNotificationChannels()
            notificationUtils.createNewNotificationGroupsAndChannels()
        }

        // Save app's version code to aid in future migrations (added in 5.4.0)
        PrefManager.putInt(PrefManager.PROPERTY_VERSION_CODE, BuildConfig.VERSION_CODE)
        DatabaseMigrations.deleteLocalBillingDatabase(this)
        migrateOldSettings()
    }

    private fun setupKoin() {
        startKoin {
            // use AndroidLogger as Koin Logger - default Level.INFO
            androidLogger(Level.ERROR)
            // use the Android context given there
            androidContext(this@OxygenUpdater)
            // module list
            modules(allModules)
        }
    }

    private fun setupNetworkCallback() {
        getSystemService<ConnectivityManager>()?.apply {
            // Posting initial value is required, as [networkCallback]'s
            // methods get called only when network connectivity changes
            @Suppress("DEPRECATION")
            _isNetworkAvailable.postValue(activeNetworkInfo?.isConnectedOrConnecting == true)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    registerDefaultNetworkCallback(networkCallback)
                } else {
                    registerNetworkCallback(
                        NetworkRequest.Builder().build(),
                        networkCallback
                    )
                }
            } catch (e: SecurityException) {
                logError(TAG, "Couldn't setup network callback", e)
            }
        }
    }

    private fun setupMobileAds() {
        // If it's a debug build, add current device's ID to the list of test device IDs for ads
        if (BuildConfig.DEBUG) {
            @SuppressLint("HardwareIds")
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            val deviceId = MD5.calculateMD5(androidId).uppercase()
            ADS_TEST_DEVICES.add(deviceId)
        }

        val requestConfiguration = RequestConfiguration.Builder()
            .setTestDeviceIds(ADS_TEST_DEVICES)
            .build()

        MobileAds.initialize(this) {}
        MobileAds.setRequestConfiguration(requestConfiguration)

        // By default video ads run at device volume, which could be annoying
        // to some users. We're reducing ad volume to be 10% of device volume.
        // Note that this doesn't always guarantee that ads will run at a
        // reduced volume. This is either a longstanding SDK bug or due to
        // an undocumented behaviour.
        MobileAds.setAppVolume(0.1f)
    }

    /**
     * Syncs analytics and crashlytics collection to user's preference.
     *
     * @param shouldShareLogs user's preference for log sharing. Note that if
     * it's set to false, it does not take effect until the next app launch.
     *
     * @see [FirebaseAnalytics.setAnalyticsCollectionEnabled]
     * @see [FirebaseCrashlytics.setCrashlyticsCollectionEnabled]
     */
    fun setupCrashReporting(
        shouldShareLogs: Boolean = PrefManager.getBoolean(
            PrefManager.PROPERTY_SHARE_ANALYTICS_AND_LOGS,
            true
        )
    ) {
        val analytics by inject<FirebaseAnalytics>()
        val crashlytics by inject<FirebaseCrashlytics>()

        // Sync analytics collection to user's preference
        analytics.setAnalyticsCollectionEnabled(shouldShareLogs)
        // Sync crashlytics collection to user's preference, but only if we're on a release build
        crashlytics.setCrashlyticsCollectionEnabled(shouldShareLogs && !BuildConfig.DEBUG)
    }

    /**
     * Migrate settings from old versions of the app, if any
     */
    @Suppress("DEPRECATION")
    private fun migrateOldSettings() {
        // App version 2.4.6: Migrated old setting Show if system is up to date (default: ON) to Advanced mode (default: OFF).
        if (PrefManager.contains(PrefManager.PROPERTY_SHOW_IF_SYSTEM_IS_UP_TO_DATE)) {
            PrefManager.putBoolean(
                PrefManager.PROPERTY_ADVANCED_MODE,
                !PrefManager.getBoolean(
                    PrefManager.PROPERTY_SHOW_IF_SYSTEM_IS_UP_TO_DATE,
                    true
                )
            )
            PrefManager.remove(PrefManager.PROPERTY_SHOW_IF_SYSTEM_IS_UP_TO_DATE)
        }

        // App version 5.2.0+: no longer used. We now configure capping in the AdMob dashboard itself.
        if (PrefManager.contains(PrefManager.PROPERTY_LAST_NEWS_AD_SHOWN)) {
            PrefManager.remove(PrefManager.PROPERTY_LAST_NEWS_AD_SHOWN)
        }
    }

    @Suppress("unused")
    companion object {
        private const val TAG = "OxygenUpdater"

        @Suppress("ObjectPropertyName")
        private val _isNetworkAvailable = MutableLiveData<Boolean>()
        val isNetworkAvailable: LiveData<Boolean>
            get() = _isNetworkAvailable

        // Test devices for ads.
        private val ADS_TEST_DEVICES = mutableListOf(
            AdRequest.DEVICE_ID_EMULATOR
        )

        // Permissions constants
        const val PERMISSION_REQUEST_CODE = 200
        const val DOWNLOAD_FILE_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE
        const val VERIFY_FILE_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE

        const val NUMBER_OF_INSTALL_GUIDE_PAGES = 5
        const val APP_USER_AGENT = "Oxygen_updater_" + BuildConfig.VERSION_NAME
        const val UNABLE_TO_FIND_A_MORE_RECENT_BUILD = "unable to find a more recent build"
        const val NETWORK_CONNECTION_ERROR = "NETWORK_CONNECTION_ERROR"
        const val SERVER_MAINTENANCE_ERROR = "SERVER_MAINTENANCE_ERROR"
        const val APP_OUTDATED_ERROR = "APP_OUTDATED_ERROR"

        fun buildAdRequest(): AdRequest = AdRequest.Builder().build()
    }
}
