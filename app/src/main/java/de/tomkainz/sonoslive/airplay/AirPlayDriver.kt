package de.tomkainz.sonoslive.airplay

import de.tomkainz.sonoslive.AppState
import de.tomkainz.sonoslive.Speaker
import de.tomkainz.sonoslive.SpeakerCheck
import de.tomkainz.sonoslive.SpeakerControl
import de.tomkainz.sonoslive.SpeakerDriver
import de.tomkainz.sonoslive.SpeakerRefused
import de.tomkainz.sonoslive.StreamEndpoint
import java.io.IOException

/** An AirPlay speaker: unlike the others, it is sent the audio instead of fetching it. */
class AirPlayDriver(private val speaker: Speaker) : SpeakerDriver {

    override val pullsStream get() = false

    private val port get() = speaker.address.toIntOrNull() ?: DEFAULT_PORT

    @Volatile
    private var session: AirPlaySession? = null
    private var streamer: AirPlayStreamer? = null
    private var keepAlive: Thread? = null
    private var restarts = 0
    private var everStarted = false

    /**
     * Answers of "busy" in a row. Right after our own session broke that can still be
     * us, so only a few in a row mean somebody else is playing to this speaker.
     */
    private var busyAnswers = 0
    private var lastFailure: Exception? = null
    private var startedAt = 0L

    init {
        // What was chosen for another speaker must not carry over.
        desiredVolume = null
        desiredMute = false
    }

    override fun start(stream: StreamEndpoint) {
        closeSession(stream)
        val features = AirPlayFeatures.fromDetails(speaker.details)
        if (features.passwordRequired) throw SpeakerRefused("AirPlay: this speaker has a password")
        var failure: Exception? = null
        var busyNow = false
        for (mode in features.modes()) {
            try {
                connectWithRetry(mode, features, stream)
                busyAnswers = 0
                return
            } catch (e: Exception) {
                AppState.log("AirPlay ${speaker.ip}: $mode failed: ${e.javaClass.simpleName}: ${e.message}")
                closeSession(stream)
                if (e is RtspError && e.code == BUSY) busyNow = true
                // Keep the first reason, it comes from the way the speaker said it supports.
                if (failure == null || (e is SpeakerRefused && failure !is SpeakerRefused)) failure = e
            }
        }
        busyAnswers = if (busyNow) busyAnswers + 1 else 0
        lastFailure = failure
        throw failure ?: IOException("AirPlay: no way in")
    }

    /**
     * The references disagree on how a pairing salt with a leading zero enters the
     * proof, which hits one pairing in 256. The next one has a new salt.
     */
    private fun connectWithRetry(mode: AirPlayMode, features: AirPlayFeatures, stream: StreamEndpoint) {
        try {
            connect(mode, features, stream)
        } catch (e: SpeakerRefused) {
            if (mode != AirPlayMode.AIRPLAY_2 || e.message?.contains("pairing refused") != true) throw e
            AppState.log("AirPlay ${speaker.ip}: pairing refused, once more")
            closeSession(stream)
            connect(mode, features, stream)
        }
    }

    private fun connect(mode: AirPlayMode, features: AirPlayFeatures, stream: StreamEndpoint) {
        // Older devices run the classic service on a port of its own.
        val target = if (mode == AirPlayMode.CLASSIC) features.classicPort ?: port else port
        AppState.log("AirPlay ${speaker.ip}:$target: connecting, $mode")
        val s = AirPlaySession(speaker.ip, target, mode)
        session = s
        val sender = AirPlayStreamer(speaker.ip)
        streamer = sender
        s.setup(sender.timingPort, sender.controlPort)
        s.record(sender.firstSequence, sender.firstTimestamp)
        active = s
        desiredVolume?.let { runCatching { s.setVolumeDb(AirPlaySession.percentToDb(it)) } }
        sender.start(s)
        stream.server.addSink(sender)
        startedAt = System.nanoTime()
        everStarted = true
        AppState.log("AirPlay ${speaker.ip}: streaming, $mode, data ${s.dataPort}, control ${s.controlPort}")
        keepAlive = Thread({
            while (s.alive) {
                try {
                    Thread.sleep(s.keepAliveEveryMs)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (!s.keepAlive()) return@Thread
            }
        }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    override fun check(stream: StreamEndpoint): SpeakerCheck {
        // Somebody else has the speaker now. Do not fight over it.
        if (busyAnswers >= BUSY_MEANS_TAKEN) return SpeakerCheck.Left
        if (!everStarted) return SpeakerCheck.Fine("")
        val s = session
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000
        return when {
            s != null && s.alive && (streamer?.silentForMs ?: 0) > STUCK_AFTER_MS -> {
                AppState.log("AirPlay ${speaker.ip}: sending stands still: ${streamer?.senderPosition()}")
                SpeakerCheck.Restart("sending stands still")
            }
            s != null && s.alive -> {
                // Long enough into the session to count as fine again.
                if (seconds > STABLE_AFTER_S) restarts = 0
                SpeakerCheck.Fine(if (streamer?.timingSeen == true) "PLAYING" else "CONNECTED")
            }
            ++restarts > MAX_RESTARTS -> SpeakerCheck.Failed("AirPlay session could not be restarted", lastFailure)
            else -> SpeakerCheck.Restart("AirPlay session ended after $seconds s")
        }
    }

    private fun closeSession(stream: StreamEndpoint? = null) {
        keepAlive?.interrupt()
        keepAlive = null
        streamer?.let { sender ->
            stream?.server?.removeSink(sender)
            sinkOwner?.removeSink(sender)
            sender.stop()
        }
        streamer = null
        if (active === session) active = null
        session?.close()
        session = null
    }

    override fun stop() = closeSession()

    private val sinkOwner get() = de.tomkainz.sonoslive.LiveStreamServer.active

    companion object {
        const val DEFAULT_PORT = 7000
        private const val MAX_RESTARTS = 5
        private const val BUSY = 453
        private const val BUSY_MEANS_TAKEN = 3
        private const val STUCK_AFTER_MS = 4000L
        private const val STABLE_AFTER_S = 60

        @Volatile
        internal var active: AirPlaySession? = null

        /** Kept for the next start: an AirPlay speaker is only reachable during a session. */
        @Volatile
        internal var desiredVolume: Int? = null

        @Volatile
        internal var desiredMute = false
    }
}

/** Volume exists only while the speaker is connected, so before that there is none to show. */
object AirPlayControl : SpeakerControl {

    override fun volume(): Int? {
        val s = AirPlayDriver.active ?: return null
        AirPlayDriver.desiredVolume?.let { return it }
        return s.volumeDb()?.let(AirPlaySession::dbToPercent)?.also { AirPlayDriver.desiredVolume = it } ?: 50
    }

    override fun setVolume(v: Int) {
        AirPlayDriver.desiredVolume = v
        AirPlayDriver.desiredMute = false
        AirPlayDriver.active?.setVolumeDb(AirPlaySession.percentToDb(v))
    }

    override fun muted() = AirPlayDriver.desiredMute

    override fun setMuted(on: Boolean) {
        AirPlayDriver.desiredMute = on
        AirPlayDriver.active?.setVolumeDb(if (on) -144.0 else AirPlaySession.percentToDb(AirPlayDriver.desiredVolume ?: 50))
    }
}
