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
import java.io.IOException
import kotlinx.coroutines.launch

/**
 * Keeps the live stream alive, captures audio and points the speaker at the URL.
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
    private var lowLatencyLock: WifiManager.WifiLock? = null

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var endpoint: StreamEndpoint? = null
    @Volatile
    private var driver: SpeakerDriver? = null

    private var standardMode = false

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

        // Already streaming (double tap, second consent): keep the running
        // stream. startForeground is still due after startForegroundService.
        if (server != null) {
            startInForeground(standard = standardMode)
            return START_NOT_STICKY
        }

        Diagnose.beginSession()
        val modeName = intent?.getStringExtra(EXTRA_MODE) ?: AppState.mode.value.name
        val mode = runCatching { CaptureMode.valueOf(modeName) }.getOrDefault(AppState.mode.value)
        standardMode = mode == CaptureMode.STANDARD
        restoreLeftoverVolume(this)

        startInForeground(standard = standardMode)
        acquireWifiLock()

        val speaker = AppState.selected.value
        if (speaker == null) {
            AppState.message.value = getString(R.string.msg_no_speaker)
            stopSelf()
            return START_NOT_STICKY
        }

        val ip = Net.localIp(this, speaker.ip)
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
        val stream = StreamEndpoint(base, srv, ip)
        endpoint = stream
        AppState.streamUrl.value = stream.url

        // Clear the old message now, not after starting: capture runs in its
        // own thread and can fail before that.
        AppState.message.value = null
        // Only the speaker may fetch the stream, not every device on the Wi-Fi.
        // With a hand-set address (emulator port forwarding) anyone may. Who else
        // counts as the speaker (say, a group member that fetches for the
        // coordinator) is up to its driver. AirPlay is sent the audio instead,
        // so with it nobody needs to fetch anything.
        if (AppState.streamBaseOverride.value.isBlank()) {
            srv.allowPeer = { peer ->
                val active = driver
                active != null && active.pullsStream && (peer == speaker.ip || active.allowsPeer(peer))
            }
        }

        // If capture ends by itself, end the service too instead of showing
        // "Live" while only silence goes out. The error is already reported.
        if (mode == CaptureMode.ALL_APPS) warnIfMediaVolumeLow()

        val eng = CaptureEngine(this, srv) { handler.post { if (engine != null) stopEverything() } }
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
        pointSpeakerAtStream()
        startWatchdog()
        // Not sticky: after a crash the system would restart the service without
        // the capture consent, and it would only send silence.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopInternals()
        super.onDestroy()
    }

    // ---- Speaker ----------------------------------------------------------

    /**
     * True while the speaker is being pointed at the stream or checked. Play can
     * take many seconds to answer; a second one from the watchdog in that time
     * would restart the speaker's connection attempt.
     */
    private val speakerBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastSpeakerState = ""
    private var lastCheckError = ""
    private var ticks = 0
    private var playingChecks = 0

    /** The device's ways in that have not had their turn in this stream. */
    private val untried = ArrayDeque<Speaker>()
    private var firstFailure: Exception? = null
    private var firstFailurePulled = true
    @Volatile
    private var playingTo: Speaker? = null

    /**
     * Starts with the way that worked last time, or the one the search put first.
     * The other ways of a device get their turn if it will not play.
     */
    private fun pointSpeakerAtStream() {
        val speaker = AppState.selected.value ?: return
        val remembered = Remembered(this).get(wayKey(speaker))
        val ways = (listOf(speaker) + speaker.alternatives).sortedByDescending { it.protocol.name == remembered }
        untried.clear()
        untried.addAll(ways)
        firstFailure = null
        speakerBusy.set(true)
        AppState.scope.launch {
            try {
                tryNextWay()
            } finally {
                speakerBusy.set(false)
            }
        }
    }

    private fun wayKey(speaker: Speaker) = "way.${speaker.ip}"

    /** One way after the other, until one has been told to play. Runs off the main thread. */
    private fun tryNextWay() {
        val stream = endpoint ?: return
        while (AppState.running.value) {
            val candidate = untried.removeFirstOrNull() ?: break
            val attempt = SpeakerDrivers.driver(this, candidate)
            driver?.let { previous -> runCatching { previous.stop() } }
            driver = attempt
            playingTo = candidate
            try {
                attempt.start(stream)
                AppState.refreshVolume(candidate)
                return
            } catch (e: Exception) {
                if (!AppState.running.value) return
                AppState.log("${candidate.protocol}: ${e.javaClass.simpleName}: ${e.message}")
                noteFailure(e, attempt)
            }
        }
        reportFailure(stream)
        // A Sonos that does not answer is watched until it does, as it always was. The
        // others have been through everything there is to try.
        if (playingTo?.protocol != SpeakerProtocol.SONOS) handler.post { stopEverything() }
    }

    private fun noteFailure(e: Exception, attempt: SpeakerDriver) {
        // The first failure is the one to report: it is from the way most likely to work.
        if (firstFailure == null) {
            firstFailure = e
            firstFailurePulled = attempt.pullsStream
        }
    }

    private fun reportFailure(stream: StreamEndpoint) {
        val e = firstFailure ?: return
        val srv = stream.server
        val requests = srv.streamRequests.get()
        val refused = synchronized(srv.refusedPeers) { srv.refusedPeers.toList() }
        val facts = "Stream URL: ${stream.url}\nStream requests: $requests\n" +
            "Refused: ${refused.ifEmpty { listOf("none") }.joinToString()}\n\n" +
            e.stackTraceToString()
        val code = Diagnose.Code.forSpeakerFailure(e, requests, firstFailurePulled)
        Diagnose.fail(this@LiveService, code, e.message, facts)
    }

    /**
     * Keeps playback going: if the speaker switches source (say, from its own
     * app), we shut down; if it drops out, we point it at the stream again.
     */
    private fun startWatchdog() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!AppState.running.value) return
                // Once a minute, so the log shows how things went before something breaks.
                if (++ticks % 24 == 0) AppState.log("Stream: ${server?.stats()}. ${deviceState()}")
                val target = driver
                val stream = endpoint
                if (target != null && stream != null && speakerBusy.compareAndSet(false, true)) {
                    AppState.scope.launch {
                        try {
                            val check = target.check(stream)
                            lastCheckError = ""
                            when (check) {
                                is SpeakerCheck.Fine -> {
                                    if (check.state.isNotEmpty() && check.state != lastSpeakerState) {
                                        lastSpeakerState = check.state
                                        AppState.log("Speaker state: ${check.state}")
                                    }
                                    if (check.state == "PLAYING" && ++playingChecks == WAY_PROVEN_CHECKS) {
                                        playingTo?.let { Remembered(this@LiveService).set(wayKey(it), it.protocol.name) }
                                    }
                                }
                                is SpeakerCheck.Restart -> {
                                    lastSpeakerState = ""
                                    AppLog.append("Pointing the speaker at the stream again: ${check.reason}")
                                    target.start(stream)
                                }
                                SpeakerCheck.Left -> {
                                    AppState.log("The speaker left the stream for another source, stopping")
                                    // Someone switched source: end the stream, but do not
                                    // stop the speaker, it is playing the new source now.
                                    driver = null
                                    handler.post { stopEverything() }
                                }
                                is SpeakerCheck.Failed -> {
                                    AppState.log("Speaker failed: ${check.reason}")
                                    noteFailure(check.cause ?: IOException(check.reason), target)
                                    lastSpeakerState = ""
                                    playingChecks = 0
                                    tryNextWay()
                                }
                            }
                        } catch (e: Exception) {
                            // Next round tries again. The first failure in a row is worth a line:
                            // the phone cannot reach the speaker.
                            val error = "${e.javaClass.simpleName}: ${e.message}"
                            if (error != lastCheckError) AppState.log("Speaker check failed: $error")
                            lastCheckError = error
                        } finally {
                            speakerBusy.set(false)
                        }
                    }
                }
                handler.postDelayed(this, 2500)
            }
        }, 2500)
    }

    private fun deviceState(): String {
        val power = getSystemService(android.os.PowerManager::class.java)
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") val info = wifi?.connectionInfo
        return "Wi-Fi ${info?.rssi} dBm, ${info?.linkSpeed} Mbit/s, ${info?.frequency} MHz, " +
            "screen ${if (power.isInteractive) "on" else "off"}, doze ${power.isDeviceIdleMode}, " +
            "battery saver ${power.isPowerSaveMode}"
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

    // The saved volume lives in the preferences, so it comes back even if the
    // process died while the phone was muted.
    /**
     * REMOTE_SUBMIX is captured after the phone's media volume is applied: in the
     * emulator, volume 0 meant complete silence on the speaker, while "Live" was showing.
     */
    private fun warnIfMediaVolumeLow() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (volume * 2 >= am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) return
        AppState.log("All apps: media volume is low ($volume)")
        // A low volume may be on purpose, so say it once. Volume 0 can mean silence: every time.
        val prefs = prefs(this)
        if (volume > 0 && prefs.getBoolean(KEY_VOLUME_HINT_SEEN, false)) return
        prefs.edit().putBoolean(KEY_VOLUME_HINT_SEEN, true).apply()
        AppState.message.value = getString(R.string.msg_all_apps_volume)
    }

    private fun muteMusicStream() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs(this).edit().putInt(KEY_SAVED_VOLUME, am.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
    }

    private fun restoreMusicStream() = restoreLeftoverVolume(this)

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
        // Network: not on the main thread, where it would fail silently.
        driver?.let { target ->
            AppState.scope.launch {
                runCatching { target.stop() }
                // AirPlay has a volume only while connected.
                AppState.refreshVolume()
            }
        }
        driver = null
        endpoint = null
        playingTo = null
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
        runCatching { if (lowLatencyLock?.isHeld == true) lowLatencyLock?.release() }
        lowLatencyLock = null
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

    /**
     * Keeps Wi-Fi from dozing between packets. HIGH_PERF no longer has an effect
     * on newer Android versions; LOW_LATENCY also turns off Wi-Fi power saving,
     * but Android honours it only while the app is in front and the screen is on.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sonos-live")
            ?.apply { runCatching { acquire() } }
        lowLatencyLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "sonos-live-low-latency")
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
        private const val KEY_SAVED_VOLUME = "savedMusicVolume"
        private const val KEY_VOLUME_HINT_SEEN = "volumeHintSeen"

        /** After about half a minute of playing, this way counts as the one for this device. */
        private const val WAY_PROVEN_CHECKS = 12

        private fun prefs(context: Context) =
            context.getSharedPreferences("sonoslive", Context.MODE_PRIVATE)

        /** Puts back the phone's media volume if a Standard stream muted it. */
        fun restoreLeftoverVolume(context: Context) {
            val prefs = prefs(context)
            val saved = prefs.getInt(KEY_SAVED_VOLUME, -1)
            if (saved < 0) return
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
            prefs.edit().remove(KEY_SAVED_VOLUME).apply()
        }

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
