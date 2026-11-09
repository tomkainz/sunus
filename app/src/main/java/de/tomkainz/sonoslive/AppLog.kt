package de.tomkainz.sonoslive

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The app's log on disk, so it can be handed over at any time, also after a restart
 * or a crash. Two files of about [maxBytes] each: the oldest lines go first.
 */
object AppLog {

    private const val FILE = "sunus.log"
    private const val OLD_FILE = "sunus.log.1"

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "sunus-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Volatile private var dir: File? = null
    @Volatile private var maxBytes = 512 * 1024L

    fun init(dir: File, maxBytes: Long = this.maxBytes) {
        this.dir = dir
        this.maxBytes = maxBytes
    }

    /** Never blocks the caller; logging happens on the main thread too. */
    fun append(line: String) {
        val time = System.currentTimeMillis()
        writer.execute { write(time, line) }
    }

    private fun write(time: Long, line: String) {
        val dir = dir ?: return
        runCatching {
            val file = File(dir, FILE)
            if (file.length() > maxBytes) file.renameTo(File(dir, OLD_FILE))
            file.appendText("${stamp.format(Date(time))}  $line\n")
        }
    }

    /** All of it, oldest first, including lines still waiting to be written. */
    fun read(): String = writer.submit(Callable {
        val dir = dir ?: return@Callable ""
        listOf(OLD_FILE, FILE).map { File(dir, it) }.filter { it.exists() }.joinToString("") { it.readText() }
    }).get()
}
