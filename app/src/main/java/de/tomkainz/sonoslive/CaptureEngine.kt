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
            // Only wakes the reading thread. Stopping the helper and closing the
            // connection is left to that thread (finally in runAllApps), under the
            // ADB session, so that a quick Start waits for it instead of losing its
            // connection to it. stop() is called on the main thread, where every
            // network access fails silently, including closing the stream.
            Thread({ helper.close() }, "helper-stop").start()
        }
        thread?.interrupt()
        thread = null
    }

    // ---- Standard (inside the app) ---------------------------------------

    @SuppressLint("MissingPermission")
    private fun runStandard(projection: MediaProjection) {
        raisePriority()
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
            fun build(bytes: Int) = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            // About two seconds, so a reading thread held up for a moment loses nothing.
            // Should Android refuse that, the old size.
            val small = 8 * minBuffer.coerceAtLeast(4096)
            val recorder = runCatching { build(maxOf(2 * LiveStreamServer.BYTES_PER_SEC, small)) }
                .getOrElse { build(small) }
            AppState.log("Standard: capture buffer ${recorder.bufferSizeInFrames * 1000L / LiveStreamServer.RATE} ms")
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
        raisePriority()
        var process: AdbManager.ShellProcess? = null
        AppState.status.value = context.getString(R.string.status_connecting_adb)
        // An earlier run may still be stopping its helper and closing the connection
        // (about a second after Stop). Wait for that, or it closes ours.
        val waitStart = System.currentTimeMillis()
        val owns = AdbManager.beginSession(SESSION_WAIT_MS)
        val waited = System.currentTimeMillis() - waitStart
        if (!owns && running) {
            AppState.log("All apps: earlier run not cleaned up after $waited ms, starting anyway")
        } else if (waited >= 50) {
            AppState.log("All apps: waited $waited ms for the earlier run to clean up")
        }
        try {
            // Stop was pressed while waiting.
            if (!running) return
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
            // Since 1.6-beta12 the helper says how much it can hold while the connection stalls.
            header.split(" ").getOrNull(3)?.toLongOrNull()?.let {
                AppState.log("All apps: helper buffer ${it * 1000 / LiveStreamServer.RATE} ms")
            }

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
            // open and the phone silent. This is the only place that does it.
            // The interrupt from stop() must not break the shell commands below.
            Thread.interrupted()
            if (process != null) {
                shell = null
                runCatching { process.close() }
                AdbManager.stopHelper(context)
                // Ends Android's "Wireless debugging connected" notice; the next
                // start reconnects within a fraction of a second.
                AdbManager.disconnect(context)
            }
            if (owns) AdbManager.endSession()
            if (running) onEnded()
        }
    }

    /** Audio priority, so the reading thread keeps up when the phone is busy. */
    private fun raisePriority() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
    }

    /** Reads lines (up to LF), skipping any VM output, until the marker. */
    private fun readHeader(input: java.io.InputStream): String? {
        repeat(20) {
            val line = readAsciiLine(input) ?: return null
            if (line.startsWith("PCM ") || line.startsWith("ERR ")) return line
        }
        return null
    }

    private companion object {
        /**
         * Cleanup takes about 1.2 s (stopHelper waits 1 s). Its shell command gives up
         * after 15 s, so a longer wait only means that run hangs somewhere else.
         */
        const val SESSION_WAIT_MS = 20_000L
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
