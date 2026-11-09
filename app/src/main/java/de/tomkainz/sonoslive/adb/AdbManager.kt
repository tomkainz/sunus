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
import io.github.muntashirakon.adb.AbsAdbConnectionManager
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

    private const val LOOPBACK = "127.0.0.1"
    private const val CLASSIC_PORT = 5555
    private const val MAX_PAIR_CANDIDATES = 8
    private const val PAIR_ATTEMPT_MS = 5000L

    // ADB message commands, little-endian ASCII.
    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_STLS = 0x534c5453

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

        if (switchedOn) AppState.log("ADB: turned on wireless debugging")
        val last = prefs.getInt(KEY_PORT, -1)
        if (!switchedOn && last > 0) {
            val ok = try {
                manager.connect(hostIp(context), last)
            } catch (e: AdbPairingRequiredException) {
                AppState.log("ADB: port $last asks for pairing")
                return false
            } catch (e: Exception) {
                AppState.log("ADB: port $last from last time is stale (${e.javaClass.simpleName})")
                false
            }
            if (ok) {
                AppState.log("ADB connected in ${SystemClock.elapsedRealtime() - started} ms (known port)")
                return true
            }
        }

        // Freshly turned on, the service needs a few seconds before its mDNS announcement.
        val (host, port, how) = findPort(context, if (switchedOn) timeoutMs + 7000 else timeoutMs)
            ?: throw IOException(context.getString(R.string.adb_not_found))
        val ok = try {
            connectAny(manager, listOfNotNull(host, hostIp(context), LOOPBACK).distinct(), port)
        } catch (e: AdbPairingRequiredException) {
            AppState.log("ADB: port $port asks for pairing")
            false
        }
        if (ok) {
            prefs.edit().putInt(KEY_PORT, port).apply()
            AppState.log("ADB connected in ${SystemClock.elapsedRealtime() - started} ms (port $port, $how)")
        }
        return ok
    }

    /** Tries [port] on each address in turn; the last connection error is thrown. */
    private fun connectAny(manager: AbsAdbConnectionManager, hosts: List<String>, port: Int): Boolean {
        var lastError: Exception? = null
        for (host in hosts) {
            try {
                if (manager.connect(host, port)) return true
            } catch (e: AdbPairingRequiredException) {
                throw e
            } catch (e: Exception) {
                AppState.log("ADB: $host:$port failed (${e.javaClass.simpleName}: ${e.message})")
                lastError = e
            }
        }
        lastError?.let { throw it }
        return false
    }

    private data class Found(val host: String?, val port: Int, val how: String)

    /**
     * Looks for the ADB service: via mDNS (wireless debugging over TLS or, as
     * in the emulator, classic TCP) and, at the same time, by scanning the
     * phone's own ports. mDNS alone is not reliable: on a Pixel with Android 17
     * it found nothing while wireless debugging was on. Whichever finds the
     * service first wins.
     */
    private fun findPort(context: Context, timeoutMs: Long): Found? {
        val found = AtomicReference<Found?>()
        val latch = CountDownLatch(1)
        val listener = AdbMdns.OnAdbDaemonDiscoveredListener { address, port ->
            if (port > 0 && found.compareAndSet(null, Found(address?.hostAddress, port, "mDNS"))) {
                latch.countDown()
            }
        }
        val searches = listOf(AdbMdns.SERVICE_TYPE_TLS_CONNECT, AdbMdns.SERVICE_TYPE_ADB)
            .map { AdbMdns(context, it, listener).apply { start() } }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val scanner = Thread({
            // Repeated: right after being turned on, adbd may not listen yet.
            while (found.get() == null && SystemClock.elapsedRealtime() < deadline) {
                // Interrupted once the search is over; must not end up as an app crash.
                val port = runCatching { scanLocalPorts { found.get() != null } }.getOrNull()
                if (port != null) {
                    if (found.compareAndSet(null, Found(null, port, "port scan"))) latch.countDown()
                    break
                }
                runCatching { Thread.sleep(500) }
            }
        }, "adb-port-scan").apply { isDaemon = true; start() }
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            searches.forEach { it.stop() }
            scanner.interrupt()
        }
        if (found.get() == null) AppState.log("ADB: neither mDNS nor the port scan found the service")
        return found.get()
    }

    /**
     * Finds adbd among the open ports of this phone. Wireless debugging listens
     * on a random port from the kernel's ephemeral range, on all interfaces,
     * so on 127.0.0.1 too. Closed ports refuse at once; every open one is
     * asked with an ADB CONNECT, and only adbd answers that with STLS, AUTH
     * or CNXN. 5555 is the classic port (emulator, "adb tcpip").
     */
    private fun scanLocalPorts(cancelled: () -> Boolean): Int? =
        openLocalPorts(cancelled).firstOrNull { !cancelled() && isAdbd(it) }

    /**
     * Pairing without knowing the port, for phones where mDNS finds nothing. The
     * pairing dialog listens on a random port too, but unlike adbd it does not
     * answer an ADB CONNECT. So every other open port is a candidate; the pairing
     * protocol never sends the code itself, a foreign service just fails the attempt.
     */
    fun pairByScan(context: Context, code: String): Boolean {
        val candidates = openLocalPorts { false }
            .filterNot { it == CLASSIC_PORT || isAdbd(it) }
            .take(MAX_PAIR_CANDIDATES)
        AppState.log("ADB pairing: port scan found ${candidates.size} candidate(s)")
        return candidates.any { port ->
            pairWithTimeout(context, port, code).also { ok ->
                if (ok) AppState.log("ADB paired on port $port (port scan)")
            }
        }
    }

    /** A foreign service may simply never answer, and the library would wait forever. */
    private fun pairWithTimeout(context: Context, port: Int, code: String): Boolean {
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempt = Thread({
            runCatching { if (pair(context, LOOPBACK, port, code)) result.set(true) }
        }, "adb-pair-attempt").apply { isDaemon = true; start() }
        attempt.join(PAIR_ATTEMPT_MS)
        attempt.interrupt()
        return result.get()
    }

    private fun openLocalPorts(cancelled: () -> Boolean): List<Int> {
        val range = ephemeralRange()
        val ports = listOf(CLASSIC_PORT) + range.filter { it != CLASSIC_PORT }
        val open = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        try {
            ports.chunked(ports.size / 32 + 1).forEach { chunk ->
                pool.execute {
                    for (port in chunk) {
                        if (cancelled() || Thread.currentThread().isInterrupted) return@execute
                        runCatching {
                            java.net.Socket().use {
                                it.connect(java.net.InetSocketAddress(LOOPBACK, port), 200)
                            }
                            open += port
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        return open.sorted()
    }

    /** The kernel's range for random ports, as adbd gets one; Linux default otherwise. */
    private fun ephemeralRange(): IntRange = runCatching {
        val (low, high) = java.io.File("/proc/sys/net/ipv4/ip_local_port_range").readText()
            .trim().split(Regex("\\s+")).map { it.toInt() }
        low..high
    }.getOrDefault(32768..60999)

    /** Sends an ADB CONNECT message and checks whether the answer is from adbd. */
    private fun isAdbd(port: Int): Boolean = runCatching {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(LOOPBACK, port), 500)
            socket.soTimeout = 1500
            val payload = "host::\u0000".toByteArray(Charsets.US_ASCII)
            val header = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(A_CNXN).putInt(0x01000001).putInt(256 * 1024)
                .putInt(payload.size).putInt(payload.sumOf { it.toInt() and 0xFF })
                .putInt(A_CNXN xor -1)
            socket.getOutputStream().apply { write(header.array()); write(payload); flush() }
            val answer = ByteArray(4)
            java.io.DataInputStream(socket.getInputStream()).readFully(answer)
            val command = java.nio.ByteBuffer.wrap(answer).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            command == A_STLS || command == A_AUTH || command == A_CNXN
        }
    }.getOrDefault(false)

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
        runCatching { runShell(context, commands.joinToString("; ")) }
        AppState.log("Permissions set up: ${commands.size} step(s)")
    }

    /** Gives or takes notification access for "Now playing" through the shell. */
    fun setNotificationAccess(context: Context, allow: Boolean): Boolean {
        val component = android.content.ComponentName(
            context, de.tomkainz.sonoslive.MediaListenerService::class.java
        ).flattenToString()
        val verb = if (allow) "allow_listener" else "disallow_listener"
        // The result is checked below anyway.
        runCatching { runShell(context, "cmd notification $verb $component") }
        // Android applies this a moment after the command returns.
        repeat(15) {
            if (androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName) == allow
            ) return true
            Thread.sleep(200)
        }
        return false
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
     * Held by one "All apps" run, from connecting until its helper is stopped and the
     * connection is closed. A quick Start after Stop used to reuse the old connection,
     * and the old run's cleanup then closed it under the new one ("Stream closed.",
     * SL-A5, phone log of 08.11.2026), or could kill the new helper.
     */
    private val session = java.util.concurrent.Semaphore(1)

    /** Waits until no other run holds the connection. False after [timeoutMs]. */
    fun beginSession(timeoutMs: Long): Boolean =
        try {
            session.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }

    fun endSession() = session.release()

    /** Closes the connection, unless a run is using it or about to. */
    fun disconnectIfIdle(context: Context) {
        if (!session.tryAcquire()) return
        try {
            disconnect(context)
        } finally {
            session.release()
        }
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
        runCatching { runShell(context, command) }
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
            "AudioRecord:* AudioFlinger:E AudioPolicyManager:E r_submix:W *:S 2>&1 | tail -n 120"
        runShell(context, command).trim()
    }.getOrElse { "Report not readable: $it" }

    /**
     * Runs a command to its end and returns its output.
     *
     * An AdbStream cannot be read to its end. If the command ends while output is still unread,
     * the read after that output waits forever. If the reader was already waiting, it gets
     * "Stream closed." instead, and a plain readText() loses the output. So the command prints
     * an end mark and reading stops there. Should the mark never come, the limit closes the
     * stream, which wakes the reader.
     */
    private fun runShell(context: Context, command: String): String {
        // Split by quotes, so that only the echo's output is the mark, never the command line.
        val process = openShell(context, "$command; echo ${END_MARK.take(5)}\"\"${END_MARK.drop(5)}")
        val limit = Thread {
            try {
                Thread.sleep(SHELL_LIMIT_MS)
                AppState.log("ADB: no end of the command after $SHELL_LIMIT_MS ms, closing it")
                process.close()
            } catch (e: InterruptedException) {
                // The command ended in time.
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            return readUntilMark(process.input)
        } finally {
            limit.interrupt()
            process.close()
        }
    }

    /** Everything before the end mark, or all there is if the stream ends without one. */
    internal fun readUntilMark(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        // Big enough for a whole packet at once: once the stream is closed it answers -1
        // even if the packet it was still handing out has more in it.
        val buffer = ByteArray(256 * 1024)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                val text = out.toString("UTF-8")
                if (END_MARK in text) return text.substringBefore(END_MARK)
            }
        } catch (e: java.io.IOException) {
            // The stream closed early, see runShell. What came before it still counts.
        }
        return out.toString("UTF-8")
    }

    internal const val END_MARK = "SUNUS_SHELL_END"
    private const val SHELL_LIMIT_MS = 15_000L

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
