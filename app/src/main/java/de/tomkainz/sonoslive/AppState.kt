package de.tomkainz.sonoslive

import android.content.Context
import android.os.Build
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CaptureMode {
    /** Shell helper over the app's own ADB connection: every app, phone muted. */
    ALL_APPS,

    /** Official AudioPlaybackCapture: no setup, but apps may opt out. */
    STANDARD
}

/** Shared state for the UI and the service. */
object AppState {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences

    val speakers = MutableStateFlow<List<Speaker>>(emptyList())
    val selected = MutableStateFlow<Speaker?>(null)
    val searching = MutableStateFlow(false)

    /** The shell helper reads REMOTE_SUBMIX, which needs Android 12. */
    val allAppsSupported = Build.VERSION.SDK_INT >= 31

    val mode = MutableStateFlow(if (allAppsSupported) CaptureMode.ALL_APPS else CaptureMode.STANDARD)
    val muteLocal = MutableStateFlow(true)
    val automationAllowed = MutableStateFlow(false)
    val streamBaseOverride = MutableStateFlow("")

    /** Has ADB pairing succeeded once? */
    val adbPaired = MutableStateFlow(false)
    /** Only while the code is being checked; before that, pairing can be restarted. */
    val adbBusy = MutableStateFlow(false)
    val adbMessage = MutableStateFlow<String?>(null)

    /** Set for the UI when the All apps mode needs pairing. */
    val requestPairing = MutableStateFlow(false)

    fun needsPairing() {
        setAdbPaired(false)
        requestPairing.value = true
    }

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)

    /** Latest error report (see [Diagnose]), for sharing or saving. */
    val report = MutableStateFlow<String?>(null)
    val streamUrl = MutableStateFlow<String?>(null)
    val clients = MutableStateFlow(0)
    val lastClient = MutableStateFlow<String?>(null)
    val audioFlowing = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val volume = MutableStateFlow<Int?>(null)
    val muted = MutableStateFlow(false)
    val eq = MutableStateFlow<Eq?>(null)
    /** Recent log lines, newest first; they go into the error report. */
    val requestLog = MutableStateFlow<List<String>>(emptyList())

    /** For messages from places without their own Context. */
    private lateinit var app: Context

    fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    fun init(context: Context) {
        app = context.applicationContext
        prefs = context.getSharedPreferences("sonoslive", Context.MODE_PRIVATE)
        status.value = text(R.string.status_ready)
        mode.value = runCatching { CaptureMode.valueOf(prefs.getString("mode", null)!!) }
            .getOrDefault(CaptureMode.ALL_APPS)
            .takeIf { allAppsSupported } ?: CaptureMode.STANDARD
        muteLocal.value = prefs.getBoolean("muteLocal", true)
        automationAllowed.value = prefs.getBoolean("automation", false)
        streamBaseOverride.value = prefs.getString("streamBase", "") ?: ""
        adbPaired.value = prefs.getBoolean("adbPaired", false)
        val ip = prefs.getString("speakerIp", null)
        val udn = prefs.getString("speakerUdn", null)
        if (ip != null && udn != null) {
            selected.value = Speaker(
                room = prefs.getString("speakerRoom", ip) ?: ip,
                ip = ip,
                udn = udn,
                model = prefs.getString("speakerModel", "Sonos") ?: "Sonos",
                protocol = runCatching { SpeakerProtocol.valueOf(prefs.getString("speakerProtocol", null)!!) }
                    .getOrDefault(SpeakerProtocol.SONOS),
                address = prefs.getString("speakerAddress", "") ?: "",
                details = prefs.getString("speakerDetails", "") ?: ""
            )
        }
        logChanges()
    }

    /** What the user saw, in the order they saw it. Volume and level would only flood the log. */
    private fun logChanges() {
        watch("Mode", mode)
        watch("Running", running)
        watch("Status", status)
        watch("Message", message)
        watch("Audio flowing", audioFlowing)
        watch("Stream clients", clients)
        watch("Stream URL", streamUrl)
        watch("Selected speaker", selected)
        watch("Speakers found", speakers)
        watch("ADB paired", adbPaired)
        watch("ADB message", adbMessage)
        watch("Pairing asked", requestPairing)
        watch("Now playing access", NowPlaying.enabled)
    }

    private fun <T> watch(name: String, flow: StateFlow<T>) {
        scope.launch { flow.collect { log("$name: $it") } }
    }

    fun setMode(m: CaptureMode) {
        if (m == CaptureMode.ALL_APPS && !allAppsSupported) return
        mode.value = m
        prefs.edit().putString("mode", m.name).apply()
    }

    fun setMuteLocal(on: Boolean) {
        muteLocal.value = on
        prefs.edit().putBoolean("muteLocal", on).apply()
    }

    fun setAutomationAllowed(on: Boolean) {
        automationAllowed.value = on
        prefs.edit().putBoolean("automation", on).apply()
    }

    fun setStreamBaseOverride(value: String) {
        streamBaseOverride.value = value.trim().trimEnd('/')
        prefs.edit().putString("streamBase", streamBaseOverride.value).apply()
    }

    fun setAdbPaired(on: Boolean) {
        adbPaired.value = on
        prefs.edit().putBoolean("adbPaired", on).apply()
    }

    fun select(speaker: Speaker) {
        selected.value = speaker
        prefs.edit()
            .putString("speakerIp", speaker.ip)
            .putString("speakerUdn", speaker.udn)
            .putString("speakerRoom", speaker.room)
            .putString("speakerModel", speaker.model)
            .putString("speakerProtocol", speaker.protocol.name)
            .putString("speakerAddress", speaker.address)
            .putString("speakerDetails", speaker.details)
            .apply()
        refreshVolume()
    }

    fun discover(context: Context) {
        if (searching.value) return
        searching.value = true
        scope.launch {
            try {
                // 1. Quickly via SSDP and mDNS (multicast)
                var found = Discovery.search(context)

                // 2. If that finds nothing, scan the local network (routers
                //    sometimes filter multicast). Hits show up in the list right away.
                if (found.isEmpty()) {
                    val ip = Net.localIp(context)
                    if (ip != null) {
                        // A search during a stream must not overwrite "Live".
                        if (!running.value) status.value = text(R.string.status_scanning)
                        found = Discovery.scan(ip) { sp -> addFound(sp) }
                        if (!running.value) status.value = text(R.string.status_ready)
                    }
                }

                val current = selected.value
                val merged = Discovery.merge(Ssdp.visibleOnly(found + speakers.value + listOfNotNull(current)))
                speakers.value = merged
                if (current == null && merged.size == 1) select(merged.first())
                else if (current != null) refreshSelected(current, merged)
                // The empty state is already shown in the list; no duplicate message.
                refreshVolume()
            } finally {
                searching.value = false
            }
        }
    }

    /**
     * The search may have found another way into the selected device, or a better one.
     * While streaming, the running driver stays as it is.
     */
    private fun refreshSelected(current: Speaker, merged: List<Speaker>) {
        if (running.value) return
        merged.firstOrNull { row -> row.udn == current.udn || row.alternatives.any { it.udn == current.udn } }
            ?.takeIf { it != current }
            ?.let { select(it) }
    }

    /** Adds a speaker found during the search right away. */
    private fun addFound(speaker: Speaker) {
        val merged = Discovery.merge(listOf(speaker) + speakers.value)
        speakers.value = merged
        if (selected.value == null && merged.size == 1) select(speaker)
    }

    fun addManual(ip: String) {
        val clean = ip.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val speaker = Discovery.probe(clean).firstOrNull()
            if (speaker == null) {
                message.value = text(R.string.msg_no_speaker_at, clean)
            } else {
                speakers.value = Discovery.merge(listOf(speaker) + speakers.value)
                select(speaker)
            }
        }
    }

    /** [speaker] is who the stream really went to, if not the selected one's first way in. */
    fun refreshVolume(speaker: Speaker? = selected.value) {
        val target = speaker ?: return
        activeControl = SpeakerDrivers.control(target)
        scope.launch {
            val control = SpeakerDrivers.control(target)
            volume.value = runCatching { control.volume() }.getOrNull()
            muted.value = runCatching { control.muted() }.getOrDefault(false)
            // Only Sonos has these settings.
            eq.value = (control as? Sonos)?.let { sonos -> runCatching { sonos.eq() }.getOrNull() }
            eqOnSpeaker = eq.value
        }
    }

    /** Who the slider talks to. */
    @Volatile
    private var activeControl: SpeakerControl? = null

    private fun control(): SpeakerControl? = activeControl ?: selected.value?.let { SpeakerDrivers.control(it) }

    private var eqJob: Job? = null

    /** What the speaker has, as far as we know; set on load and after sending. */
    @Volatile
    private var eqOnSpeaker: Eq? = null

    /**
     * Like [setVolume]: only the last value of a drag is sent. Only what
     * differs from the speaker is sent, so a bass drag does not also rewrite
     * the treble.
     */
    fun setEq(value: Eq) {
        val speaker = selected.value ?: return
        eq.value = value
        eqJob?.cancel()
        eqJob = scope.launch {
            delay(150)
            val before = eqOnSpeaker
            runCatching {
                val sonos = Sonos(speaker)
                if (before == null || before.bass != value.bass) sonos.setBass(value.bass)
                if (before == null || before.treble != value.treble) sonos.setTreble(value.treble)
                if (before == null || before.loudness != value.loudness) sonos.setLoudness(value.loudness)
                if (value.night != null && before?.night != value.night) sonos.setNightMode(value.night)
                if (value.speech != null && before?.speech != value.speech) sonos.setSpeech(value.speech)
                eqOnSpeaker = value
            }.onFailure { message.value = text(R.string.msg_eq_error, it.message ?: "") }
        }
    }

    private var volumeJob: Job? = null

    /**
     * The slider calls this for every frame of a drag. Only the last value is
     * sent, shortly after, so requests cannot overtake each other.
     */
    fun setVolume(v: Int) {
        val target = control() ?: return
        val value = v.coerceIn(0, 100)
        volume.value = value
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(150)
            runCatching {
                target.setVolume(value)
                // As in the Sonos app: changing the volume ends muting.
                if (muted.value) {
                    target.setMuted(false)
                    muted.value = false
                }
            }.onFailure { message.value = text(R.string.msg_volume_error, it.message ?: "") }
        }
    }

    fun setMuted(on: Boolean) {
        val target = control() ?: return
        muted.value = on
        scope.launch {
            runCatching { target.setMuted(on) }
                .onFailure {
                    muted.value = !on
                    message.value = text(R.string.msg_volume_error, it.message ?: "")
                }
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun log(line: String) {
        val stamped = synchronized(timeFormat) { "${timeFormat.format(Date())}  $line" }
        android.util.Log.i("Sunus", line)
        AppLog.append(line)
        requestLog.update { (listOf(stamped) + it).take(100) }
    }
}
