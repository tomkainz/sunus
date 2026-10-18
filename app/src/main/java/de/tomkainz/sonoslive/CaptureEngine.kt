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
 * Reads audio and passes it as PCM to the [LiveStreamServer]. Two paths,
 * the same output (48 kHz, stereo, 16 bit):
 *
 *  ALL_APPS  - a helper with shell rights reads REMOTE_SUBMIX. Captures every
 *              app, and the phone itself stays muted.
 *  STANDARD  - AudioPlaybackCapture inside the app. No setup, but apps
 *              may opt out of capture; the sound keeps playing on the
 *              phone as well.
 */
class CaptureEngine(
    private val context: Context,
    private val server: LiveStreamServer,
    /** Called when capture ends by itself (error, helper gone), not after [stop]. */
    private val onEnded: () -> Unit
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
        val helper = shell
        shell = null
        if (helper != null) {
            // stop() is called on the main thread, where every network access fails
            // silently (NetworkOnMainThreadException), including closing the stream.
            Thread({
                helper.close()
                AdbManager.stopHelper(context)
                // Ends Android's "Wireless debugging connected" notice; the next
                // start reconnects within a fraction of a second.
                AdbManager.disconnect(context)
            }, "helper-stop").start()
        }
        thread?.interrupt()
        thread = null
    }

    // ---- Standard (inside the app) ---------------------------------------

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
                Diagnose.fail(context, Diagnose.Code.S1, extra = "recordingState=${recorder.recordingState}")
                return
            }
            AppState.status.value = context.getString(R.string.status_live_standard)

            val buffer = ByteArray(1024 * LiveStreamServer.CHANNELS * 2)
            while (running) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) server.offer(buffer, n) else if (n < 0) {
                    if (running) Diagnose.fail(context, Diagnose.Code.S2, "AudioRecord.read = $n")
                    break
                }
            }
        } catch (e: Exception) {
            if (running) Diagnose.fail(context, Diagnose.Code.S2, e.message, e.stackTraceToString())
        } finally {
            if (running) onEnded()
        }
    }

    // ---- All apps (shell helper) -----------------------------------------

    private fun runAllApps() {
        var process: AdbManager.ShellProcess? = null
        try {
            AppState.status.value = context.getString(R.string.status_connecting_adb)
            val connected = try {
                AdbManager.connect(context)
            } catch (e: Exception) {
                // Service not found: the pairing is still valid, it is just not available right now.
                Diagnose.fail(context, Diagnose.Code.A1, e.message, AdbManager.status(context))
                return
            }
            if (!connected) {
                Diagnose.fail(context, Diagnose.Code.A1, extra = AdbManager.status(context))
                AppState.needsPairing()
                return
            }
            // Stop was pressed while connecting: do not start the helper at all.
            if (!running) return
            AdbManager.grantSelf(context)
            // Clean up leftovers from an earlier run (for example after an app crash).
            AdbManager.stopHelper(context)
            val startedAt = System.currentTimeMillis()
            val started = AdbManager.startSubmixRecorder(context)
            process = started
            shell = started
            if (!running) return
            val input = started.input

            // Look for the header line: "PCM <rate> <channels>" or "ERR <reason>".
            val header = readHeader(input)
            when {
                header == null -> {
                    Diagnose.fail(context, Diagnose.Code.A2, extra = AdbManager.helperReport(context, startedAt))
                    return
                }
                header.startsWith("ERR ") -> {
                    Diagnose.fail(
                        context, Diagnose.Code.A3, header.removePrefix("ERR ").trim(),
                        AdbManager.helperReport(context, startedAt)
                    )
                    return
                }
                !header.startsWith("PCM ") -> {
                    Diagnose.fail(
                        context, Diagnose.Code.A4, header.take(80),
                        AdbManager.helperReport(context, startedAt)
                    )
                    return
                }
            }
            AppState.status.value = context.getString(R.string.status_live_all_apps)

            val buffer = ByteArray(1024 * LiveStreamServer.CHANNELS * 2)
            while (running) {
                val n = input.read(buffer)
                if (n > 0) server.offer(buffer, n) else if (n < 0) {
                    if (running) {
                        Diagnose.fail(context, Diagnose.Code.A5, "stream ended",
                            AdbManager.helperReport(context, startedAt))
                    }
                    break
                }
            }
        } catch (e: Exception) {
            if (running) Diagnose.fail(context, Diagnose.Code.A5, e.message, e.stackTraceToString())
        } finally {
            // Whatever ended the loop, the helper must go, or REMOTE_SUBMIX stays
            // open and the phone silent. stop() may do the same; that is harmless.
            if (process != null) {
                shell = null
                runCatching { process.close() }
                AdbManager.stopHelper(context)
                AdbManager.disconnect(context)
            }
            if (running) onEnded()
        }
    }

    /** Reads lines (up to LF), skipping any VM output, until the marker. */
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
