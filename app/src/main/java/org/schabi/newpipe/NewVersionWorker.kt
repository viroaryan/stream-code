package org.schabi.newpipe

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import java.io.IOException
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.util.ReleaseVersionUtil
import org.schabi.newpipe.util.Version

class NewVersionWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    private fun compareAppVersionAndShowNotification(
        versionName: String,
        apkLocationUrl: String?
    ) {
        val ourVersion = Version.fromString(BuildConfig.VERSION_NAME)
        val theirVersion = Version.fromString(versionName)

        // abort if source version is the same or newer than target version
        if (ourVersion >= theirVersion) {
            if (inputData.getBoolean(IS_MANUAL, false)) {
                // Show toast stating that the app is up-to-date if the update check was manual.
                ContextCompat.getMainExecutor(applicationContext).execute {
                    Toast.makeText(
                        applicationContext,
                        R.string.app_update_unavailable_toast,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return
        }

        // Stream: remember the update so the app itself can offer it with a dialog,
        // even when notifications are turned off
        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .putString(PENDING_UPDATE_VERSION, versionName)
            .putString(PENDING_UPDATE_URL, apkLocationUrl)
            .apply()

        // A pending intent to open the apk location url in the browser.
        val intent = Intent(Intent.ACTION_VIEW, apkLocationUrl?.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntentCompat.getActivity(
            applicationContext,
            0,
            intent,
            0,
            false
        )
        val channelId = applicationContext.getString(R.string.app_update_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_tubular_update)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setContentTitle(
                applicationContext.getString(R.string.app_update_available_notification_title)
            )
            .setContentText(
                applicationContext.getString(
                    R.string.app_update_available_notification_text,
                    versionName
                )
            )

        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (notificationManager.areNotificationsEnabled()) {
            notificationManager.notify(2000, notificationBuilder.build())
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    private fun checkNewVersion() {
        if (!inputData.getBoolean(IS_MANUAL, false)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            // Check if the last request has happened a certain time ago
            // to reduce the number of API requests.
            val expiry = prefs.getLong(applicationContext.getString(R.string.update_expiry_key), 0)
            if (!ReleaseVersionUtil.isLastUpdateCheckExpired(expiry)) {
                return
            }
        }

        // Ask Stream's website first; GitHub is the fallback. Some networks block GitHub's
        // file host or hit its API rate limit, while the website serves the APK itself.
        var lastError: IOException? = null
        for (url in UPDATE_SOURCES) {
            try {
                val response = DownloaderImpl.getInstance().get(url)
                if (response.responseCode() in 200..299 && handleResponse(response, url)) {
                    return
                }
            } catch (e: IOException) {
                lastError = e
                if (DEBUG) {
                    Log.w(TAG, "Update source failed: $url", e)
                }
            }
        }
        lastError?.let { throw it }
    }

    /**
     * @return true if the response described a release (whether or not it is newer)
     */
    private fun handleResponse(response: Response, sourceUrl: String): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        try {
            // Store a timestamp which needs to be exceeded,
            // before a new request to the API is made.
            val newExpiry = ReleaseVersionUtil.coerceUpdateCheckExpiry(response.getHeader("expires"))
            prefs.edit {
                putLong(applicationContext.getString(R.string.update_expiry_key), newExpiry)
            }
        } catch (e: Exception) {
            if (DEBUG) {
                Log.w(TAG, "Could not extract and save new expiry date", e)
            }
        }

        // Parse the json from the response.
        try {
            val jObj = JsonParser.`object`().from(response.responseBody())
            val versionName = jObj.getString("tag_name")
            if (versionName.isNullOrBlank()) {
                // e.g. the repo has no release yet (GitHub answers with an error object)
                if (DEBUG) {
                    Log.w(TAG, "No release found at $sourceUrl")
                }
                return false
            }
            // Prefer the first .apk asset; fall back to the release page itself.
            val assets = jObj.getArray("assets")
            var apkLocationUrl: String? = null
            for (i in 0 until assets.size) {
                val url = assets.getObject(i).getString("browser_download_url")
                if (url != null && url.endsWith(".apk", ignoreCase = true)) {
                    apkLocationUrl = url
                    break
                }
            }
            compareAppVersionAndShowNotification(
                versionName,
                apkLocationUrl ?: jObj.getString("html_url")
            )
            return true
        } catch (e: JsonParserException) {
            if (DEBUG) {
                Log.w(TAG, "Invalid json from $sourceUrl", e)
            }
            return false
        }
    }

    override fun doWork(): Result {
        return try {
            checkNewVersion()
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Could not fetch any update source: probably network problem", e)
            Result.failure()
        } catch (e: ReCaptchaException) {
            Log.e(TAG, "ReCaptchaException should never happen here.", e)
            Result.failure()
        }
    }

    companion object {
        private val DEBUG = MainActivity.DEBUG
        private val TAG = NewVersionWorker::class.java.simpleName
        private const val GITHUB_RELEASES_API_URL =
            "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"

        /** Same shape as GitHub's "latest release" JSON, published by updates/release.ps1. */
        private const val WEBSITE_LATEST_URL = "https://stream.arivna.com/apk/latest.json"
        private val UPDATE_SOURCES = listOf(WEBSITE_LATEST_URL, GITHUB_RELEASES_API_URL)
        private const val IS_MANUAL = "isManual"
        const val PENDING_UPDATE_VERSION = "stream_pending_update_version"
        const val PENDING_UPDATE_URL = "stream_pending_update_url"

        /**
         * Stream: shows "New update available" inside the app, once per app start, when a
         * newer version was found. "Update" opens the APK download on Stream's website.
         */
        @JvmStatic
        fun showPendingUpdateDialog(activity: android.app.Activity) {
            if (updateDialogShown) return
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val version = prefs.getString(PENDING_UPDATE_VERSION, null) ?: return
            val url = prefs.getString(PENDING_UPDATE_URL, null) ?: return
            val newer = runCatching {
                Version.fromString(version) > Version.fromString(BuildConfig.VERSION_NAME)
            }.getOrDefault(false)
            if (!newer) {
                prefs.edit().remove(PENDING_UPDATE_VERSION).remove(PENDING_UPDATE_URL).apply()
                return
            }
            updateDialogShown = true
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.stream_update_dialog_title, version))
                .setMessage(R.string.stream_update_dialog_message)
                .setPositiveButton(R.string.stream_update_dialog_update) { _, _ ->
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                .setNegativeButton(R.string.stream_update_dialog_later, null)
                .show()
        }

        private var updateDialogShown = false

        @JvmStatic
        fun enqueueNewVersionCheckingWork(context: Context, isManual: Boolean) {
            val workRequest = OneTimeWorkRequestBuilder<NewVersionWorker>()
                .setInputData(workDataOf(IS_MANUAL to isManual))
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
