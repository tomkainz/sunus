package de.tomkainz.sonoslive

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CaptureMode {
    /** Shell-Helfer ueber die eigene ADB-Verbindung: jede App, Handy stumm. */
    ALL_APPS,

    /** Offizielle AudioPlaybackCapture: ohne Einrichtung, aber Apps duerfen sperren. */
    STANDARD
}

/** Ein gemeinsamer Zustand fuer Oberflaeche und Dienst. */
object AppState {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: SharedPreferences

    val speakers = MutableStateFlow<List<Speaker>>(emptyList())
    val selected = MutableStateFlow<Speaker?>(null)
    val searching = MutableStateFlow(false)

    val mode = MutableStateFlow(CaptureMode.ALL_APPS)
    val muteLocal = MutableStateFlow(true)
    val streamBaseOverride = MutableStateFlow("")

    /** Ist die ADB-Kopplung einmal gelungen? */
    val adbPaired = MutableStateFlow(false)
    val adbBusy = MutableStateFlow(false)
    val adbMessage = MutableStateFlow<String?>(null)

    /** Setzt die Oberflaeche, wenn der Alle-Apps-Modus eine Kopplung braucht. */
    val requestPairing = MutableStateFlow(false)

    fun needsPairing() {
        setAdbPaired(false)
        requestPairing.value = true
    }

    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)

    /** Letzter Fehlerbericht (siehe [Diagnose]), zum Teilen oder Speichern. */
    val report = MutableStateFlow<String?>(null)
    val streamUrl = MutableStateFlow<String?>(null)
    val clients = MutableStateFlow(0)
    val lastClient = MutableStateFlow<String?>(null)
    val audioFlowing = MutableStateFlow(false)
    val level = MutableStateFlow(0f)
    val volume = MutableStateFlow<Int?>(null)
    val requestLog = MutableStateFlow<List<String>>(emptyList())

    /** Fuer Meldungen aus Stellen ohne eigenen Context. */
    private lateinit var app: Context

    fun text(id: Int, vararg args: Any): String = app.getString(id, *args)

    fun init(context: Context) {
        app = context.applicationContext
        prefs = context.getSharedPreferences("sonoslive", Context.MODE_PRIVATE)
        status.value = text(R.string.status_ready)
        mode.value = runCatching { CaptureMode.valueOf(prefs.getString("mode", null)!!) }
            .getOrDefault(CaptureMode.ALL_APPS)
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
                // 1. Schnell per SSDP (Multicast)
                var found = Ssdp.discover(context)

                // 2. Bringt das nichts, das eigene Netz absuchen (Router filtert
                //    manchmal Multicast). Treffer erscheinen sofort in der Liste.
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
                // Leerzustand steht bereits in der Liste; keine doppelte Meldung.
                refreshVolume()
            } finally {
                searching.value = false
            }
        }
    }

    /** Fuegt einen waehrend der Suche gefundenen Speaker sofort hinzu. */
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

    fun setVolume(v: Int) {
        val speaker = selected.value ?: return
        volume.value = v.coerceIn(0, 100)
        scope.launch {
            runCatching { Sonos(speaker).setVolume(v) }
                .onFailure { message.value = text(R.string.msg_volume_error, it.message ?: "") }
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    fun log(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        android.util.Log.i("Sunus", line)
        requestLog.value = (listOf(stamped) + requestLog.value).take(8)
    }
}
