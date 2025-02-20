package com.oxygenupdater.viewmodels

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.os.Environment
import android.util.SparseArray
import androidx.annotation.IdRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest.MIN_BACKOFF_MILLIS
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
import com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE
import com.oxygenupdater.activities.MainActivity
import com.oxygenupdater.enums.DownloadStatus
import com.oxygenupdater.exceptions.UpdateDownloadException
import com.oxygenupdater.extensions.toWorkData
import com.oxygenupdater.fragments.UpdateInformationFragment
import com.oxygenupdater.internal.KotlinCallback
import com.oxygenupdater.internal.settings.PrefManager
import com.oxygenupdater.models.Device
import com.oxygenupdater.models.DeviceOsSpec
import com.oxygenupdater.models.DeviceRequestFilter
import com.oxygenupdater.models.ServerMessage
import com.oxygenupdater.models.ServerStatus
import com.oxygenupdater.models.UpdateData
import com.oxygenupdater.repositories.ServerRepository
import com.oxygenupdater.utils.Logger.logDebug
import com.oxygenupdater.utils.Logger.logInfo
import com.oxygenupdater.utils.Logger.logWarning
import com.oxygenupdater.utils.NotificationTopicSubscriber
import com.oxygenupdater.workers.DIRECTORY_ROOT
import com.oxygenupdater.workers.DownloadWorker
import com.oxygenupdater.workers.WORK_DATA_DOWNLOAD_FAILURE_EXTRA_FILENAME
import com.oxygenupdater.workers.WORK_DATA_DOWNLOAD_FAILURE_EXTRA_HTTP_CODE
import com.oxygenupdater.workers.WORK_DATA_DOWNLOAD_FAILURE_EXTRA_HTTP_MESSAGE
import com.oxygenupdater.workers.WORK_DATA_DOWNLOAD_FAILURE_EXTRA_OTA_VERSION
import com.oxygenupdater.workers.WORK_DATA_DOWNLOAD_FAILURE_EXTRA_URL
import com.oxygenupdater.workers.WORK_DATA_DOWNLOAD_FAILURE_EXTRA_VERSION
import com.oxygenupdater.workers.WORK_UNIQUE_DOWNLOAD
import com.oxygenupdater.workers.WORK_UNIQUE_MD5_VERIFICATION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Shared between [com.oxygenupdater.activities.MainActivity] and its three child fragments
 * (as part of [androidx.viewpager2.widget.ViewPager2]):
 * 1. [com.oxygenupdater.fragments.NewsListFragment]
 * 2. [com.oxygenupdater.fragments.UpdateInformationFragment]
 * 3. [com.oxygenupdater.fragments.DeviceInformationFragment]
 *
 * @author [Adhiraj Singh Chauhan](https://github.com/adhirajsinghchauhan)
 */
class MainViewModel(
    private val serverRepository: ServerRepository
) : ViewModel() {

    private val _allDevices = MutableLiveData<List<Device>>()
    val allDevices: LiveData<List<Device>>
        get() = _allDevices

    private val _updateData = MutableLiveData<UpdateData?>()
    val updateData: LiveData<UpdateData?>
        get() = _updateData

    private val _serverStatus = MutableLiveData<ServerStatus>()
    val serverStatus: LiveData<ServerStatus>
        get() = _serverStatus

    private val _serverMessages = MutableLiveData<List<ServerMessage>>()
    val serverMessages: LiveData<List<ServerMessage>>
        get() = _serverMessages

    private val _downloadStatusLiveData = MutableLiveData<Pair<DownloadStatus, WorkInfo?>>()
    private var _downloadStatus = DownloadStatus.NOT_DOWNLOADING
    val downloadStatus: LiveData<Pair<DownloadStatus, WorkInfo?>>
        get() = _downloadStatusLiveData

    val downloadWorkInfo
        get() = workManager.getWorkInfosForUniqueWorkLiveData(WORK_UNIQUE_DOWNLOAD)

    val verificationWorkInfo
        get() = workManager.getWorkInfosForUniqueWorkLiveData(WORK_UNIQUE_MD5_VERIFICATION)

    private val _appUpdateAvailable = MutableLiveData<AppUpdateInfo>()
    private val _appUpdateInstallStatus = MutableLiveData<InstallState>()
    val appUpdateInstallStatus: LiveData<InstallState>
        get() = _appUpdateInstallStatus

    private val _pageToolbarTextUpdated = MutableLiveData<Pair<Int, CharSequence?>>()
    val pageToolbarTextUpdated: LiveData<Pair<Int, CharSequence?>>
        get() = _pageToolbarTextUpdated
    val pageToolbarSubtitle = SparseArray<CharSequence?>()

    private val _menuClicked = MutableLiveData<Int>()
    val menuClicked: LiveData<Int>
        get() = _menuClicked

    private val _settingsChanged = MutableLiveData<String>()
    val settingsChanged: LiveData<String>
        get() = _settingsChanged

    private val workManager by getKoin().inject<WorkManager>()
    private val appUpdateManager by getKoin().inject<AppUpdateManager>()

    private lateinit var downloadWorkRequest: OneTimeWorkRequest

    private var appUpdateType = AppUpdateType.FLEXIBLE

    private val flexibleAppUpdateListener: KotlinCallback<InstallState> = {
        _appUpdateInstallStatus.postValue(it)
    }

    var deviceOsSpec: DeviceOsSpec? = null
    var deviceMismatchStatus: Triple<Boolean, String, String>? = null

    val isDownloadWorkInitialized: Boolean
        get() = ::downloadWorkRequest.isInitialized

    fun fetchAllDevices(): LiveData<List<Device>> = viewModelScope.launch(Dispatchers.IO) {
        serverRepository.fetchDevices(DeviceRequestFilter.ALL)?.let {
            _allDevices.postValue(it)
        }
    }.let { _allDevices }

    fun fetchUpdateData(
        deviceId: Long,
        updateMethodId: Long,
        incrementalSystemVersion: String
    ) = viewModelScope.launch(Dispatchers.IO) {
        serverRepository.fetchUpdateData(
            deviceId,
            updateMethodId,
            incrementalSystemVersion
        ).let {
            _updateData.postValue(it)
        }
    }

    fun fetchServerStatus() = viewModelScope.launch(Dispatchers.IO) {
        serverRepository.fetchServerStatus().let {
            _serverStatus.postValue(it)
        }
    }

    fun fetchServerMessages(
        serverStatus: ServerStatus,
        errorCallback: KotlinCallback<String?>
    ) = viewModelScope.launch(Dispatchers.IO) {
        serverRepository.fetchServerMessages(serverStatus, errorCallback)?.let {
            _serverMessages.postValue(it)
        }
    }

    fun resubscribeToNotificationTopicsIfNeeded(
        enabledDeviceList: List<Device>
    ) = viewModelScope.launch(Dispatchers.IO) {
        NotificationTopicSubscriber.resubscribeIfNeeded(
            enabledDeviceList,
            serverRepository.fetchAllMethods() ?: ArrayList()
        )
    }

    fun updateDownloadStatus(downloadStatus: DownloadStatus) {
        _downloadStatus = downloadStatus
        _downloadStatusLiveData.postValue(Pair(downloadStatus, null))
    }

    fun updateDownloadStatus(workInfo: WorkInfo, isVerificationWorkState: Boolean = false) {
        val workState = workInfo.state

        _downloadStatus = when (workState) {
            WorkInfo.State.ENQUEUED -> if (isVerificationWorkState) {
                DownloadStatus.VERIFYING
            } else {
                DownloadStatus.DOWNLOAD_QUEUED
            }
            WorkInfo.State.RUNNING -> if (isVerificationWorkState) {
                DownloadStatus.VERIFYING
            } else {
                DownloadStatus.DOWNLOADING
            }
            WorkInfo.State.SUCCEEDED -> if (isVerificationWorkState) {
                DownloadStatus.VERIFICATION_COMPLETED
            } else {
                DownloadStatus.DOWNLOAD_COMPLETED
            }
            WorkInfo.State.FAILED -> if (isVerificationWorkState) {
                DownloadStatus.VERIFICATION_FAILED
            } else {
                DownloadStatus.DOWNLOAD_FAILED
            }
            WorkInfo.State.BLOCKED -> if (isVerificationWorkState) {
                DownloadStatus.VERIFYING
            } else {
                DownloadStatus.DOWNLOAD_QUEUED
            }
            WorkInfo.State.CANCELLED -> if (isVerificationWorkState) {
                // Verification can never be cancelled
                DownloadStatus.VERIFICATION_FAILED
            } else {
                // Downloads are paused by cancelling by work
                // (we're tracking `bytesDone` and sending a `Range` header for resume operations),
                // so we assume all cancel operation are pause operations instead.
                // The only place from where the user can cancel the download is the action button
                // Hence, we've called `initDownloadLayout(NOT_DOWNLOADING)` in [UpdateInformationFragment] right after cancelling the work.
                DownloadStatus.DOWNLOAD_PAUSED
            }
        }

        // Post value only if it's changed, or if status is DOWNLOADING (because we need to update views for progress)
        if (_downloadStatus != _downloadStatusLiveData.value?.first || _downloadStatus == DownloadStatus.DOWNLOADING) {
            _downloadStatusLiveData.postValue(Pair(_downloadStatus, workInfo))
        }
    }

    fun checkDownloadCompletionByFile(updateData: UpdateData?): Boolean {
        if (updateData?.filename == null) {
            logInfo(UpdateInformationFragment.TAG, "Cannot check for download completion by file - null update data or filename provided!")
            return false
        }

        @Suppress("DEPRECATION")
        return File(Environment.getExternalStoragePublicDirectory(DIRECTORY_ROOT), updateData.filename!!).exists()
    }

    /**
     * @return true if the downloaded ZIP was successfully deleted
     */
    fun deleteDownloadedFile(
        context: Context,
        updateData: UpdateData?,
        markNotDownloading: Boolean = false
    ): Boolean {
        if (updateData?.filename == null) {
            logWarning(
                UpdateInformationFragment.TAG,
                UpdateDownloadException("Could not delete downloaded file, null update data or update data without file name was provided")
            )
            return false
        }

        logDebug(UpdateInformationFragment.TAG, "Deleting any associated tracker preferences for downloaded file")
        PrefManager.remove(PrefManager.PROPERTY_DOWNLOAD_BYTES_DONE)

        logDebug(UpdateInformationFragment.TAG, "Deleting downloaded update file " + updateData.filename)

        val tempFile = File(context.getExternalFilesDir(null), updateData.filename!!)

        @Suppress("DEPRECATION")
        val zipFile = File(Environment.getExternalStoragePublicDirectory(DIRECTORY_ROOT).absolutePath, updateData.filename!!)

        if (tempFile.exists() && !tempFile.delete()) {
            logWarning(UpdateInformationFragment.TAG, UpdateDownloadException("Could not delete temporary file ${updateData.filename}"))
        }

        var deleted = true
        if (zipFile.exists() && !zipFile.delete()) {
            logWarning(UpdateInformationFragment.TAG, UpdateDownloadException("Could not delete downloaded file ${updateData.filename}"))
            deleted = false
        }

        // Mark downloaded only if the file was successfully deleted,
        // or if the caller explicitly requested it
        if (deleted || markNotDownloading) {
            updateDownloadStatus(DownloadStatus.NOT_DOWNLOADING)
        }

        return deleted
    }

    fun setupDownloadWorkRequest(updateData: UpdateData) {
        downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(updateData.toWorkData())
            .setBackoffCriteria(BackoffPolicy.LINEAR, MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
    }

    fun enqueueDownloadWork() = workManager.enqueueUniqueWork(
        WORK_UNIQUE_DOWNLOAD,
        ExistingWorkPolicy.REPLACE,
        downloadWorkRequest
    )

    fun cancelDownloadWork(
        context: Context,
        updateData: UpdateData?
    ) = workManager.cancelUniqueWork(WORK_UNIQUE_DOWNLOAD).also {
        deleteDownloadedFile(context, updateData)
    }

    fun pauseDownloadWork() = workManager.cancelUniqueWork(WORK_UNIQUE_DOWNLOAD)

    /**
     * This method is called in [UpdateInformationFragment.onDestroy], to prune finished work.
     *
     * This is done to avoid getting callbacks to respective work observers when the fragment is created,
     * as it results in unnecessary calls to [updateDownloadStatus]
     */
    fun maybePruneWork() {
        // Make it non-nullable (!!) because it's initialized with a value
        if (_downloadStatus.shouldPruneWork()) {
            workManager.pruneWork()
        }
    }

    fun logDownloadError(data: Data) = viewModelScope.launch(Dispatchers.IO) {
        serverRepository.logDownloadError(
            data.getString(WORK_DATA_DOWNLOAD_FAILURE_EXTRA_URL),
            data.getString(WORK_DATA_DOWNLOAD_FAILURE_EXTRA_FILENAME),
            data.getString(WORK_DATA_DOWNLOAD_FAILURE_EXTRA_VERSION),
            data.getString(WORK_DATA_DOWNLOAD_FAILURE_EXTRA_OTA_VERSION),
            data.getInt(WORK_DATA_DOWNLOAD_FAILURE_EXTRA_HTTP_CODE, -1),
            data.getString(WORK_DATA_DOWNLOAD_FAILURE_EXTRA_HTTP_MESSAGE)
        )
    }

    /**
     * Checks that the platform will allow the specified type of update
     */
    fun maybeCheckForAppUpdate(): LiveData<AppUpdateInfo> = appUpdateManager.appUpdateInfo.addOnSuccessListener {
        when (it.updateAvailability()) {
            DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> _appUpdateAvailable.postValue(it)
            UPDATE_AVAILABLE -> {
                val lastAppUpdateCheckedDate = PrefManager.getString(
                    PrefManager.PROPERTY_LAST_APP_UPDATE_CHECKED_DATE,
                    LocalDate.MIN.toString()
                )

                val today = LocalDate.now()

                // Check for app updates at most once every 2 days
                if (LocalDate.parse(lastAppUpdateCheckedDate).plusDays(MainActivity.DAYS_FOR_APP_UPDATE_CHECK) <= today) {
                    PrefManager.putString(
                        PrefManager.PROPERTY_LAST_APP_UPDATE_CHECKED_DATE,
                        today.toString()
                    )

                    _appUpdateAvailable.postValue(it)
                }
            }
            // Reset ignore count
            else -> PrefManager.putInt(
                PrefManager.PROPERTY_FLEXIBLE_APP_UPDATE_IGNORE_COUNT,
                0
            )
        }
    }.let {
        _appUpdateAvailable
    }

    /**
     * Checks that the platform will allow the specified type of update.
     * Note: Value is posted to [_appUpdateAvailable] only if an immediate update was stalled.
     * This is because the method is called in [MainActivity.onResume],
     * and the activity can get resumed after the user accepts a flexible update too (because a dialog is shown by Google Play)
     */
    fun checkForStalledAppUpdate(): LiveData<AppUpdateInfo> = appUpdateManager.appUpdateInfo.addOnSuccessListener {
        if (appUpdateType == AppUpdateType.IMMEDIATE
            && it.updateAvailability() == DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        ) {
            _appUpdateAvailable.postValue(it)
        }
    }.let {
        _appUpdateAvailable
    }

    /**
     * @throws IntentSender.SendIntentException if a stale [appUpdateInfo] is being used (probably, not sure)
     */
    @Throws(IntentSender.SendIntentException::class)
    fun requestImmediateAppUpdate(activity: Activity, appUpdateInfo: AppUpdateInfo) {
        // Reset ignore count
        PrefManager.putInt(
            PrefManager.PROPERTY_FLEXIBLE_APP_UPDATE_IGNORE_COUNT,
            0
        )

        // If an in-app update is already running, resume the update.
        appUpdateManager.startUpdateFlowForResult(
            appUpdateInfo,
            AppUpdateType.IMMEDIATE,
            activity,
            MainActivity.REQUEST_CODE_APP_UPDATE
        )
    }

    /**
     * Calls [AppUpdateManager.startUpdateFlowForResult]. The app update type is [AppUpdateType.FLEXIBLE] by default, if it's allowed.
     * However, it is forced to be [AppUpdateType.IMMEDIATE] if any of the following criteria satisfy:
     * * [AppUpdateInfo.clientVersionStalenessDays] exceeds the max threshold
     * * The user has ignored the flexible update too many times
     * If [AppUpdateType.FLEXIBLE] isn't allowed, then it's [AppUpdateType.IMMEDIATE]
     *
     * @param appUpdateInfo The update info. Note that this can not be re-used,
     * so every call of this function requires a fresh instance of [AppUpdateInfo],
     * which can be requested from [AppUpdateManager.getAppUpdateInfo].
     *
     * @throws IntentSender.SendIntentException if a stale [appUpdateInfo] is being used (probably, not sure)
     */
    @Throws(IntentSender.SendIntentException::class)
    fun requestUpdate(activity: Activity, appUpdateInfo: AppUpdateInfo) {
        // TODO: implement app update priority whenever Google adds support for it in Play Developer Console and the library itself
        //  (the library doesn't yet have an annotation interface for priority constants)
        appUpdateType = if (appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            val versionStalenessDays = appUpdateInfo.clientVersionStalenessDays() ?: 0
            val ignoreCount = PrefManager.getInt(
                PrefManager.PROPERTY_FLEXIBLE_APP_UPDATE_IGNORE_COUNT,
                0
            )

            // Force update if user has ignored a flexible update 7 times,
            // or if it's been 14 days since the update arrived
            if (versionStalenessDays >= MainActivity.MAX_APP_FLEXIBLE_UPDATE_STALE_DAYS
                || ignoreCount >= MainActivity.MAX_APP_FLEXIBLE_UPDATE_IGNORE_COUNT
            ) {
                AppUpdateType.IMMEDIATE
            } else {
                AppUpdateType.FLEXIBLE
            }
        } else {
            // Reset ignore count
            PrefManager.putInt(
                PrefManager.PROPERTY_FLEXIBLE_APP_UPDATE_IGNORE_COUNT,
                0
            )

            AppUpdateType.IMMEDIATE
        }

        if (appUpdateType == AppUpdateType.FLEXIBLE) {
            appUpdateManager.registerListener(flexibleAppUpdateListener)
        }

        appUpdateManager.startUpdateFlowForResult(
            appUpdateInfo,
            appUpdateType,
            activity,
            MainActivity.REQUEST_CODE_APP_UPDATE
        )
    }

    fun completeAppUpdate() {
        appUpdateManager.completeUpdate()
    }

    fun unregisterAppUpdateListener() {
        appUpdateManager.unregisterListener(flexibleAppUpdateListener)
    }

    override fun onCleared() = super.onCleared().also {
        unregisterAppUpdateListener()
    }

    /**
     * Updates the corresponding page's toolbar subtitle
     */
    fun saveSubtitleForPage(
        @IdRes pageId: Int,
        subtitle: CharSequence? = null
    ) {
        pageToolbarSubtitle.put(pageId, subtitle)
        _pageToolbarTextUpdated.postValue(Pair(pageId, subtitle))
    }

    fun notifyMenuClicked(@IdRes id: Int) = _menuClicked.postValue(id)

    fun notifySettingsChanged(key: String) = _settingsChanged.postValue(key)
}
