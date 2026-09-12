import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.FileOutputStream;
import java.util.Locale;

/** Minimal root-side AudioRecord probe, executed with app_process via Magisk. */
public final class RootAudioProbe {
    private static final int DEFAULT_RATE = 16000;
    private static final int SLICE_MS = 500;

    private RootAudioProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RootAudioProbe <mic|voice_call|uplink|downlink> <seconds> <raw-output>");
            System.exit(2);
        }

        final String sourceName = args[0].toLowerCase(Locale.US);
        final int source = sourceForName(sourceName);
        final int seconds = Integer.parseInt(args[1]);
        final String outputPath = args[2];
        final int minBytes = AudioRecord.getMinBufferSize(
                DEFAULT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            throw new IllegalStateException("getMinBufferSize=" + minBytes);
        }

        final int sliceSamples = DEFAULT_RATE * SLICE_MS / 1000;
        final int bufferBytes = Math.max(minBytes, sliceSamples * 2);
        final AudioRecord recorder = new AudioRecord(
                source,
                DEFAULT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes);
        System.out.println("source=" + sourceName + " uid=" + android.os.Process.myUid()
                + " state=" + recorder.getState() + " minBytes=" + minBytes);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("AudioRecord did not initialize");
        }

        final short[] samples = new short[sliceSamples];
        long totalSamples = 0;
        double totalSquares = 0.0;
        int slice = 0;
        try (FileOutputStream output = new FileOutputStream(outputPath)) {
            recorder.startRecording();
            System.out.println("recordingState=" + recorder.getRecordingState());
            final long targetSamples = (long) DEFAULT_RATE * seconds;
            while (totalSamples < targetSamples) {
                int filled = 0;
                while (filled < samples.length && totalSamples + filled < targetSamples) {
                    final int wanted = (int) Math.min(samples.length - filled,
                            targetSamples - totalSamples - filled);
                    final int read = recorder.read(samples, filled, wanted, AudioRecord.READ_BLOCKING);
                    if (read <= 0) {
                        throw new IllegalStateException("read=" + read + " after " + totalSamples + " samples");
                    }
                    filled += read;
                }

                double squares = 0.0;
                int nonZero = 0;
                for (int i = 0; i < filled; i++) {
                    final int value = samples[i];
                    squares += (double) value * value;
                    if (value != 0) nonZero++;
                    output.write(value & 0xff);
                    output.write((value >>> 8) & 0xff);
                }
                totalSamples += filled;
                totalSquares += squares;
                final double rms = filled == 0 ? 0.0 : Math.sqrt(squares / filled);
                final double dbfs = rms == 0.0 ? -120.0 : 20.0 * Math.log10(rms / 32767.0);
                System.out.printf(Locale.US, "slice=%02d ms=%d dbfs=%.2f nonzero=%.1f%%%n",
                        slice++, totalSamples * 1000 / DEFAULT_RATE, dbfs,
                        filled == 0 ? 0.0 : nonZero * 100.0 / filled);
            }
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
            recorder.release();
        }

        final double totalRms = totalSamples == 0 ? 0.0 : Math.sqrt(totalSquares / totalSamples);
        final double totalDbfs = totalRms == 0.0 ? -120.0 : 20.0 * Math.log10(totalRms / 32767.0);
        System.out.printf(Locale.US, "done samples=%d dbfs=%.2f output=%s%n",
                totalSamples, totalDbfs, outputPath);
    }

    private static int sourceForName(String name) {
        switch (name) {
            case "mic": return MediaRecorder.AudioSource.MIC;
            case "voice_call": return MediaRecorder.AudioSource.VOICE_CALL;
            case "uplink": return MediaRecorder.AudioSource.VOICE_UPLINK;
            case "downlink": return MediaRecorder.AudioSource.VOICE_DOWNLINK;
            default: throw new IllegalArgumentException("unknown source: " + name);
        }
    }
}
