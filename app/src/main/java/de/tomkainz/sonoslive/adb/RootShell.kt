package de.tomkainz.sonoslive.adb

import android.content.Context
import de.tomkainz.sonoslive.AppState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The shell (uid 2000) through su, on rooted phones. It gives the same rights as the
 * ADB shell, so the helper and every command work unchanged, but without pairing and
 * without "Wireless debugging". Uid 2000 rather than root: the helper poses as
 * com.android.shell, and Android checks that the package belongs to the caller's uid.
 */
object RootShell {

    private const val PREFS = "adb"
    private const val KEY_ROOT = "root"
    private const val SHELL_UID = "2000"

    /** Long enough for the user to answer the root manager's prompt. */
    private const val PROMPT_MS = 20_000L

    @Volatile
    private var works: Boolean? = null

    /** Does su exist at all? Cheap, never prompts. */
    fun present(): Boolean =
        System.getenv("PATH").orEmpty().split(':').any { File(it, "su").exists() } ||
            listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su").any { File(it).exists() }

    /** Known to work, from this run or the last one. Does not run su. */
    fun known(context: Context): Boolean =
        works ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ROOT, false)

    /**
     * Asks su for the shell user. The first time, the root manager may ask the user, so
     * this can take seconds. Not on the main thread.
     */
    @Synchronized
    fun check(context: Context, again: Boolean = false): Boolean {
        if (!again) works?.let { return it }
        val ok = present() && runCatching {
            val p = start("id")
            // Unanswered prompt: ending su also ends the read below.
            val watchdog = Thread {
                try {
                    if (!p.waitFor(PROMPT_MS, TimeUnit.MILLISECONDS)) p.destroy()
                } catch (e: InterruptedException) {
                    // Answered in time.
                }
            }.apply { isDaemon = true; start() }
            val out = p.inputStream.bufferedReader().readText()
            watchdog.interrupt()
            "uid=$SHELL_UID" in out
        }.getOrDefault(false)
        works = ok
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ROOT, ok).apply()
        if (!again || !ok) AppState.log(if (ok) "Root: shell via su works" else "Root: not available (su ${if (present()) "refused" else "missing"})")
        return ok
    }

    /**
     * Starts [command] as the shell user. The command goes in on stdin, which works the
     * same with Magisk, KernelSU and APatch, where the "-c" syntax differs. Its stderr is
     * dropped unless the command sends it somewhere itself, so stdout stays clean.
     */
    fun start(command: String): Process {
        val process = ProcessBuilder("su", SHELL_UID).start()
        process.outputStream.use { it.write("exec 2>/dev/null; $command\n".toByteArray()) }
        // stderr of su itself: drain it, or a chatty su could block.
        Thread { runCatching { process.errorStream.readBytes() } }.apply { isDaemon = true; start() }
        return process
    }
}
