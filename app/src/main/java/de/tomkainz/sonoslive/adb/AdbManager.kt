package de.tomkainz.sonoslive.adb

import android.content.Context
import de.tomkainz.sonoslive.shell.SubmixRecorder
import io.github.muntashirakon.adb.AdbStream
import java.io.InputStream

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

    /** Koppeln mit Code und Port aus den Entwickleroptionen (einmalig). */
    fun pair(context: Context, port: Int, code: String): Boolean {
        val manager = AdbKeyManager.getInstance(context)
        return manager.pair(hostIp(context), port, code)
    }

    /** Verbindet sich mit dem lokalen ADB-TLS-Dienst (per mDNS gefunden). */
    fun connect(context: Context, timeoutMs: Long = 8000): Boolean {
        val manager = AdbKeyManager.getInstance(context)
        if (manager.isConnected) return true
        return manager.autoConnect(context, timeoutMs)
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
     * Startet [SubmixRecorder] mit Shell-Rechten. stderr wird verworfen, damit
     * der rohe PCM-Strom auf stdout sauber bleibt; exec haengt den Helfer direkt
     * an die Verbindung, sodass er endet, sobald wir schliessen.
     */
    fun startSubmixRecorder(context: Context): ShellProcess {
        val apk = context.applicationInfo.sourceDir
        val klass = SubmixRecorder::class.java.name
        val command = "CLASSPATH='$apk' exec app_process / $klass 2>/dev/null"
        return openShell(context, command)
    }

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
