package de.tomkainz.sonoslive.shell;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Does NOT run in the app, but as a separate process with shell permissions:
 *
 *   CLASSPATH=/data/app/.../base.apk app_process / de.tomkainz.sonoslive.shell.SubmixRecorder
 *
 * started via the app's ADB connection, only while the user is streaming.
 * As the shell, the process may open the REMOTE_SUBMIX source. Android then
 * redirects the whole media mix there, including that of apps which block normal
 * capture. The phone itself goes silent, the sound only goes
 * to the Sonos.
 *
 * Protocol on stdout: one text line "PCM <rate> <channels> <buffer frames>" or
 * "ERR <reason>", then raw PCM, 16 bit little-endian, interleaved. When the
 * connection ends, write() fails and the process exits; the audio redirect
 * then goes away automatically.
 *
 * stderr goes to a file the app's log export reads. A read or write that hangs
 * goes there, with how far the reading is behind real time: behind means
 * Android lost audio, a slow write alone means it only came late.
 */
public final class SubmixRecorder {

    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;

    // Android delivers the mix in blocks of 1024 frames; reading smaller gains nothing.
    private static final int READ_BYTES = 1024 * CHANNELS * 2;

    // About two seconds. If the ADB connection stalls, the audio waits here; with the
    // old 8 x minimum (well under a second) Android dropped it, and the speaker hung up.
    private static final int BUFFER_BYTES = SAMPLE_RATE * CHANNELS * 2 * 2;

    // A read normally waits about 21 ms for the next block.
    private static final long SLOW_MS = 200;
    private static final long ALIVE_EVERY_MS = 5 * 60 * 1000;
    private static final int MAX_NOTES = 300;
    private static int notes;

    private SubmixRecorder() {
    }

    public static void main(String... args) {
        OutputStream out = new FileOutputStream(FileDescriptor.out);
        AudioRecord recorder = null;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw new IllegalStateException("needs Android 12 or newer");
            }
            Looper.prepareMainLooper();
            ShellRuntime.apply();

            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            recorder = create();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("recording does not start");
            }

            int bufferFrames = recorder.getBufferSizeInFrames();
            out.write(("PCM " + SAMPLE_RATE + " " + CHANNELS + " " + bufferFrames + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buffer = new byte[READ_BYTES];
            long started = System.nanoTime() / 1_000_000;
            long nextAlive = started + ALIVE_EVERY_MS;
            long bytesRead = 0;
            long followUp = 0;
            while (true) {
                long t0 = System.nanoTime() / 1_000_000;
                int n = recorder.read(buffer, 0, buffer.length);
                long t1 = System.nanoTime() / 1_000_000;
                if (n < 0) {
                    throw new IOException("AudioRecord.read = " + n);
                }
                bytesRead += n;
                if (n > 0) {
                    out.write(buffer, 0, n);
                }
                long t2 = System.nanoTime() / 1_000_000;
                if (t1 - t0 >= SLOW_MS) {
                    note("read waited " + (t1 - t0) + " ms", started, t2, bytesRead);
                    followUp = t2 + 1000;
                }
                if (t2 - t1 >= SLOW_MS) {
                    note("write took " + (t2 - t1) + " ms", started, t2, bytesRead);
                    followUp = t2 + 1000;
                }
                if (followUp > 0 && t2 >= followUp) {
                    // Still behind now: lost. Back near 0: it only came late.
                    note("1 s later", started, t2, bytesRead);
                    followUp = 0;
                }
                if (t2 >= nextAlive) {
                    note("running, buffer " + bufferFrames + " frames", started, t2, bytesRead);
                    nextAlive += ALIVE_EVERY_MS;
                }
            }
        } catch (Throwable t) {
            try {
                String reason = String.valueOf(t).replace('\n', ' ');
                out.write(("ERR " + reason + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // The other side is gone, which is the normal way to end
            }
        } finally {
            if (recorder != null) {
                recorder.release();
            }
        }
        System.exit(0);
    }

    /** One line to stderr: wall clock, what happened, and how far reading is behind real time. */
    private static void note(String what, long startedMs, long nowMs, long bytesRead) {
        if (notes >= MAX_NOTES) {
            return;
        }
        notes++;
        long behind = (nowMs - startedMs) - bytesRead * 1000 / ((long) SAMPLE_RATE * CHANNELS * 2);
        String time = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
                .format(new java.util.Date());
        System.err.println(time + " " + what + ", " + (bytesRead / (CHANNELS * 2)) + " frames read, "
                + behind + " ms behind real time" + (notes == MAX_NOTES ? " (no more notes)" : ""));
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint({"MissingPermission", "WrongConstant"})
    private static AudioRecord create() {
        try {
            return create(BUFFER_BYTES);
        } catch (RuntimeException e) {
            // Should Android refuse the large buffer, the old size still works.
            System.err.println("buffer of " + BUFFER_BYTES + " bytes refused (" + e + "), using 8 x minimum");
            return create(0);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint({"MissingPermission", "WrongConstant"})
    private static AudioRecord create(int bufferBytes) {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setContext(ShellContext.get())
                .setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                .setAudioFormat(format);
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min > 0) {
            builder.setBufferSizeInBytes(Math.max(bufferBytes, 8 * min));
        }
        AudioRecord record = builder.build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("AudioRecord not initialized");
        }
        return record;
    }
}
