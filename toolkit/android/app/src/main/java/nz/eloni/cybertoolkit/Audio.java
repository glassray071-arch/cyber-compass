package nz.eloni.cybertoolkit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** Microphone helper: prefers the raw (unprocessed) source so readings aren't auto-levelled. */
final class Audio {
    private Audio() {}

    @SuppressLint("MissingPermission")
    static AudioRecord open(Context c, int rate, int minBytes) {
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, minBytes);
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        boolean raw = am != null && "true".equals(
                am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = raw
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
                r.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Blocks until the whole buffer is filled (or recording stops). */
    static boolean readFully(AudioRecord r, short[] b) {
        int off = 0;
        while (off < b.length) {
            int n = r.read(b, off, b.length - off);
            if (n <= 0) return false;
            off += n;
        }
        return true;
    }
}
