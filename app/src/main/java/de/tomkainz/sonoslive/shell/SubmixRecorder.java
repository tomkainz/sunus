package de.tomkainz.sonoslive.shell;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Looper;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Laeuft NICHT in der App, sondern als eigener Prozess mit Shell-Rechten:
 *
 *   CLASSPATH=/data/app/.../base.apk app_process / de.tomkainz.sonoslive.shell.SubmixRecorder
 *
 * gestartet ueber die ADB-Verbindung der App, nur solange der Nutzer streamt.
 * Als Shell darf der Prozess die Quelle REMOTE_SUBMIX oeffnen. Android leitet
 * dann den gesamten Medien-Mix dorthin um, auch den von Apps, die die normale
 * Aufnahme sperren. Das Handy selbst wird dabei stumm, der Ton laeuft nur noch
 * zum Sonos.
 *
 * Protokoll auf stdout: eine Textzeile "PCM <rate> <kanaele>" oder "ERR <grund>",
 * danach rohes PCM, 16 Bit little-endian, verschraenkt. Endet die Verbindung,
 * schlaegt write() fehl und der Prozess beendet sich; die Umleitung des Tons
 * faellt damit automatisch weg.
 */
public final class SubmixRecorder {

    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;

    // Android liefert den Mix in Bloecken zu 1024 Frames; kleiner lesen bringt nichts.
    private static final int READ_BYTES = 1024 * CHANNELS * 2;

    private SubmixRecorder() {
    }

    public static void main(String... args) {
        OutputStream out = new FileOutputStream(FileDescriptor.out);
        AudioRecord recorder = null;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                throw new IllegalStateException("braucht Android 12 oder neuer");
            }
            Looper.prepareMainLooper();
            ShellRuntime.apply();

            recorder = create();
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Aufnahme startet nicht");
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
                // Gegenstelle ist weg - genau der normale Weg zum Beenden
            }
        } finally {
            if (recorder != null) {
                recorder.release();
            }
        }
        System.exit(0);
    }

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
