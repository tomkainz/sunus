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
        streamBaseOverride.value = prefs.getString("streamBase", "") ?: ""
        adbPaired.value = prefs.getBoolean("adbPaired", false)
        val ip = prefs.getString("speakerIp", null)
        val udn = prefs.getString("speakerUdn", null)
        if (ip != null && udn != null) {
            selected.value = Speaker(
                room = prefs.getString("speakerRoom", ip) ?: ip,
                ip = ip,
                udn = udn,
                model = prefs.getString("speakerModel", "Sonos") ?: "Sonos"
            )
        }
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
            .apply()
        refreshVolume()
    }

    fun discover(context: Context) {
        if (searching.value) return
        searching.value = true
        scope.launch {
            try {
                // 1. Quickly via SSDP (multicast)
                var found = Ssdp.discover(context)

                // 2. If that finds nothing, scan the local network (routers
                //    sometimes filter multicast). Hits show up in the list right away.
                if (found.isEmpty()) {
                    val ip = Net.localIp(context)
                    if (ip != null) {
                        status.value = text(R.string.status_scanning)
                        found = Ssdp.scanSubnet(ip) { sp -> addFound(sp) }
                        status.value = text(R.string.status_ready)
                    }
                }

                val current = selected.value
                val merged = Ssdp.visibleOnly((found + speakers.value + listOfNotNull(current)))
                    .distinctBy { it.udn }
                    .sortedBy { it.room.lowercase() }
                speakers.value = merged
                if (current == null && merged.size == 1) select(merged.first())
                // The empty state is already shown in the list; no duplicate message.
                refreshVolume()
            } finally {
                searching.value = false
            }
        }
    }

    /** Adds a speaker found during the search right away. */
    private fun addFound(speaker: Speaker) {
        val merged = (speakers.value + speaker).distinctBy { it.udn }
            .sortedBy { it.room.lowercase() }
        speakers.value = merged
        if (selected.value == null && merged.size == 1) select(speaker)
    }

    fun addManual(ip: String) {
        val clean = ip.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val speaker = Ssdp.probe(clean)
            if (speaker == null) {
                message.value = text(R.string.msg_no_sonos_at, clean)
            } else {
                speakers.value = (speakers.value + speaker).distinctBy { it.udn }
                select(speaker)
            }
        }
    }

    fun refreshVolume() {
        val speaker = selected.value ?: return
        scope.launch {
            volume.value = runCatching { Sonos(speaker).volume() }.getOrNull()
        }
    }

    private var volumeJob: Job? = null

    /**
     * The slider calls this for every frame of a drag. Only the last value is
     * sent, shortly after, so requests cannot overtake each other.
     */
    fun setVolume(v: Int) {
        val speaker = selected.value ?: return
        val value = v.coerceIn(0, 100)
        volume.value = value
        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(150)
            runCatching { Sonos(speaker).setVolume(value) }
                .onFailure { message.value = text(R.string.msg_volume_error, it.message ?: "") }
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun log(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        android.util.Log.i("Sunus", line)
        requestLog.value = (listOf(stamped) + requestLog.value).take(8)
    }
}
