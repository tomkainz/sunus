package de.tomkainz.sonoslive

import android.content.Context

/** Volume and mute of a speaker. Null means the speaker cannot say right now. */
interface SpeakerControl {
    fun volume(): Int?
    fun setVolume(v: Int)
    fun muted(): Boolean
    fun setMuted(on: Boolean)
}

/** What the watchdog should do next. */
sealed interface SpeakerCheck {
    /** Plays, or is on its way there. [state] goes into the log when it changes. */
    data class Fine(val state: String) : SpeakerCheck

    /** The speaker stopped: point it at the stream again. */
    data class Restart(val reason: String) : SpeakerCheck

    /** Somebody chose something else on the speaker. Leave it alone and end the stream. */
    data object Left : SpeakerCheck

    /** Nothing worked. [reason] goes into the report, [cause] decides its code. */
    data class Failed(val reason: String, val cause: Exception? = null) : SpeakerCheck
}

/** Where a speaker finds the stream. */
class StreamEndpoint(val baseUrl: String, val server: LiveStreamServer, val localIp: String) {
    val url get() = baseUrl + LiveStreamServer.PATH
}

/** The speaker refused this phone: wrong access setting, or it wants a code. */
class SpeakerRefused(message: String) : Exception(message)

/** One brand's way of getting the live stream onto a speaker. */
interface SpeakerDriver {
    /** Does the speaker fetch the HTTP stream? AirPlay is sent to the speaker instead. */
    val pullsStream: Boolean get() = true

    /** Blocks until the speaker has been told. Throws if it would not listen. */
    fun start(stream: StreamEndpoint)

    /** Called every 2.5 s, one at a time. */
    fun check(stream: StreamEndpoint): SpeakerCheck

    /** Network; never on the main thread. */
    fun stop()

    /** An address other than the speaker's own that may fetch the stream. */
    fun allowsPeer(peer: String): Boolean = false
}

object SpeakerDrivers {

    fun driver(context: Context, speaker: Speaker): SpeakerDriver = when (speaker.protocol) {
        SpeakerProtocol.SONOS -> SonosDriver(speaker)
        SpeakerProtocol.UPNP -> UpnpDriver(speaker, Remembered(context))
        SpeakerProtocol.CAST -> CastDriver(speaker, Remembered(context))
        SpeakerProtocol.AIRPLAY -> de.tomkainz.sonoslive.airplay.AirPlayDriver(speaker)
    }

    fun control(speaker: Speaker): SpeakerControl = when (speaker.protocol) {
        SpeakerProtocol.SONOS -> Sonos(speaker)
        SpeakerProtocol.UPNP -> UpnpControl(speaker)
        SpeakerProtocol.CAST -> CastControl(speaker)
        SpeakerProtocol.AIRPLAY -> de.tomkainz.sonoslive.airplay.AirPlayControl
    }
}

/** What worked on a speaker last time, so the next start does not have to find it again. */
class Remembered(context: Context) {
    private val prefs = context.getSharedPreferences("speaker-profiles", Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs.getString(key, null)
    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/** Sonos: as it has always been, with the group coordinator taking the source. */
class SonosDriver(private val speaker: Speaker) : SpeakerDriver {

    @Volatile
    private var coordinatorIp: String? = null
    @Volatile
    private var target: Sonos? = null
    private var repoints = 0

    override fun start(stream: StreamEndpoint) {
        // In a group, only the coordinator accepts the source.
        val ip = runCatching { Sonos(speaker).coordinatorIp() }.getOrDefault(speaker.ip)
        coordinatorIp = ip
        val sonos = Sonos(speaker.copy(ip = ip))
        target = sonos
        AppState.log("Sonos $ip: play ${stream.url}")
        sonos.playStream(stream.url)
        AppState.log("Sonos $ip: playing")
    }

    override fun check(stream: StreamEndpoint): SpeakerCheck {
        val sonos = target ?: return SpeakerCheck.Fine("")
        val current = sonos.currentUri()
        val state = sonos.transportState()
        return when {
            current.isNotEmpty() && current != stream.url -> {
                // Someone switched source: do not stop the speaker, it plays the new source now.
                target = null
                SpeakerCheck.Left
            }
            state == "STOPPED" -> {
                val line = "Sonos stopped (status ${sonos.transportStatus()})"
                // Only a few in the report, so it keeps the earlier lines; all in the log.
                if (++repoints <= 3) AppState.log(line) else AppLog.append(line)
                SpeakerCheck.Restart(line)
            }
            else -> SpeakerCheck.Fine(state)
        }
    }

    override fun stop() {
        target?.let { runCatching { it.stop() } }
        target = null
    }

    override fun allowsPeer(peer: String) =
        peer == coordinatorIp || AppState.speakers.value.any { it.ip == peer } ||
            sonosPeers.getOrPut(peer) { Ssdp.probe(peer) != null }

    private val sonosPeers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
}
