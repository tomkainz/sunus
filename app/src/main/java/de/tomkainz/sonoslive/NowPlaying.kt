package de.tomkainz.sonoslive

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import de.tomkainz.sonoslive.adb.AdbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class NowPlayingInfo(
    val title: String,
    val artist: String?,
    val art: Bitmap?,
    val playing: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean
)

/** Decides which media session is shown. */
object SessionPicker {

    data class Candidate(val packageName: String, val title: String?, val playing: Boolean)

    /** Index of the session to show: the first playing one, else the first with a title. */
    fun pick(candidates: List<Candidate>, ownPackage: String): Int {
        val usable = candidates.withIndex().filter {
            it.value.packageName != ownPackage && !it.value.title.isNullOrBlank()
        }
        return (usable.firstOrNull { it.value.playing } ?: usable.firstOrNull())?.index ?: -1
    }
}

/**
 * What the phone is playing, from the media session of whichever app plays it,
 * so it works the same for every music app. Title and cover need notification
 * access; play, pause and skip work without it, as media keys. Only watched
 * while the app is on screen.
 */
object NowPlaying {

    val info = MutableStateFlow<NowPlayingInfo?>(null)
    val enabled = MutableStateFlow(false)

    /** Without access this is all that is known: something is playing, or not. */
    val musicActive = MutableStateFlow(false)

    private val handler = Handler(Looper.getMainLooper())
    private val watched = mutableListOf<MediaController>()
    private var manager: MediaSessionManager? = null
    private var current: MediaController? = null
    private var ownPackage = ""
    private var audio: AudioManager? = null

    private val playbackChanged = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            refreshMusicActive()
            // A pausing player still counts as active for a moment.
            handler.removeCallbacks(refreshLater)
            handler.postDelayed(refreshLater, 800)
        }
    }
    private val refreshLater = Runnable { refreshMusicActive() }

    private fun refreshMusicActive() {
        musicActive.value = audio?.isMusicActive == true
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = update()
    }

    private fun hasAccess(context: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun component(context: Context) = ComponentName(context, MediaListenerService::class.java)

    fun start(context: Context) {
        stop()
        val app = context.applicationContext
        ownPackage = app.packageName
        audio = app.getSystemService(AudioManager::class.java).also {
            it.registerAudioPlaybackCallback(playbackChanged, handler)
        }
        refreshMusicActive()
        enabled.value = hasAccess(app)
        if (!enabled.value) {
            info.value = null
            return
        }
        runCatching {
            val sessions = app.getSystemService(MediaSessionManager::class.java)
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, component(app), handler)
            manager = sessions
            watch(sessions.getActiveSessions(component(app)))
        }.onFailure { AppState.log("Now playing: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun stop() {
        audio?.unregisterAudioPlaybackCallback(playbackChanged)
        audio = null
        handler.removeCallbacks(refreshLater)
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        manager = null
        watched.forEach { it.unregisterCallback(callback) }
        watched.clear()
        current = null
    }

    private fun watch(sessions: List<MediaController>) {
        watched.forEach { it.unregisterCallback(callback) }
        watched.clear()
        sessions.forEach {
            it.registerCallback(callback, handler)
            watched += it
        }
        update()
    }

    private fun isPlaying(controller: MediaController): Boolean =
        when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true
            else -> false
        }

    private fun title(controller: MediaController): String? =
        controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: controller.metadata?.description?.title?.toString()

    private var lastSession = ""

    private fun update() {
        val index = SessionPicker.pick(
            watched.map { SessionPicker.Candidate(it.packageName, title(it), isPlaying(it)) }, ownPackage
        )
        val controller = watched.getOrNull(index)
        current = controller
        val shown = session()
        if (shown != lastSession) {
            lastSession = shown
            AppState.log("Now playing: session $shown")
        }
        info.value = controller?.let {
            val metadata = it.metadata
            val actions = it.playbackState?.actions ?: 0L
            NowPlayingInfo(
                title = title(it).orEmpty(),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: metadata?.description?.subtitle?.toString(),
                art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
                playing = isPlaying(it),
                canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
                canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
            )
        }
    }

    fun playPause() {
        val controller = current
        AppState.log("Now playing: play/pause pressed")
        when {
            controller == null -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            isPlaying(controller) -> controller.transportControls.pause()
            else -> controller.transportControls.play()
        }
    }

    fun next() {
        val controller = current
        AppState.log("Now playing: next pressed")
        if (controller == null) mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) else controller.transportControls.skipToNext()
    }

    fun previous() {
        val controller = current
        AppState.log("Now playing: previous pressed")
        if (controller == null) mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) else controller.transportControls.skipToPrevious()
    }

    /** Works without notification access: Android hands it to the app that played last. */
    private fun mediaKey(code: Int) {
        val manager = audio ?: return
        AppState.log("Now playing: media key ${KeyEvent.keyCodeToString(code)}")
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** For the log: which app is shown. Titles stay out, they are nobody's business. */
    fun session(): String = current?.let { "${it.packageName}, state ${it.playbackState?.state}" } ?: "none"

    /**
     * Turns access on or off. With a pairing, the shell can do it directly, which
     * also gets past Android's "restricted settings" block for sideloaded apps.
     * Otherwise the user decides in Settings.
     */
    fun setAccess(context: Context, on: Boolean) {
        val app = context.applicationContext
        AppState.log("Now playing: turning access ${if (on) "on" else "off"}")
        AppState.scope.launch {
            val done = AppState.adbPaired.value && runCatching {
                AdbManager.connect(app) && AdbManager.setNotificationAccess(app, on)
            }.onFailure { AppState.log("Now playing: shell failed: $it") }.getOrDefault(false)
            AppState.log("Now playing: " + if (done) "access set through the shell" else "opening Settings")
            handler.post { if (done) start(app) else openSettings(app) }
        }
    }

    private fun openSettings(context: Context) {
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component(context).flattenToString()
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        try {
            context.startActivity(detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
