package de.tomkainz.sonoslive

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import de.tomkainz.sonoslive.adb.AdbManager
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Error codes, error reports and the log.
 *
 * Every streaming error gets a short code (such as SL-A2) that appears in the
 * message. A report is also created with the device, version and all
 * details (in All apps mode, the helper's exit code and log). It can be
 * shared or saved to "Downloads". App crashes also end up in the report as
 * SL-X1 and are shown on the next start. The log covers everything else and
 * can be handed over at any time, with or without an error.
 */
object Diagnose {

    /** Codes: A = All apps, S = Standard, D = service/network, X = crash. */
    enum class Code(val text: Int, val help: Int) {
        A1(R.string.code_a1, R.string.help_a1),
        A2(R.string.code_a2, R.string.help_helper),
        A3(R.string.code_a3, R.string.help_helper),
        A4(R.string.code_a4, R.string.help_helper),
        A5(R.string.code_a5, R.string.help_a5),
        S1(R.string.code_s1, R.string.help_s1),
        S2(R.string.code_s2, R.string.help_s2),
        S3(R.string.code_s3, R.string.help_s3),
        D1(R.string.code_d1, R.string.help_d1),
        D2(R.string.code_d2, R.string.help_d2),
        D3(R.string.code_d3, R.string.help_d3),
        D4(R.string.code_d4, R.string.help_d4),
        D5(R.string.code_d5, R.string.help_d5),
        D6(R.string.code_d6, R.string.help_d6),
        X1(R.string.code_x1, R.string.help_x1);

        val label: String get() = "SL-$name"

        companion object {
            private val PATTERN = Regex("SL-([A-Z][0-9])")

            /** The code named in a message, if there is one. */
            fun fromText(text: String): Code? =
                PATTERN.find(text)?.let { match -> entries.firstOrNull { it.name == match.groupValues[1] } }

            /**
             * Why pointing the speaker at the stream failed. A refused connection
             * means the phone never reached the speaker, so what the speaker
             * did with the stream says nothing. [streamRequests] 0 otherwise
             * means the speaker answered but never came for the stream, which
             * only a speaker that fetches it ([pulls]) would do.
             */
            fun forSpeakerFailure(e: Exception, streamRequests: Int, pulls: Boolean = true): Code = when {
                e is java.net.ConnectException || e is java.net.NoRouteToHostException -> D5
                e is SpeakerRefused -> D6
                pulls && streamRequests == 0 -> D4
                else -> D3
            }
        }
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
            AppLog.append("Crash in the last run:\n$text")
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
        AppLog.append(entry)
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
        val en = english(context)
        return buildString {
            appendLine("Sunus error report")
            appendLine("Code:        " + codes.joinToString(", ") { "${it.label} ${en.getString(it.text)}" })
            append(deviceInfo(context))
            appendLine()
            appendLine("---- Details ----")
            appendLine(details.trim())
            val log = AppState.requestLog.value
            if (log.isNotEmpty()) {
                appendLine()
                appendLine("---- Recent log (oldest first) ----")
                log.asReversed().forEach { appendLine(it) }
            }
        }
    }

    private fun deviceInfo(context: Context): String = buildString {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val version = info?.let { "${it.versionName} (${it.longVersionCode})" } ?: "?"
        val speaker = AppState.selected.value?.let { "${it.room}, ${it.ip}, ${it.model}, ${it.protocol}" } ?: "none"
        appendLine("Time:        ${stamp.format(Date())}")
        appendLine("App:         $version")
        appendLine("Android:     ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device:      ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Mode:        ${AppState.mode.value}")
        appendLine("ADB paired:  ${AppState.adbPaired.value}")
        appendLine("Speaker:     $speaker")
        appendLine("Phone IP:    ${Net.localIp(context) ?: "none"}")
        if (Build.VERSION.SDK_INT >= 37) {
            val local = context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            appendLine("Local network permission: $local")
        }
    }

    // ---- Log, at any time --------------------------------------------------

    /** Everything for a bug report: what is happening now and what happened before. */
    private fun fullLog(context: Context): String = buildString {
        appendLine("Sunus log")
        append(deviceInfo(context))
        appendLine()
        appendLine("---- Right now ----")
        append(stateInfo(context))
        AppState.report.value?.let {
            appendLine()
            appendLine("---- Last error report ----")
            appendLine(it.trim())
        }
        appendLine()
        appendLine("---- App log (oldest first, also from earlier runs) ----")
        appendLine(AppLog.read().trim())
        if (AdbManager.isConnected(context)) {
            appendLine()
            appendLine("---- Capture helper and audio system, last 10 minutes ----")
            val since = System.currentTimeMillis() - 10 * 60 * 1000L
            appendLine(withLimit(8000) { AdbManager.helperReport(context, since) } ?: "(no answer)")
        }
        appendLine()
        appendLine("---- Android log of this app ----")
        appendLine(ownLogcat())
    }

    private fun stateInfo(context: Context): String = buildString {
        val audio = context.getSystemService(AudioManager::class.java)
        appendLine("Running:       ${AppState.running.value}")
        appendLine("Status:        ${AppState.status.value}")
        appendLine("Audio flowing: ${AppState.audioFlowing.value}")
        appendLine("Stream:        ${AppState.streamUrl.value ?: "none"}, " +
            "${AppState.clients.value} client(s), last ${AppState.lastClient.value ?: "none"}")
        appendLine("Stream health: ${LiveStreamServer.active?.stats() ?: "not running"}")
        appendLine("Speakers:      ${AppState.speakers.value.joinToString { "${it.room} (${it.ip}, ${it.model}, ${it.protocol})" }}")
        appendLine("Volume:        ${AppState.volume.value}, muted ${AppState.muted.value}")
        appendLine("Media volume:  ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} of " +
            "${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        appendLine("Music active:  ${audio.isMusicActive}")
        appendLine("Now playing:   access ${NowPlaying.enabled.value}, session ${NowPlaying.session()}")
        appendLine("ADB connected: ${AdbManager.isConnected(context)}")
    }

    /** Without READ_LOGS, Android shows an app only its own lines, also from earlier runs. */
    private fun ownLogcat(): String = withLimit(6000) {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "5000")
            .redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
    }?.trim()?.ifEmpty { null } ?: "(no output)"

    /** The log must arrive even if one source hangs. */
    private fun <T : Any> withLimit(millis: Long, block: () -> T): T? {
        val result = AtomicReference<T?>()
        val worker = Thread { result.set(runCatching(block).getOrNull()) }.apply { isDaemon = true; start() }
        worker.join(millis)
        return result.get()
    }

    private fun logName(): String =
        "Sunus-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"

    private val logBusy = AtomicBoolean(false)

    /** Collecting takes a few seconds with an ADB connection, so say that something is happening. */
    private fun buildingLog(app: Context, what: String, work: suspend () -> Unit) {
        if (!logBusy.compareAndSet(false, true)) return
        AppState.log("Log: $what")
        val notice = app.getString(R.string.log_building)
        AppState.message.value = notice
        AppState.scope.launch {
            try {
                work()
            } finally {
                logBusy.set(false)
                AppState.message.compareAndSet(notice, null)
            }
        }
    }

    /** Opens the share sheet with the log as a file. */
    fun shareLog(context: Context) {
        val app = context.applicationContext
        buildingLog(app, "sharing") {
            runCatching {
                val folder = File(app.cacheDir, "logs").apply { mkdirs() }
                folder.listFiles()?.forEach { it.delete() }
                val file = File(folder, logName()).apply { writeText(fullLog(app)) }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.logs", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Sunus log")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(file.name, uri)
                app.startActivity(
                    Intent.createChooser(send, app.getString(R.string.log_share))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }.onFailure {
                AppState.log("Log: sharing failed: $it")
                AppState.message.value = app.getString(R.string.log_failed, it.message ?: "")
            }
        }
    }

    /** Saves the log as a text file in "Downloads". */
    fun saveLog(context: Context) {
        val app = context.applicationContext
        buildingLog(app, "saving") {
            saveText(app, logName(), runCatching { fullLog(app) }.getOrElse { "Log failed: $it" })
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
        saveText(context, name, report)
    }

    private fun saveText(context: Context, name: String, text: String) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        }.onSuccess {
            AppState.message.value = context.getString(R.string.report_saved, name)
        }.onFailure {
            AppState.message.value = context.getString(R.string.report_save_failed, it.message ?: "")
        }
    }
}
