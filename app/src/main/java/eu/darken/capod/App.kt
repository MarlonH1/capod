package eu.darken.capod

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.RoutingSessionInfo
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.hilt.work.HiltWorkerFactory
import androidx.mediarouter.media.MediaRouter
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import eu.darken.capod.common.MediaControl
import eu.darken.capod.common.coroutine.AppScope
import eu.darken.capod.common.debug.autoreport.AutomaticBugReporter
import eu.darken.capod.common.debug.logging.*
import eu.darken.capod.common.flow.throttleLatest
import eu.darken.capod.common.upgrade.UpgradeRepo
import eu.darken.capod.main.ui.widget.WidgetManager
import eu.darken.capod.monitor.core.PodMonitor
import eu.darken.capod.monitor.core.worker.MonitorControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import javax.inject.Inject

class MyBroadcastReceiver(val onReceive: () -> Unit): BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if(intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            this.onReceive()
    }

}
interface Media2RouterImpl {
    public abstract fun selectRoute(route: MediaRoute2Info, sessionInfo: RoutingSessionInfo)
}
@HiltAndroidApp
open class App : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var autoReporting: AutomaticBugReporter
    @Inject lateinit var monitorControl: MonitorControl
    @Inject lateinit var podMonitor: PodMonitor
    @Inject lateinit var widgetManager: WidgetManager
    @Inject lateinit var upgradeRepo: UpgradeRepo
    @Inject lateinit var playPause: MediaControl
    @Inject @AppScope lateinit var appScope: CoroutineScope

    private fun addAutoSwitcher() {
        val onPlaybackCallback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    super.onPlaybackStateChanged(state)
                    if(state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_PAUSED) {
                        appScope.launch {
                            podMonitor.onPlaybackChanged()
                        }
                    }
                }
        }


        val mediaSessionService = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSessionService.getActiveSessions(null).forEach {
            it.registerCallback(onPlaybackCallback)
        }
        mediaSessionService.addOnActiveSessionsChangedListener( {controllers -> controllers?.forEach {
            it.registerCallback(onPlaybackCallback)
        }
        }, null)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Logging.install(LogCatLogger())

        autoReporting.setup(this)
        log(TAG) { "onCreate() done! ${Exception().asLog()}" }
        appScope.launch {
            monitorControl.startMonitor(forceStart = true)
        }
        addAutoSwitcher()

        podMonitor.mainDevice
            .distinctUntilChanged()
            .throttleLatest(1000)
            .onEach {
                log(TAG) { "Main device changed, refreshing widgets." }
                widgetManager.refreshWidgets()
            }
            .launchIn(appScope)

        upgradeRepo.upgradeInfo
            .map { it.isPro }
            .distinctUntilChanged()
            .onEach {
                log(TAG) { "Pro status changed, refreshing widgets." }
                widgetManager.refreshWidgets()
            }
            .launchIn(appScope)
    }

    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder()
        .setMinimumLoggingLevel(android.util.Log.VERBOSE)
        .setWorkerFactory(workerFactory)
        .build()

    companion object {
        internal val TAG = logTag("CAP")
    }
}
