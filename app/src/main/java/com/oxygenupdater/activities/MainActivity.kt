package com.oxygenupdater.activities

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Observer
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus.DOWNLOADED
import com.google.android.play.core.install.model.InstallStatus.DOWNLOADING
import com.google.android.play.core.install.model.InstallStatus.FAILED
import com.google.android.play.core.install.model.InstallStatus.PENDING
import com.google.android.play.core.install.model.UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
import com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE
import com.oxygenupdater.BuildConfig
import com.oxygenupdater.OxygenUpdater
import com.oxygenupdater.OxygenUpdater.Companion.buildAdRequest
import com.oxygenupdater.R
import com.oxygenupdater.databinding.ActivityMainBinding
import com.oxygenupdater.dialogs.Dialogs
import com.oxygenupdater.dialogs.MessageDialog
import com.oxygenupdater.dialogs.ServerMessagesDialogFragment
import com.oxygenupdater.extensions.fullWidthAnchoredAdaptiveBannerAd
import com.oxygenupdater.extensions.openPlayStorePage
import com.oxygenupdater.extensions.reduceDragSensitivity
import com.oxygenupdater.fragments.AboutFragment
import com.oxygenupdater.fragments.DeviceInformationFragment
import com.oxygenupdater.fragments.NewsListFragment
import com.oxygenupdater.fragments.SettingsFragment
import com.oxygenupdater.fragments.UpdateInformationFragment
import com.oxygenupdater.internal.settings.PrefManager
import com.oxygenupdater.models.Device
import com.oxygenupdater.models.DeviceOsSpec
import com.oxygenupdater.models.ServerMessage
import com.oxygenupdater.models.ServerStatus
import com.oxygenupdater.utils.Logger.logWarning
import com.oxygenupdater.utils.SetupUtils
import com.oxygenupdater.utils.Utils
import com.oxygenupdater.utils.Utils.checkPlayServices
import com.oxygenupdater.viewmodels.BillingViewModel
import com.oxygenupdater.viewmodels.MainViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.math.abs

class MainActivity : BaseActivity(R.layout.activity_main), Toolbar.OnMenuItemClickListener {

    private val noNetworkDialog by lazy(LazyThreadSafetyMode.NONE) {
        MessageDialog(
            this,
            title = getString(R.string.error_app_requires_network_connection),
            message = getString(R.string.error_app_requires_network_connection_message),
            negativeButtonText = getString(R.string.download_error_close),
            cancellable = false
        )
    }

    private val serverMessagesDialog by lazy(LazyThreadSafetyMode.NONE) {
        ServerMessagesDialogFragment()
    }

    private val noConnectionSnackbar by lazy(LazyThreadSafetyMode.NONE) {
        Snackbar.make(
            binding.coordinatorLayout,
            R.string.error_no_internet_connection,
            Snackbar.LENGTH_INDEFINITE
        ).apply {
            anchorView = binding.bannerAdViewContainer
            setBackgroundTint(ContextCompat.getColor(this@MainActivity, R.color.colorError))
            setAction(getString(android.R.string.ok)) {
                dismiss()
            }
        }
    }

    private lateinit var bannerAdView: AdView

    private val mainViewModel by viewModel<MainViewModel>()
    private val billingViewModel by viewModel<BillingViewModel>()

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            binding.toolbar.menu.findItem(R.id.action_mark_articles_read).isVisible = position == PAGE_NEWS

            binding.bottomNavigationView.menu.getItem(position)?.run {
                isChecked = true

                when (position) {
                    PAGE_UPDATE, PAGE_NEWS, PAGE_DEVICE -> hideTabBadge(itemId, 1000)
                    else -> {
                        // no-op
                    }
                }

                updateToolbarForPage(itemId, title)
            }
        }
    }

    private val appUpdateAvailableObserver = Observer<AppUpdateInfo> { updateInfo ->
        if (updateInfo.installStatus() == DOWNLOADED) {
            mainViewModel.unregisterAppUpdateListener()
            showAppUpdateSnackbar()
        } else {
            try {
                when (updateInfo.updateAvailability()) {
                    UPDATE_AVAILABLE -> mainViewModel.requestUpdate(this, updateInfo)
                    // If an IMMEDIATE update is in the stalled state, we should resume it
                    DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> mainViewModel.requestImmediateAppUpdate(
                        this,
                        updateInfo
                    )
                    else -> {
                        // no-op
                    }
                }
            } catch (e: IntentSender.SendIntentException) {
                showAppUpdateBanner()
            }
        }
    }

    private lateinit var binding: ActivityMainBinding
    override fun onCreate(
        savedInstanceState: Bundle?
    ) = super.onCreate(savedInstanceState).also {
        binding = ActivityMainBinding.bind(rootView)
        setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        lifecycle.addObserver(billingViewModel.lifecycleObserver)

        bannerAdView = fullWidthAnchoredAdaptiveBannerAd(
            BuildConfig.AD_BANNER_MAIN_ID,
            binding.bannerAdViewContainer
        )

        binding.toolbar.setOnMenuItemClickListener(this)
        setupViewPager()

        if (!Utils.checkNetworkConnection()) {
            noNetworkDialog.show()
        }

        setupLiveDataObservers()
    }

    override fun onResume() = super.onResume().also {
        bannerAdView.resume()
        mainViewModel.checkForStalledAppUpdate().observe(
            this,
            appUpdateAvailableObserver
        )
    }

    override fun onPause() = super.onPause().also {
        bannerAdView.pause()
    }

    override fun onDestroy() = super.onDestroy().also {
        bannerAdView.destroy()
        binding.viewPager.unregisterOnPageChangeCallback(pageChangeCallback)
        noNetworkDialog.bypassListenerAndDismiss()
        mainViewModel.unregisterAppUpdateListener()
    }

    override fun onBackPressed() = when {
        // If the user is currently looking at the first step, allow the system to handle the
        // Back button. This calls finish() on this activity and pops the back stack.
        binding.viewPager.currentItem == 0 -> finishAffinity()
        // If user's settings haven't been saved yet, don't reset to the first page
        shouldStopNavigateAwayFromSettings() -> showSettingsWarning()
        // Otherwise, reset to first page
        else -> binding.viewPager.currentItem = 0
    }

    override fun onMenuItemClick(item: MenuItem) = when (item.itemId) {
        R.id.action_announcements -> showServerMessagesDialog().let { true }
        R.id.action_mark_articles_read -> mainViewModel.notifyMenuClicked(item.itemId).let { true }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * Control comes back to the activity in the form of a result only for a [AppUpdateType.FLEXIBLE] update request,
     * since an [AppUpdateType.IMMEDIATE] update is entirely handled by Google Play, with the exception of resuming an installation.
     * Check [onResume] for more info on how this is handled.
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) = super.onActivityResult(requestCode, resultCode, data).also {
        if (requestCode == REQUEST_CODE_APP_UPDATE) {
            when (resultCode) {
                // Reset ignore count
                Activity.RESULT_OK -> PrefManager.putInt(
                    PrefManager.PROPERTY_FLEXIBLE_APP_UPDATE_IGNORE_COUNT,
                    0
                )
                // Increment ignore count and show app update banner
                Activity.RESULT_CANCELED -> PrefManager.getInt(
                    PrefManager.PROPERTY_FLEXIBLE_APP_UPDATE_IGNORE_COUNT,
                    0
                ).let { ignoreCount ->
                    PrefManager.putInt(
                        PrefManager.PROPERTY_FLEXIBLE_APP_UPDATE_IGNORE_COUNT,
                        ignoreCount + 1
                    )
                    showAppUpdateBanner()
                }
                // Show app update banner
                ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> showAppUpdateBanner()
            }
        }
    }

    private fun setupLiveDataObservers() {
        // display the "No connection" banner if required
        OxygenUpdater.isNetworkAvailable.observe(this) {
            if (it) {
                noConnectionSnackbar.dismiss()

                // Dismiss no network dialog if needed
                if (noNetworkDialog.isShowing) {
                    noNetworkDialog.bypassListenerAndDismiss()
                }
            } else {
                noConnectionSnackbar.show()
            }
        }

        billingViewModel.hasPurchasedAdFree.observe(this) {
            // Ads should be shown if user hasn't bought the ad-free unlock
            setupAds(!it)
        }

        mainViewModel.maybeCheckForAppUpdate().observe(
            this,
            appUpdateAvailableObserver
        )

        mainViewModel.fetchAllDevices().observe(this) { deviceList ->
            mainViewModel.deviceOsSpec = Utils.checkDeviceOsSpec(deviceList)

            val showDeviceWarningDialog = !PrefManager.getBoolean(
                PrefManager.PROPERTY_IGNORE_UNSUPPORTED_DEVICE_WARNINGS,
                false
            )

            if (showDeviceWarningDialog && !mainViewModel.deviceOsSpec!!.isDeviceOsSpecSupported) {
                displayUnsupportedDeviceOsSpecMessage()
            } else {
                updateDeviceMismatchStatus(deviceList)
            }

            if (!mainViewModel.deviceOsSpec!!.isDeviceOsSpecSupported || mainViewModel.deviceMismatchStatus!!.first) {
                updateTabBadge(R.id.page_device)
            }

            // Resubscribe to notification topics, if needed.
            // We're doing it here, instead of [SplashActivity], because it requires the app to be setup first
            // (`deviceId`, `updateMethodId`, etc need to be saved in [SharedPreferences]).
            mainViewModel.resubscribeToNotificationTopicsIfNeeded(deviceList.filter { it.enabled })
        }

        mainViewModel.serverStatus.observe(this) { serverStatus ->
            // Banner is displayed if app version is outdated
            if (!serverStatus.checkIfAppIsUpToDate()) {
                showAppUpdateBanner(serverStatus)
            }

            fetchServerMessagesInternal(serverStatus)
        }

        mainViewModel.serverMessages.observe(this) {
            displayServerMessageBars(it)
        }

        mainViewModel.appUpdateInstallStatus.observe(this) {
            when (it.installStatus()) {
                PENDING -> binding.flexibleAppUpdateProgressBar.apply {
                    isVisible = true
                    isIndeterminate = true
                }
                DOWNLOADING -> binding.flexibleAppUpdateProgressBar.apply {
                    isVisible = true
                    isIndeterminate = false
                    progress = (it.bytesDownloaded() * 100 / it.totalBytesToDownload().coerceAtLeast(1)).toInt()
                }
                DOWNLOADED -> {
                    binding.flexibleAppUpdateProgressBar.isVisible = false
                    showAppUpdateSnackbar()
                }
                FAILED -> {
                    binding.flexibleAppUpdateProgressBar.isVisible = false
                    showAppUpdateBanner()
                }
                else -> binding.flexibleAppUpdateProgressBar.isVisible = false
            }
        }

        mainViewModel.pageToolbarTextUpdated.observe(this) {
            if (it.first == binding.bottomNavigationView.selectedItemId) {
                binding.toolbar.subtitle = it.second
            }
        }

        mainViewModel.settingsChanged.observe(this) {
            val allDevices = mainViewModel.allDevices.value
            if (it == PrefManager.PROPERTY_DEVICE_ID && allDevices != null) {
                updateDeviceMismatchStatus(allDevices)
            }
        }
    }

    /**
     * Configures the [R.id.action_announcements] menu item's visibility.
     * Also submits [banners] to [serverMessagesDialog] so that its adapter can display them.
     */
    private fun displayServerMessageBars(banners: List<ServerMessage>) {
        val announcementsItem = binding.toolbar.menu.findItem(R.id.action_announcements)

        // Select only the banners that have a non-empty text (`ServerStatus.kt` has empty text in some cases)
        val bannerList = banners.filter {
            !it.getBannerText(this).isNullOrBlank()
        }

        if (isFinishing || bannerList.isEmpty()) {
            announcementsItem?.isVisible = false
        } else {
            announcementsItem?.isVisible = true
            serverMessagesDialog.submitList(bannerList)
        }
    }

    private fun fetchServerMessagesInternal(
        serverStatus: ServerStatus,
    ) {
        mainViewModel.fetchServerMessages(serverStatus) { error ->
            if (isFinishing) {
                return@fetchServerMessages
            }

            when (error) {
                OxygenUpdater.SERVER_MAINTENANCE_ERROR -> Dialogs.showServerMaintenanceError(this)
                OxygenUpdater.APP_OUTDATED_ERROR -> Dialogs.showAppOutdatedError(this)
            }
        }
    }

    private fun updateDeviceMismatchStatus(deviceList: List<Device>) {
        mainViewModel.deviceMismatchStatus = Utils.checkDeviceMismatch(this, deviceList)

        val showIncorrectDeviceDialog = !PrefManager.getBoolean(
            PrefManager.PROPERTY_IGNORE_INCORRECT_DEVICE_WARNINGS,
            false
        )

        if (showIncorrectDeviceDialog && mainViewModel.deviceMismatchStatus!!.first) {
            displayIncorrectDeviceSelectedMessage()
        }
    }

    private fun showAppUpdateBanner(
        serverStatus: ServerStatus? = null
    ) = binding.appUpdateBannerTextView.run {
        isVisible = true
        binding.appUpdateBannerDivider.isVisible = true
        text = if (serverStatus == null) {
            getString(R.string.new_app_version_inapp_failed)
        } else {
            getString(R.string.new_app_version, serverStatus.latestAppVersion)
        }

        setOnClickListener {
            openPlayStorePage()
        }
    }

    private fun showAppUpdateSnackbar() {
        Snackbar.make(
            binding.coordinatorLayout,
            R.string.new_app_version_inapp_downloaded,
            Snackbar.LENGTH_INDEFINITE
        ).apply {
            anchorView = binding.viewPager
            setAction(getString(R.string.error_reload)) {
                mainViewModel.completeAppUpdate()
            }
            show()
        }
    }

    private fun setupViewPager() {
        binding.viewPager.apply {
            offscreenPageLimit = 1 // Enough to preload the "News" page
            adapter = MainPagerAdapter()
            reduceDragSensitivity()

            registerOnPageChangeCallback(pageChangeCallback)

            currentItem = try {
                intent?.getIntExtra(
                    INTENT_START_PAGE,
                    PAGE_UPDATE
                ) ?: PAGE_UPDATE
            } catch (ignored: IndexOutOfBoundsException) {
                PAGE_UPDATE
            }
        }

        setupBottomNavigation()
        setupAppBarForViewPager()
        binding.toolbar.setNavigationOnClickListener { showAboutPage() }
    }

    private fun setupAppBarForViewPager() {
        binding.appBar.post {
            val totalScrollRange = binding.appBar.totalScrollRange

            // Adjust bottom margin on first load
            binding.contentLayout.updateLayoutParams<LayoutParams> {
                bottomMargin = totalScrollRange
            }

            // Adjust bottom margin on scroll
            binding.appBar.addOnOffsetChangedListener { _, verticalOffset ->
                binding.contentLayout.updateLayoutParams<LayoutParams> {
                    bottomMargin = totalScrollRange - abs(verticalOffset)
                }
            }
        }
    }

    private fun setupBottomNavigation() = binding.bottomNavigationView.setOnItemSelectedListener {
        when (it.itemId) {
            R.id.page_update -> 0
            R.id.page_news -> 1
            R.id.page_device -> 2
            R.id.page_about -> 3
            R.id.page_settings -> 4
            else -> 0
        }.let { pageIndex ->
            if (shouldStopNavigateAwayFromSettings(pageIndex)) {
                // If user's settings haven't been saved yet, don't navigate to PAGE_ABOUT
                showSettingsWarning()
                false
            } else {
                binding.viewPager.currentItem = pageIndex
                true
            }
        }
    }

    /**
     * Checks if the user can navigate away from [PAGE_SETTINGS].
     *
     * @param currentPageIndex an optional parameter to pass a different pageIndex (e.g. when BottomNav's menu is clicked).
     * Defaults to [ViewPager2.getCurrentItem].
     */
    private fun shouldStopNavigateAwayFromSettings(currentPageIndex: Int = binding.viewPager.currentItem) =
        currentPageIndex == PAGE_SETTINGS && !PrefManager.checkIfSetupScreenHasBeenCompleted()

    private fun showSettingsWarning() {
        val deviceId = PrefManager.getLong(PrefManager.PROPERTY_DEVICE_ID, -1L)
        val updateMethodId = PrefManager.getLong(PrefManager.PROPERTY_UPDATE_METHOD_ID, -1L)

        if (deviceId == -1L || updateMethodId == -1L) {
            logWarning(TAG, SetupUtils.getAsError("Settings screen", deviceId, updateMethodId))
            Toast.makeText(this, getString(R.string.settings_entered_incorrectly), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.settings_saving), Toast.LENGTH_LONG).show()
        }
    }

    fun showAboutPage() {
        if (shouldStopNavigateAwayFromSettings()) {
            // If user's settings haven't been saved yet, don't navigate to PAGE_ABOUT
            showSettingsWarning()
        } else {
            binding.viewPager.currentItem = PAGE_ABOUT
        }
    }

    /**
     * Show the dialog fragment only if it hasn't been added already. This
     * can happen if the user clicks in rapid succession, which can cause
     * the `java.lang.IllegalStateException: Fragment already added` error
     */
    private fun showServerMessagesDialog() {
        if (!isFinishing && !serverMessagesDialog.isAdded) {
            serverMessagesDialog.show(
                supportFragmentManager,
                ServerMessagesDialogFragment.TAG
            )
        }
    }

    fun updateToolbarForPage(@IdRes pageId: Int, subtitle: CharSequence? = null) {
        if (pageId == binding.bottomNavigationView.selectedItemId) {
            binding.toolbar.subtitle = mainViewModel.pageToolbarSubtitle[pageId] ?: subtitle
        }
    }

    /**
     * Update the state of a [com.google.android.material.bottomnavigation.BottomNavigationView]'s [com.google.android.material.badge.BadgeDrawable]
     *
     * @param pageId pageId of the tab/fragment
     * @param show flag to control the badge's visibility
     * @param count optional number to display in the badge
     *
     * @see hideTabBadge
     */
    fun updateTabBadge(
        @IdRes pageId: Int,
        show: Boolean = true,
        count: Int? = null
    ) = binding.bottomNavigationView.getOrCreateBadge(pageId).apply {
        isVisible = show

        if (isVisible && count != null /*&& count != 0*/) {
            number = count
            maxCharacterCount = 3
        }
    }

    /**
     * Hide the [com.google.android.material.bottomnavigation.BottomNavigationView]'s [com.google.android.material.badge.BadgeDrawable] after a specified delay
     *
     * Even though [updateTabBadge] can be used to hide a badge, this function is different because it only hides an existing badge, after a specified delay.
     * It's meant to be called from the [ActivityMainBinding.viewPager]'s `onPageSelected` callback, within this class.
     * [updateTabBadge] can be called from child fragments to hide the badge immediately, for example, if required after refreshing
     *
     * @param pageId pageId of the tab/fragment
     * @param delayMillis the delay, in milliseconds
     *
     * @see updateTabBadge
     */
    @Suppress("SameParameterValue")
    private fun hideTabBadge(
        @IdRes pageId: Int,
        delayMillis: Long = 0
    ) = binding.bottomNavigationView.getBadge(pageId)?.apply {
        Handler(Looper.getMainLooper()).postDelayed({
            if (binding.bottomNavigationView.selectedItemId == pageId) {
                isVisible = false
            }
        }, delayMillis)
    }

    /**
     * Checks for Play Services and initialises [MobileAds] if found
     */
    private fun setupAds(shouldShowAds: Boolean) {
        if (!checkPlayServices(this, false)) {
            Toast.makeText(this, getString(R.string.notification_no_notification_support), Toast.LENGTH_LONG).show()
        }

        binding.bannerAdViewContainer.apply {
            if (shouldShowAds) {
                isVisible = true
                bannerAdView.loadAd(buildAdRequest())

                binding.bannerAdDivider.isVisible = true
            } else {
                isVisible = false
                binding.bannerAdDivider.isVisible = false
            }
        }
    }

    fun displayUnsupportedDeviceOsSpecMessage() {
        // Do not show dialog if app was already exited upon receiving of devices from the server.
        if (isFinishing) {
            return
        }

        val resourceId = when (mainViewModel.deviceOsSpec) {
            DeviceOsSpec.CARRIER_EXCLUSIVE_OXYGEN_OS -> R.string.carrier_exclusive_device_warning_message
            DeviceOsSpec.UNSUPPORTED_OXYGEN_OS -> R.string.unsupported_device_warning_message
            DeviceOsSpec.UNSUPPORTED_OS -> R.string.unsupported_os_warning_message
            else -> R.string.unsupported_os_warning_message
        }

        val checkBoxView = View.inflate(this@MainActivity, R.layout.message_dialog_checkbox, null)
        var userIgnoreWarningsPref = false
        checkBoxView?.findViewById<CheckBox>(R.id.device_warning_checkbox)?.setOnCheckedChangeListener { _, isChecked ->
            userIgnoreWarningsPref = isChecked
        }

        MaterialAlertDialogBuilder(this)
            .setView(checkBoxView)
            .setTitle(getString(R.string.unsupported_device_warning_title))
            .setMessage(getString(resourceId))
            .setPositiveButton(getString(R.string.download_error_close), null)
            .setOnDismissListener {
                PrefManager.putBoolean(
                    PrefManager.PROPERTY_IGNORE_UNSUPPORTED_DEVICE_WARNINGS,
                    userIgnoreWarningsPref
                )
            }.show()
    }

    private fun displayIncorrectDeviceSelectedMessage() {
        // Do not show dialog if app was already exited upon receiving of devices from the server.
        if (isFinishing) {
            return
        }

        val checkBoxView = View.inflate(this@MainActivity, R.layout.message_dialog_checkbox, null)
        var userIgnoreWarningsPref = false
        checkBoxView?.findViewById<CheckBox>(R.id.device_warning_checkbox)?.setOnCheckedChangeListener { _, isChecked ->
            userIgnoreWarningsPref = isChecked
        }

        MaterialAlertDialogBuilder(this)
            .setView(checkBoxView)
            .setTitle(getString(R.string.incorrect_device_warning_title))
            .setMessage(
                getString(
                    R.string.incorrect_device_warning_message,
                    mainViewModel.deviceMismatchStatus!!.second,
                    mainViewModel.deviceMismatchStatus!!.third
                )
            )
            .setPositiveButton(getString(R.string.download_error_close), null)
            .setOnDismissListener {
                PrefManager.putBoolean(
                    PrefManager.PROPERTY_IGNORE_INCORRECT_DEVICE_WARNINGS,
                    userIgnoreWarningsPref
                )
            }.show()
    }

    /**
     * A [FragmentStateAdapter] that returns a fragment corresponding to one of the sections/tabs/pages.
     */
    inner class MainPagerAdapter : FragmentStateAdapter(this) {

        /**
         * This is called to instantiate the fragment for the given page.
         * Return one of:
         * * [UpdateInformationFragment]
         * * [NewsListFragment]
         * * [DeviceInformationFragment]
         * * [SettingsFragment]
         * * [SettingsFragment]
         */
        override fun createFragment(position: Int) = when (position) {
            PAGE_UPDATE -> UpdateInformationFragment()
            PAGE_NEWS -> NewsListFragment()
            PAGE_DEVICE -> DeviceInformationFragment()
            PAGE_ABOUT -> AboutFragment()
            PAGE_SETTINGS -> SettingsFragment()
            else -> TODO()
        }

        /**
         * Show 5 total pages: Update, News, Device, About, and Settings
         */
        override fun getItemCount() = 5
    }

    companion object {
        private const val TAG = "MainActivity"

        const val PAGE_UPDATE = 0
        const val PAGE_NEWS = 1
        const val PAGE_DEVICE = 2
        const val PAGE_ABOUT = 3
        const val PAGE_SETTINGS = 4
        const val INTENT_START_PAGE = "start_page"

        const val REQUEST_CODE_APP_UPDATE = 1000
        const val DAYS_FOR_APP_UPDATE_CHECK = 2L
        const val MAX_APP_FLEXIBLE_UPDATE_STALE_DAYS = 14
        const val MAX_APP_FLEXIBLE_UPDATE_IGNORE_COUNT = 7
    }
}
