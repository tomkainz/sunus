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
 * The app's built-in ADB connection to itself.
 *
 * Android does not allow normal apps to use REMOTE_SUBMIX. The ADB shell (uid 2000)
 * may. So the app pairs once with its own phone via "Wireless debugging"
 * and uses that to start the capture helper with
 * shell permissions. Nothing is sent to another device, the connection
 * runs only locally to the phone's own ADB service.
 */
object AdbManager {

    /** Where the helper's error output goes; readable and writable for the shell. */
    private const val ERROR_FILE = "/data/local/tmp/sonoslive-helper.err"

    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    private const val OP_PROJECT_MEDIA = "android:project_media"

    // Not public in Settings.Global, but unchanged since Android 11.
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val ADB_ALLOWED_TIME = "adb_allowed_connection_time"

    /** Port of the ADB service from last time; valid until the service restarts. */
    private const val PREFS = "adb"
    private const val KEY_PORT = "port"

    /** Without a limit the library waits forever for the answer from adbd. */
    private const val ANSWER_TIMEOUT_MS = 6000L

    /**
     * Pairs using the code from the "Pair device with pairing code" dialog.
     * [host] comes from the mDNS search; if it is missing, the phone's own Wi-Fi
     * address is used. The pairing service listens on all interfaces.
     */
    fun pair(context: Context, host: String?, port: Int, code: String): Boolean {
        val manager = AdbKeyManager.getInstance(context)
        val candidates = listOfNotNull(host, hostIp(context)).distinct()
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                if (manager.pair(candidate, port, code)) return true
            } catch (e: java.io.IOException) {
                // Connection error: try the next address
                lastError = e
            }
        }
        lastError?.let { throw it }
        return false
    }

    /**
     * Connects to the local ADB service. First via the port from
     * last time: it stays valid until "Wireless debugging" restarts (reboot,
     * Wi-Fi change), and a stale one is rejected right away. Only then the
     * mDNS search, which can take several seconds. If "Wireless debugging"
     * is off, the app turns it on itself, provided it has already run
     * [grantSelf].
     *
     * false: the phone does not know the app (any more), it has to pair again.
     * Throws [IOException] if no ADB service can be found.
     * Network: do not call on the main thread.
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
                false // stale port
            }
            if (ok) {
                AppState.log("ADB connected in ${SystemClock.elapsedRealtime() - started} ms (known port)")
                return true
            }
        }

        // Freshly turned on, the service needs a few seconds before its mDNS announcement.
        val (host, port) = findPort(context, if (switchedOn) timeoutMs + 7000 else timeoutMs)
            ?: throw IOException(context.getString(R.string.adb_not_found))
        val ok = try {
            manager.connect(host ?: hostIp(context), port)
        } catch (e: AdbPairingRequiredException) {
            false
        }
        if (ok) {
            prefs.edit().putInt(KEY_PORT, port).apply()
            AppState.log("ADB connected in ${SystemClock.elapsedRealtime() - started} ms (port searched)")
        }
        return ok
    }

    /**
     * Looks for the ADB service via mDNS: wireless debugging (TLS) or, as in
     * the emulator, classic TCP. Like the library's autoConnect, but returns
     * the port so it can be kept for next time.
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
     * What the app needs from the shell only once, and never again after that:
     *  - WRITE_SECURE_SETTINGS, to turn on "Wireless debugging" itself
     *    (this is also how Shizuku starts without root since 13.6),
     *  - the PROJECT_MEDIA permission: standard mode then starts without
     *    a consent dialog and is not stopped when the screen locks,
     *  - adb_allowed_connection_time 0: the pairing does not expire when
     *    nothing was streamed for a week.
     * If nothing is missing, nothing happens.
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
        AppState.log("Permissions set up: ${commands.size} step(s)")
    }

    /** Short status for error reports. */
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
     * Turns on "Wireless debugging". Returns true only if it was off
     * before. If the Wi-Fi network is not yet saved as trusted, Android
     * asks once ("Always allow on this network").
     */
    private fun enableWirelessDebugging(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        val resolver = context.contentResolver
        return runCatching {
            if (Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1) {
                false
            } else {
                // USB debugging (adb_enabled) stays as it is; only the wireless one is needed.
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
     * Runs a shell command and returns its running data stream.
     * Closing the stream ends the command.
     */
    fun openShell(context: Context, command: String): ShellProcess {
        val manager = AdbKeyManager.getInstance(context)
        val stream: AdbStream = manager.openStream("shell:$command")
        return ShellProcess(stream)
    }

    /**
     * Starts [SubmixRecorder] with shell permissions. stderr and then the
     * exit code go to a file, so a failed start can be traced via
     * [helperReport]; stdout stays pure PCM. It is stopped via
     * [stopHelper]: closing the connection alone is not enough.
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
     * Stops running helpers. The shell runs in a pseudo-terminal, and
     * adbd does not end it when the stream is closed (in the emulator it kept
     * running for over a minute after the stop). A leftover helper
     * keeps REMOTE_SUBMIX open, and the phone would stay silent. The pattern
     * "SubmixRecorde[r]" does not match the command line of this shell itself.
     * pgrep instead of a loop over /proc with grep: that took 12 s in the
     * emulator, pgrep 0.15 s. Not "pkill -9 -f": there it also killed the
     * calling shell.
     * Network: do not call on the main thread.
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
     * Everything about a failed helper start: its error output with exit code
     * and the log lines since [sinceMillis]. Start errors from app_process
     * (missing class, VM abort) only show up in the log, not on stderr.
     */
    fun helperReport(context: Context, sinceMillis: Long): String = runCatching {
        // Wait briefly: the exit code is only written after the helper has ended.
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
        // Self-pairing: the ADB service listens on all interfaces of the
        // device, so the phone's own Wi-Fi address is reachable.
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
