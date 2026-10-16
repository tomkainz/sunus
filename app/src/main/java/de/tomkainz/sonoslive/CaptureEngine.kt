package de.tomkainz.sonoslive

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import de.tomkainz.sonoslive.adb.AdbManager

/**
 * Liest Audio und reicht es als PCM an den [LiveStreamServer] weiter. Zwei Wege,
 * dieselbe Ausgabe (48 kHz, Stereo, 16 Bit):
 *
 *  ALL_APPS  - ein Helfer mit Shell-Rechten liest REMOTE_SUBMIX. Erfasst jede
 *              App, das Handy selbst ist dabei stumm.
 *  STANDARD  - AudioPlaybackCapture in der App selbst. Ohne Einrichtung, aber
 *              Apps duerfen sich der Aufnahme entziehen; der Ton laeuft parallel
 *              weiter am Handy.
 */
class CaptureEngine(
    private val context: Context,
    private val server: LiveStreamServer
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var shell: AdbManager.ShellProcess? = null

    fun startStandard(projection: MediaProjection) {
        if (running) return
        running = true
        thread = Thread({ runStandard(projection) }, "capture-standard").apply {
            isDaemon = true
            start()
        }
    }

    fun startAllApps() {
        if (running) return
        running = true
        thread = Thread({ runAllApps() }, "capture-allapps").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        shell?.close()
        shell = null
        thread?.interrupt()
        thread = null
    }

    // ---- Standard (in der App) -------------------------------------------

    @SuppressLint("MissingPermission")
    private fun runStandard(projection: MediaProjection) {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(LiveStreamServer.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                LiveStreamServer.RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(8 * minBuffer.coerceAtLeast(4096))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record = recorder

            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                AppState.message.value = "Aufnahme startet nicht."
                return
            }
            AppState.status.value = "Live (Standard)"

            val buffer = ByteArray(1024 * LiveStreamServer.CHANNELS * 2)
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) server.offer(buffer, n) else if (n < 0) break
            }
        } catch (e: Exception) {
            if (running) AppState.message.value = "Aufnahme: ${e.message}"
        }
    }

    // ---- Alle Apps (Shell-Helfer) ----------------------------------------

    private fun runAllApps() {
        try {
            AppState.status.value = "Verbinde ADB..."
            if (!AdbManager.connect(context)) {
                AppState.message.value = "ADB-Verbindung fehlgeschlagen. Ist \"Kabelloses Debugging\" an?"
                AppState.needsPairing()
                return
            }
            val process = AdbManager.startSubmixRecorder(context)
            shell = process
            val input = process.input

            // Kopfzeile suchen: "PCM <rate> <kanaele>" oder "ERR <grund>".
            val header = readHeader(input)
            when {
                header == null -> {
                    AppState.message.value = "Helfer lieferte keine Daten."
                    return
                }
                header.startsWith("ERR ") -> {
                    AppState.message.value = "Helfer: ${header.removePrefix("ERR ").trim()}"
                    return
                }
                !header.startsWith("PCM ") -> {
                    AppState.message.value = "Unerwartete Antwort: ${header.take(80)}"
                    return
                }
            }
            AppState.status.value = "Live (alle Apps)"

            val buffer = ByteArray(1024 * LiveStreamServer.CHANNELS * 2)
            while (running) {
                val n = input.read(buffer)
                if (n > 0) server.offer(buffer, n) else if (n < 0) break
            }
        } catch (e: Exception) {
            if (running) AppState.message.value = "Alle-Apps-Modus: ${e.message}"
        }
    }

    /** Liest Zeilen (bis LF), ueberspringt moegliche VM-Ausgabe, bis zur Markierung. */
    private fun readHeader(input: java.io.InputStream): String? {
        repeat(20) {
            val line = readAsciiLine(input) ?: return null
            if (line.startsWith("PCM ") || line.startsWith("ERR ")) return line
        }
        return null
    }

    private fun readAsciiLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 4096) return sb.toString()
        }
    }
}
