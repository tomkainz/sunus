package de.tomkainz.sonoslive

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fehlercodes und Fehlerberichte.
 *
 * Jeder Fehler beim Streamen bekommt einen kurzen Code (etwa SL-A2), der in der
 * Meldung steht. Dazu entsteht ein Bericht mit Geraet, Version und allen
 * Einzelheiten (beim Alle-Apps-Modus Exit-Code und Log des Helfers). Er laesst
 * sich teilen oder unter "Downloads" speichern. Abstuerze der App landen als
 * SL-X1 ebenfalls im Bericht und werden beim naechsten Start gemeldet.
 */
object Diagnose {

    /** Codes: A = Alle Apps, S = Standard, D = Dienst/Netz, X = Absturz. */
    enum class Code(val text: Int) {
        A1(R.string.code_a1),
        A2(R.string.code_a2),
        A3(R.string.code_a3),
        A4(R.string.code_a4),
        A5(R.string.code_a5),
        S1(R.string.code_s1),
        S2(R.string.code_s2),
        S3(R.string.code_s3),
        D1(R.string.code_d1),
        D2(R.string.code_d2),
        D3(R.string.code_d3),
        X1(R.string.code_x1);

        val label: String get() = "SL-$name"
    }

    private const val REPORT_FILE = "fehlerbericht.txt"
    private const val CRASH_FILE = "absturz.txt"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)
    private lateinit var dir: File

    fun init(context: Context) {
        dir = context.filesDir
        AppState.report.value = File(dir, REPORT_FILE).takeIf { it.exists() }?.readText()

        // Absturz beim letzten Lauf: jetzt melden, Bericht liegt schon bereit.
        val crash = File(dir, CRASH_FILE)
        if (crash.exists()) {
            val text = crash.readText()
            crash.delete()
            File(dir, REPORT_FILE).writeText(text)
            AppState.report.value = text
            AppState.message.value =
                context.getString(R.string.error_format, context.getString(Code.X1.text), Code.X1.label)
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(dir, CRASH_FILE).writeText(
                    build(context, Code.X1, "Thread: ${thread.name}\n\n${e.stackTraceToString()}")
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Fehler seit dem letzten Start; spaetere ueberschreiben fruehere nicht. */
    private val session = mutableListOf<Pair<Code, String>>()

    /** Neuer Start des Streams: der naechste Fehler beginnt einen neuen Bericht. */
    fun beginSession() {
        synchronized(session) { session.clear() }
    }

    /**
     * Meldet einen Fehler: kurze Meldung mit Code in der Oberflaeche, voller
     * Bericht zum Teilen. [detail] ergaenzt die Meldung (etwa die Ausnahme),
     * [extra] kommt nur in den Bericht.
     */
    fun fail(context: Context, code: Code, detail: String? = null, extra: String = "") {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val short = context.getString(code.text) + suffix
        val entry = "[${stamp.format(Date())}] ${code.label}  ${english(context).getString(code.text)}$suffix" +
            (if (extra.isNotBlank()) "\n\n${extra.trim()}" else "")
        val report = synchronized(session) {
            session += code to entry
            build(context, session.map { it.first }, session.joinToString("\n\n--------\n\n") { it.second })
        }
        runCatching { File(dir, REPORT_FILE).writeText(report) }
        AppState.report.value = report
        AppState.message.value = context.getString(R.string.error_format, short, code.label)
    }

    /** Der Bericht ist immer englisch, gleich welche Sprache das Handy hat. */
    private fun english(context: Context): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
    )

    private fun build(context: Context, code: Code, details: String): String =
        build(context, listOf(code), details)

    private fun build(context: Context, codes: List<Code>, details: String): String {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val version = info?.let { "${it.versionName} (${it.longVersionCode})" } ?: "?"
        val speaker = AppState.selected.value?.let { "${it.room}, ${it.ip}, ${it.model}" } ?: "none"
        val en = english(context)
        return buildString {
            appendLine("Sunos – error report")
            appendLine("Code:        " + codes.joinToString(", ") { "${it.label} ${en.getString(it.text)}" })
            appendLine("Time:        ${stamp.format(Date())}")
            appendLine("App:         $version")
            appendLine("Android:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device:      ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Mode:        ${AppState.mode.value}")
            appendLine("ADB paired:  ${AppState.adbPaired.value}")
            appendLine("Speaker:     $speaker")
            appendLine()
            appendLine("---- Details ----")
            appendLine(details.trim())
        }
    }

    /** Oeffnet das Teilen-Menue mit dem letzten Bericht. */
    fun share(context: Context) {
        val report = AppState.report.value ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Sunos error report")
            .putExtra(Intent.EXTRA_TEXT, report)
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.report_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Legt den letzten Bericht als Textdatei unter "Downloads" ab. */
    fun saveToDownloads(context: Context) {
        val report = AppState.report.value ?: return
        val name = "Sunos-report-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".txt"
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { it.write(report.toByteArray()) }
        }.onSuccess {
            AppState.message.value = context.getString(R.string.report_saved, name)
        }.onFailure {
            AppState.message.value = context.getString(R.string.report_save_failed, it.message ?: "")
        }
    }
}
