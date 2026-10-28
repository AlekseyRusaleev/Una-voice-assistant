package org.stypox.dicio

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.HiltAndroidApp
import kotlin.concurrent.thread
import org.stypox.dicio.eval.RemoteConfig
import org.stypox.dicio.eval.Device
import org.stypox.dicio.eval.Uplink
import org.stypox.dicio.update.Updater
import org.stypox.dicio.io.input.gigaam.GigaAmModel
import org.stypox.dicio.util.checkPermissions

// IMPORTANT NOTE: beware of this nasty bug related to allowBackup=true
// https://medium.com/p/924c91bafcac
@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // must be the very first thing: a later crash has to leave a trace
        org.stypox.dicio.util.CrashGuard.install(this)
        // command dictionary: use the cached copy at once, refresh it in the background
        RemoteConfig.loadCached(this)
        RemoteConfig.refresh(this)
        // accurate on-device recogniser for commands (~216 MB, downloaded once)
        GigaAmModel.downloadIfNeeded(this)
        // free the APK left over from a previous update, then check for a new one
        Updater.dropCachedApk(this)
        Updater.checkAndUpdate(this)
        thread(name = "una-startup") {
            Device.ensureRegistered(this)
            org.stypox.dicio.util.CrashGuard.takeReport(this)?.let { report ->
                org.stypox.dicio.eval.Journal.record(this, "crash: $report", "diag")
            }
            Uplink.sendJournal(this)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkPermissions(this, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            initNotificationChannels()
        }
    }

    private fun initNotificationChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    getString(R.string.error_report_channel_id),
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(getString(R.string.error_report_channel_name))
                    .setDescription(getString(R.string.error_report_channel_description))
                    .build()
            )
        )
    }
}
