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
 * Protocol on stdout: one text line "PCM <rate> <channels>" or "ERR <reason>",
 * then raw PCM, 16 bit little-endian, interleaved. When the connection ends,
 * write() fails and the process exits; the audio redirect
 * then goes away automatically.
 */
public final class SubmixRecorder {

    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;

    // Android delivers the mix in blocks of 1024 frames; reading smaller gains nothing.
    private static final int READ_BYTES = 1024 * CHANNELS * 2;

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

            recorder = create();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("recording does not start");
            }

            out.write(("PCM " + SAMPLE_RATE + " " + CHANNELS + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            byte[] buffer = new byte[READ_BYTES];
            while (true) {
                int n = recorder.read(buffer, 0, buffer.length);
                if (n < 0) {
                    throw new IOException("AudioRecord.read = " + n);
                }
                if (n > 0) {
                    out.write(buffer, 0, n);
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

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint({"MissingPermission", "WrongConstant"})
    private static AudioRecord create() {
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
            builder.setBufferSizeInBytes(8 * min);
        }
        return builder.build();
    }
}
