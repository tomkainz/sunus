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
 * Error codes and error reports.
 *
 * Every streaming error gets a short code (such as SL-A2) that appears in the
 * message. A report is also created with the device, version and all
 * details (in All apps mode, the helper's exit code and log). It can be
 * shared or saved to "Downloads". App crashes also end up in the report as
 * SL-X1 and are shown on the next start.
 */
object Diagnose {

    /** Codes: A = All apps, S = Standard, D = service/network, X = crash. */
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

    private const val REPORT_FILE = "error-report.txt"
    private const val CRASH_FILE = "crash.txt"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private lateinit var dir: File

    fun init(context: Context) {
        dir = context.filesDir
        AppState.report.value = File(dir, REPORT_FILE).takeIf { it.exists() }?.readText()

        // Crash during the last run: report it now, the report is already in place.
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

    /** Errors since the last start; later ones do not overwrite earlier ones. */
    private val session = mutableListOf<Pair<Code, String>>()

    /** New stream start: the next error begins a new report. */
    fun beginSession() {
        synchronized(session) { session.clear() }
    }

    /**
     * Reports an error: a short message with code in the UI, the full
     * report for sharing. [detail] is appended to the message (such as the
     * exception), [extra] goes into the report only.
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

    /** The report is always in English, whatever language the phone uses. */
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
            appendLine("Sunus error report")
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

    /** Opens the share sheet with the latest report. */
    fun share(context: Context) {
        val report = AppState.report.value ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Sunus error report")
            .putExtra(Intent.EXTRA_TEXT, report)
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.report_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Saves the latest report as a text file in "Downloads". */
    fun saveToDownloads(context: Context) {
        val report = AppState.report.value ?: return
        val name = "Sunus-report-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"
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
