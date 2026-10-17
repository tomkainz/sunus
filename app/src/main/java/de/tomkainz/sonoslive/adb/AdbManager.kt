package de.tomkainz.sonoslive.adb

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import de.tomkainz.sonoslive.AppState
import de.tomkainz.sonoslive.R
import de.tomkainz.sonoslive.shell.SubmixRecorder
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Die eingebaute ADB-Verbindung der App zu sich selbst.
 *
 * Android erlaubt normalen Apps kein REMOTE_SUBMIX. Die ADB-Shell (uid 2000)
 * darf es. Deshalb koppelt sich die App einmalig ueber "Kabelloses Debugging"
 * mit dem eigenen Handy und startet darueber den Aufnahme-Helfer mit
 * Shell-Rechten. Es wird nichts an ein anderes Geraet gesendet, die Verbindung
 * laeuft ausschliesslich lokal zum eigenen ADB-Dienst.
 */
object AdbManager {

    /** Ablage fuer die Fehlerausgabe des Helfers; fuer Shell les- und schreibbar. */
    private const val ERROR_FILE = "/data/local/tmp/sonoslive-helper.err"

    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    private const val OP_PROJECT_MEDIA = "android:project_media"

    // Nicht oeffentlich in Settings.Global, aber seit Android 11 unveraendert.
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val ADB_ALLOWED_TIME = "adb_allowed_connection_time"

    /** Port des ADB-Dienstes vom letzten Mal; er gilt, bis der Dienst neu startet. */
    private const val PREFS = "adb"
    private const val KEY_PORT = "port"

    /** Ohne Grenze wartet die Bibliothek unbegrenzt auf die Antwort von adbd. */
    private const val ANSWER_TIMEOUT_MS = 6000L

    /**
     * Koppeln mit dem Code aus dem Dialog "Geraet mit Kopplungscode koppeln".
     * [host] kommt aus der mDNS-Suche; fehlt er, wird die eigene WLAN-Adresse
     * genommen. Der Kopplungsdienst lauscht auf allen Schnittstellen.
     */
    fun pair(context: Context, host: String?, port: Int, code: String): Boolean {
        val manager = AdbKeyManager.getInstance(context)
        val candidates = listOfNotNull(host, hostIp(context)).distinct()
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                if (manager.pair(candidate, port, code)) return true
            } catch (e: java.io.IOException) {
                // Verbindungsfehler: naechste Adresse probieren
                lastError = e
            }
        }
        lastError?.let { throw it }
        return false
    }

    /**
     * Verbindet sich mit dem lokalen ADB-Dienst. Zuerst ueber den Port vom
     * letzten Mal: er gilt, bis "Kabelloses Debugging" neu startet (Neustart,
     * WLAN-Wechsel), und ein veralteter wird sofort abgelehnt. Erst dann die
     * mDNS-Suche, die mehrere Sekunden dauern kann. Ist "Kabelloses Debugging"
     * aus, schaltet die App es selbst ein, sofern sie sich [grantSelf] schon
     * erteilt hat.
     *
     * false: das Handy kennt die App nicht (mehr), sie muss neu koppeln.
     * Wirft [IOException], wenn kein ADB-Dienst zu finden ist.
     * Netzwerk: nicht auf dem Haupt-Thread aufrufen.
     */
    @Synchronized
    fun connect(context: Context, timeoutMs: Long = 8000): Boolean {
        val manager = AdbKeyManager.getInstance(context)
        if (manager.isConnected) return true
        val started = SystemClock.elapsedRealtime()
        manager.setTimeout(ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val switchedOn = enableWirelessDebugging(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val last = prefs.getInt(KEY_PORT, -1)
        if (!switchedOn && last > 0) {
            val ok = try {
                manager.connect(hostIp(context), last)
            } catch (e: AdbPairingRequiredException) {
                return false
            } catch (e: Exception) {
                false // Port veraltet
            }
            if (ok) {
                AppState.log("ADB verbunden in ${SystemClock.elapsedRealtime() - started} ms (bekannter Port)")
                return true
            }
        }

        // Frisch eingeschaltet braucht der Dienst ein paar Sekunden bis zur mDNS-Ankuendigung.
        val (host, port) = findPort(context, if (switchedOn) timeoutMs + 7000 else timeoutMs)
            ?: throw IOException(context.getString(R.string.adb_not_found))
        val ok = try {
            manager.connect(host ?: hostIp(context), port)
        } catch (e: AdbPairingRequiredException) {
            false
        }
        if (ok) {
            prefs.edit().putInt(KEY_PORT, port).apply()
            AppState.log("ADB verbunden in ${SystemClock.elapsedRealtime() - started} ms (Port gesucht)")
        }
        return ok
    }

    /**
     * Sucht den ADB-Dienst per mDNS: kabelloses Debugging (TLS) oder, wie im
     * Emulator, klassisch ueber TCP. Wie autoConnect der Bibliothek, liefert
     * aber den Port, damit er fuers naechste Mal bleibt.
     */
    private fun findPort(context: Context, timeoutMs: Long): Pair<String?, Int>? {
        val found = AtomicReference<Pair<String?, Int>?>()
        val latch = CountDownLatch(1)
        val listener = AdbMdns.OnAdbDaemonDiscoveredListener { address, port ->
            if (port > 0 && found.compareAndSet(null, address?.hostAddress to port)) latch.countDown()
        }
        val searches = listOf(AdbMdns.SERVICE_TYPE_TLS_CONNECT, AdbMdns.SERVICE_TYPE_ADB)
            .map { AdbMdns(context, it, listener).apply { start() } }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            searches.forEach { it.stop() }
        }
        return found.get()
    }

    /**
     * Was die App nur einmal ueber die Shell braucht, danach nicht mehr:
     *  - WRITE_SECURE_SETTINGS, um "Kabelloses Debugging" selbst einzuschalten
     *    (so startet auch Shizuku ab 13.6 ohne Root),
     *  - die Freigabe PROJECT_MEDIA: der Standard-Modus startet dann ohne
     *    Zustimmungsdialog und wird beim Sperren des Bildschirms nicht beendet,
     *  - adb_allowed_connection_time 0: die Kopplung verfaellt nicht, wenn
     *    eine Woche lang nicht gestreamt wurde.
     * Fehlt nichts, passiert nichts.
     */
    fun grantSelf(context: Context) {
        val pkg = context.packageName
        val commands = buildList {
            if (!canWriteSecureSettings(context)) {
                add("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
            }
            if (!hasProjectionPermission(context)) add("appops set $pkg PROJECT_MEDIA allow")
            if (Settings.Global.getLong(context.contentResolver, ADB_ALLOWED_TIME, -1L) != 0L) {
                add("settings put global $ADB_ALLOWED_TIME 0")
            }
        }
        if (commands.isEmpty()) return
        runCatching {
            val process = openShell(context, commands.joinToString("; "))
            process.input.bufferedReader().use { it.readText() }
            process.close()
        }
        AppState.log("Rechte eingerichtet: ${commands.size} Schritt(e)")
    }

    /** Kurzer Zustand fuer Fehlerberichte. */
    fun status(context: Context): String {
        val wifi = runCatching {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
        }.getOrDefault(false)
        return "Wireless debugging on: $wifi\n" +
            "May turn it on itself: ${canWriteSecureSettings(context)}\n" +
            "Capture without dialog (PROJECT_MEDIA): ${hasProjectionPermission(context)}\n" +
            "Connected to ADB: ${isConnected(context)}"
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasProjectionPermission(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(OP_PROJECT_MEDIA, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Schaltet "Kabelloses Debugging" ein. Liefert true nur, wenn es vorher aus
     * war. Ist das WLAN noch nicht als vertrauenswuerdig gespeichert, fragt
     * Android einmal nach ("In diesem Netzwerk immer zulassen").
     */
    private fun enableWirelessDebugging(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        val resolver = context.contentResolver
        return runCatching {
            if (Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1) {
                false
            } else {
                // USB-Debugging (adb_enabled) bleibt, wie es ist; noetig ist nur das kabellose.
                Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
            }
        }.getOrDefault(false)
    }

    fun isConnected(context: Context): Boolean =
        runCatching { AdbKeyManager.getInstance(context).isConnected }.getOrDefault(false)

    fun disconnect(context: Context) {
        runCatching { AdbKeyManager.getInstance(context).disconnect() }
    }

    /**
     * Fuehrt einen Shell-Befehl aus und liefert dessen laufenden Datenstrom.
     * Schliessen des Streams beendet den Befehl.
     */
    fun openShell(context: Context, command: String): ShellProcess {
        val manager = AdbKeyManager.getInstance(context)
        val stream: AdbStream = manager.openStream("shell:$command")
        return ShellProcess(stream)
    }

    /**
     * Startet [SubmixRecorder] mit Shell-Rechten. stderr und danach der
     * Exit-Code gehen in eine Datei, damit ein Fehlstart ueber [helperReport]
     * nachvollziehbar ist; stdout bleibt reines PCM. Beendet wird er ueber
     * [stopHelper]: Schliessen der Verbindung allein reicht nicht.
     */
    fun startSubmixRecorder(context: Context): ShellProcess {
        val apk = context.applicationInfo.sourceDir
        val klass = SubmixRecorder::class.java.name
        val command = "rm -f $ERROR_FILE; " +
            "CLASSPATH='$apk' app_process / $klass 2>$ERROR_FILE; " +
            "echo \"Exit code: \$?\" >>$ERROR_FILE"
        return openShell(context, command)
    }

    /**
     * Beendet laufende Helfer. Die Shell laeuft in einem Pseudo-Terminal, und
     * adbd beendet sie beim Schliessen des Streams nicht (im Emulator nach dem
     * Stopp ueber eine Minute weitergelaufen). Ein uebrig gebliebener Helfer
     * haelt REMOTE_SUBMIX offen, das Handy bliebe stumm. Das Muster
     * "SubmixRecorde[r]" trifft die Befehlszeile dieser Shell selbst nicht.
     * pgrep statt einer Schleife ueber /proc mit grep: die brauchte im
     * Emulator 12 s, pgrep 0,15 s. Nicht "pkill -9 -f": das beendete dort die
     * aufrufende Shell gleich mit.
     * Netzwerk: nicht auf dem Haupt-Thread aufrufen.
     */
    fun stopHelper(context: Context) {
        val command = "X='SubmixRecorde[r]'; P=\$(pgrep -f \"\$X\") && kill \$P && sleep 1 && " +
            "P=\$(pgrep -f \"\$X\") && kill -9 \$P; true"
        runCatching {
            val process = openShell(context, command)
            process.input.bufferedReader().use { it.readText() }
            process.close()
        }
    }

    /**
     * Alles zum Fehlstart des Helfers: seine Fehlerausgabe mit Exit-Code und
     * die Log-Zeilen seit [sinceMillis]. Startfehler von app_process (fehlende
     * Klasse, Abbruch der VM) stehen nur im Log, nicht auf stderr.
     */
    fun helperReport(context: Context, sinceMillis: Long): String = runCatching {
        // Kurz warten: der Exit-Code wird erst nach dem Ende des Helfers geschrieben.
        Thread.sleep(500)
        val since = String.format(java.util.Locale.ROOT, "%d.%03d", sinceMillis / 1000, sinceMillis % 1000)
        val command = "echo '== Helper (stderr, exit code) =='; cat $ERROR_FILE 2>/dev/null; " +
            "echo; echo '== Log =='; " +
            "logcat -d -v time -t '$since' AndroidRuntime:* app_process:* DEBUG:* libc:* " +
            "AudioRecord:* AudioFlinger:E AudioPolicyManager:E *:S 2>&1 | tail -n 120"
        val process = openShell(context, command)
        val text = process.input.bufferedReader().use { it.readText() }
        process.close()
        text.trim()
    }.getOrElse { "Report not readable: $it" }

    private fun hostIp(context: Context): String {
        // Self-Pairing: der ADB-Dienst lauscht auf allen Schnittstellen des
        // Geraets, die eigene WLAN-Adresse ist also erreichbar.
        return de.tomkainz.sonoslive.Net.localIp(context) ?: "127.0.0.1"
    }

    class ShellProcess(private val stream: AdbStream) {
        val input: InputStream get() = stream.openInputStream()

        fun close() {
            runCatching { stream.close() }
        }

        val isClosed: Boolean get() = stream.isClosed
    }
}
