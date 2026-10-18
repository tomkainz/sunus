package de.tomkainz.sonoslive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.launch

/**
 * Keeps the live stream alive, captures audio and points the Sonos at the URL.
 *
 * Foreground service of type mediaProjection: from Android 14 on, projection
 * consent must exist before the service enters the foreground with this type,
 * which is why resultCode/data from the Activity come along in the start Intent.
 */
class LiveService : Service() {

    private var server: LiveStreamServer? = null
    private var engine: CaptureEngine? = null
    private var projection: MediaProjection? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val handler = Handler(Looper.getMainLooper())
    private var streamUrl: String? = null
    private var sonos: Sonos? = null

    private var savedMusicVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }

        Diagnose.beginSession()
        val modeName = intent?.getStringExtra(EXTRA_MODE) ?: CaptureMode.ALL_APPS.name
        val mode = runCatching { CaptureMode.valueOf(modeName) }.getOrDefault(CaptureMode.ALL_APPS)

        startInForeground(standard = mode == CaptureMode.STANDARD)
        acquireWifiLock()

        val speaker = AppState.selected.value
        if (speaker == null) {
            AppState.message.value = getString(R.string.msg_no_speaker)
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = Net.localIp(this)
        if (ip == null) {
            Diagnose.fail(this, Diagnose.Code.D1)
            stopSelf()
            return START_NOT_STICKY
        }

        val srv = LiveStreamServer()
        try {
            srv.start()
        } catch (e: Exception) {
            Diagnose.fail(this, Diagnose.Code.D2, e.message, e.stackTraceToString())
            stopSelf()
            return START_NOT_STICKY
        }
        server = srv

        // For the emulator, the stream address can be set by hand
        // (the Sonos only reaches the emulator through host port forwarding).
        val base = AppState.streamBaseOverride.value.ifBlank { "http://$ip:${srv.port}" }
        streamUrl = "$base${LiveStreamServer.PATH}"
        AppState.streamUrl.value = streamUrl

        // Clear the old message now, not after starting: capture runs in its
        // own thread and can fail before that.
        AppState.message.value = null
        val eng = CaptureEngine(this, srv)
        engine = eng

        when (mode) {
            CaptureMode.STANDARD -> {
                val proj = buildProjection(intent)
                if (proj == null) {
                    Diagnose.fail(this, Diagnose.Code.S3)
                    stopSelf()
                    return START_NOT_STICKY
                }
                projection = proj
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        handler.post { stopEverything() }
                    }
                }, handler)
                if (AppState.muteLocal.value) muteMusicStream()
                eng.startStandard(proj)
            }
            CaptureMode.ALL_APPS -> {
                // REMOTE_SUBMIX mutes the phone anyway.
                eng.startAllApps()
            }
        }

        AppState.running.value = true
        pointSonosAtStream()
        startWatchdog()
        return START_STICKY
    }

    override fun onDestroy() {
        stopInternals()
        super.onDestroy()
    }

    // ---- Sonos ------------------------------------------------------------

    private fun pointSonosAtStream() {
        val speaker = AppState.selected.value ?: return
        val url = streamUrl ?: return
        AppState.scope.launch {
            try {
                // In a group, only the coordinator accepts the source.
                val coordinatorIp = runCatching { Sonos(speaker).coordinatorIp() }.getOrDefault(speaker.ip)
                val target = Sonos(speaker.copy(ip = coordinatorIp))
                sonos = target
                target.playStream(url)
                AppState.refreshVolume()
            } catch (e: Exception) {
                Diagnose.fail(this@LiveService, Diagnose.Code.D3, e.message, e.stackTraceToString())
            }
        }
    }

    /**
     * Keeps playback going: if the Sonos switches source (say, from the Sonos
     * app), we shut down; if it drops to STOPPED, we point it at the stream
     * again (like "Sonos left our stream -> re-pointing").
     */
    private fun startWatchdog() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!AppState.running.value) return
                val target = sonos
                val url = streamUrl
                if (target != null && url != null) {
                    AppState.scope.launch {
                        runCatching {
                            val current = target.currentUri()
                            val state = target.transportState()
                            if (current.isNotEmpty() && current != url) {
                                // Someone switched source: end the stream.
                                handler.post { stopEverything() }
                            } else if (state == "STOPPED") {
                                target.playStream(url)
                            }
                        }
                    }
                }
                handler.postDelayed(this, 2500)
            }
        }, 2500)
    }

    // ---- Projection -------------------------------------------------------

    private fun buildProjection(intent: Intent?): MediaProjection? {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) return null
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return mpm.getMediaProjection(resultCode, data)
    }

    // ---- Phone volume -----------------------------------------------------

    private fun muteMusicStream() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedMusicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
    }

    private fun restoreMusicStream() {
        if (savedMusicVolume < 0) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0) }
        savedMusicVolume = -1
    }

    // ---- Teardown ---------------------------------------------------------

    private fun stopEverything() {
        stopInternals()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopInternals() {
        AppState.running.value = false
        AppState.status.value = getString(R.string.status_ready)
        handler.removeCallbacksAndMessages(null)
        runCatching { sonos?.stop() }
        sonos = null
        engine?.stop()
        engine = null
        server?.stop()
        server = null
        runCatching { projection?.stop() }
        projection = null
        restoreMusicStream()
        AppState.streamUrl.value = null
        AppState.clients.value = 0
        AppState.audioFlowing.value = false
        AppState.level.value = 0f
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    // ---- Foreground -------------------------------------------------------

    /**
     * The service type must match the mode. mediaProjection may only be set
     * when capture consent really exists (Standard mode, the Activity just
     * obtained it). Otherwise, from Android 14 on, Android rejects the start
     * with a SecurityException ("all of the permissions
     * [FOREGROUND_SERVICE_MEDIA_PROJECTION] any of
     * [CAPTURE_VIDEO_OUTPUT, android:project_media]") and the app crashes.
     * All apps mode has no projection, so connectedDevice is enough there.
     */
    private fun startInForeground(standard: Boolean) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (standard) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_live_text))
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_live), NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.channel_live_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sonos-live")
            ?.apply { runCatching { acquire() } }
    }

    companion object {
        private const val CHANNEL_ID = "sonos-live"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "de.tomkainz.sonoslive.START"
        const val ACTION_STOP = "de.tomkainz.sonoslive.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        fun startStandard(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, LiveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, CaptureMode.STANDARD.name)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun startAllApps(context: Context) {
            val intent = Intent(context, LiveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, CaptureMode.ALL_APPS.name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LiveService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
