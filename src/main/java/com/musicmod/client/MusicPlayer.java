package com.musicmod.client;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.*;

/**
 * Handles client-side audio playback.
 * Supports:
 *   • MP3  – decoded frame-by-frame via JLayer (Bitstream/Decoder/SourceDataLine)
 *             with real-time volume scaling and pause via byte-range resume
 *   • WAV / OGG / AIFF – via javax.sound.sampled (native pause/resume + MASTER_GAIN)
 */
@Environment(EnvType.CLIENT)
public class MusicPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/MusicPlayer");
    private static final MusicPlayer INSTANCE = new MusicPlayer();
    public static MusicPlayer get() { return INSTANCE; }

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "musicmod-player");
                t.setDaemon(true);
                return t;
            });

    private volatile float   volume        = 0.8f;
    private volatile boolean muted         = false;
    private volatile boolean stopped       = false;
    private volatile boolean paused        = false;
    private volatile boolean pendingResume = false;

    // Active playback handles
    private volatile SourceDataLine mp3Line;
    private Clip            wavClip;
    private Future<?>       playFuture;

    // For pause/resume and progress tracking
    private volatile String  currentUrl     = null;
    private volatile long    playStartTime  = 0;   // millis when this segment started
    private volatile long    pausedElapsed  = 0;   // accumulated elapsed ms before this segment
    private volatile long    pausedBytes    = 0;   // bytes read before pause (for MP3 resume)
    private volatile int     serverDuration = -1;  // duration from server metadata (seconds)

    // Callback fired when a track finishes naturally
    private Runnable onFinished;

    private MusicPlayer() {}

    // ─── Public API ──────────────────────────────────────────────────────────────

    public void setOnFinished(Runnable cb) { this.onFinished = cb; }

    public void play(String url) { play(url, -1); }

    public void play(String url, int durationSeconds) {
        stop();
        stopped        = false;
        paused         = false;
        currentUrl     = url;
        pausedElapsed  = 0;
        pausedBytes    = 0;
        playStartTime  = System.currentTimeMillis();
        serverDuration = durationSeconds;
        startPlayback(url, 0);
    }

    /** Stop completely and clear all state. */
    public void stop() {
        stopped       = true;
        paused        = false;
        pendingResume = false;
        stopCurrentHandles();
        currentUrl     = null;
        pausedElapsed  = 0;
        pausedBytes    = 0;
        serverDuration = -1;
    }

    /** Pause playback, saving position. */
    public void pause() {
        if (stopped || paused || currentUrl == null) return;
        paused = true;
        pendingResume = false;
        pausedElapsed += System.currentTimeMillis() - playStartTime;
        if (wavClip != null && wavClip.isOpen()) {
            wavClip.stop(); // position is preserved in Clip
        }
        // Stop the MP3 line immediately so its frame-position counter freezes at the
        // last played frame.  The decode loop will use that frozen position to compute
        // the correct pausedBytes, excluding any PCM that was buffered but not played.
        SourceDataLine line = mp3Line;
        if (line != null) line.stop();
    }

    /** Resume from where we paused. */
    public void resume() {
        if (stopped || !paused || currentUrl == null) return;

        if (wavClip != null && wavClip.isOpen()) {
            paused        = false;
            pendingResume = false;
            playStartTime = System.currentTimeMillis();
            wavClip.start();
        } else {
            // MP3: submit task that runs AFTER the old decode loop exits (single-thread executor).
            // We use pendingResume (not paused) as the "still want to resume" signal so the
            // decode loop can still see paused==true when computing pausedBytes, while the task
            // can distinguish a genuine resume from a re-pause that arrived before it ran.
            pendingResume = true;
            final String url = currentUrl;
            playFuture = executor.submit(() -> {
                // If stop() or pause() was called again before this task ran, bail out.
                if (stopped || !pendingResume) return;
                pendingResume = false;
                paused        = false;
                playStartTime = System.currentTimeMillis();
                try { playMp3FromOffset(url, pausedBytes); }
                catch (Exception e) {
                    if (!stopped) LOGGER.error("Resume error: {}", e.getMessage());
                }
            });
        }
    }

    public boolean isPaused()  { return paused; }
    public boolean isStopped() { return stopped; }
    public boolean isPlaying() { return !stopped && !paused && currentUrl != null; }

    /** Elapsed playback time in seconds (counting pauses). */
    public int getElapsedSeconds() {
        if (stopped || currentUrl == null) return 0;
        long elapsed = pausedElapsed;
        if (!paused) elapsed += System.currentTimeMillis() - playStartTime;
        return (int)(elapsed / 1000L);
    }

    /** Total duration in seconds; -1 if unknown. */
    public int getDurationSeconds() {
        if (wavClip != null && wavClip.isOpen()) {
            long micros = wavClip.getMicrosecondLength();
            if (micros > 0) return (int)(micros / 1_000_000L);
        }
        return serverDuration;
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        applyVolumeToClip();
    }

    public float   getVolume() { return volume; }
    public void    setMuted(boolean m) { this.muted = m; applyVolumeToClip(); }
    public boolean isMuted()   { return muted; }

    // ─── Internal helpers ─────────────────────────────────────────────────────────

    private void stopCurrentHandles() {
        // Signal decode loop to exit via `stopped` flag (already set by caller)
        if (mp3Line != null) {
            try { mp3Line.stop(); mp3Line.close(); } catch (Exception ignored) {}
            mp3Line = null;
        }
        if (wavClip != null) {
            wavClip.stop();
            wavClip.close();
            wavClip = null;
        }
        if (playFuture != null) {
            playFuture.cancel(true);
            playFuture = null;
        }
    }

    private void startPlayback(String url, long skipBytes) {
        playFuture = executor.submit(() -> {
            try {
                if (looksLikeMp3(url)) {
                    playMp3FromOffset(url, skipBytes);
                } else {
                    playWav(url);
                }
            } catch (Exception e) {
                if (!stopped) LOGGER.error("Playback error for {}: {}", url, e.getMessage());
            }
        });
    }

    private static boolean looksLikeMp3(String url) {
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        String path = q >= 0 ? lower.substring(0, q) : lower;
        return path.endsWith(".mp3");
    }

    // ─── MP3 Playback (Bitstream/Decoder/SourceDataLine with volume scaling) ──────

    private void playMp3FromOffset(String url, long skipBytes) {
        URLConnection conn = null;
        SourceDataLine line = null;
        Bitstream bitstream = null;
        long totalPcmBytesWritten = 0;
        try {
            conn = new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");
            if (skipBytes > 0) {
                conn.setRequestProperty("Range", "bytes=" + skipBytes + "-");
            }

            CountingInputStream countingStream =
                    new CountingInputStream(new BufferedInputStream(conn.getInputStream()));
            countingStream.setBaseCount(skipBytes);

            bitstream = new Bitstream(countingStream);
            Decoder decoder = new Decoder();

            while (!stopped && !paused) {
                Header header = bitstream.readFrame();
                if (header == null) break; // natural end of stream

                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                if (line == null) {
                    AudioFormat fmt = new AudioFormat(
                            output.getSampleFrequency(),
                            16,
                            output.getChannelCount(),
                            true,   // signed
                            false   // little-endian
                    );
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(fmt);
                    line.start();
                    mp3Line = line;
                }

                // Scale PCM samples by effective volume
                short[] samples = output.getBuffer();
                int     len     = output.getBufferLength(); // valid interleaved shorts
                byte[]  pcm     = new byte[len * 2];
                float   eff     = muted ? 0f : volume;
                for (int i = 0; i < len; i++) {
                    int scaled = Math.round(samples[i] * eff);
                    scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                    pcm[i * 2]     = (byte)(scaled & 0xFF);
                    pcm[i * 2 + 1] = (byte)((scaled >> 8) & 0xFF);
                }
                int written = line.write(pcm, 0, pcm.length);
                totalPcmBytesWritten += written;
                bitstream.closeFrame();
            }

            // Compute the correct resume position.
            // pausedBytes must reflect bytes *played*, not bytes *read from stream*.
            // The SourceDataLine has an internal PCM buffer; frames written there but
            // not yet emitted are the source of the "skip forward on spam" bug.
            // pause() already called line.stop(), freezing getLongFramePosition().
            if (paused && line != null && totalPcmBytesWritten > 0) {
                long playedPcmBytes = line.getLongFramePosition()
                        * (long) line.getFormat().getFrameSize();
                long flushedPcmBytes = totalPcmBytesWritten - playedPcmBytes;
                if (flushedPcmBytes > 0) {
                    // Convert flushed PCM bytes back to compressed-stream bytes using
                    // the average compression ratio for this segment (exact for CBR,
                    // a close approximation for VBR).
                    long compressedInSegment = countingStream.getCount() - skipBytes;
                    double ratio = (double) compressedInSegment / totalPcmBytesWritten;
                    pausedBytes = countingStream.getCount() - Math.round(flushedPcmBytes * ratio);
                } else {
                    pausedBytes = countingStream.getCount();
                }
            } else {
                pausedBytes = countingStream.getCount();
            }

            // Drain only if we finished naturally (not stopped/paused)
            if (!stopped && !paused && line != null) {
                line.drain();
                if (onFinished != null) onFinished.run();
            }

        } catch (Exception e) {
            if (!stopped && !paused) LOGGER.error("MP3 error: {}", e.getMessage());
        } finally {
            if (line != null) {
                try { line.stop(); line.flush(); line.close(); } catch (Exception ignored) {}
                if (mp3Line == line) mp3Line = null;
            }
            if (bitstream != null) {
                try { bitstream.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ─── WAV / OGG Playback ──────────────────────────────────────────────────────

    private void playWav(String url) {
        try {
            URLConnection conn = new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");

            try (AudioInputStream raw = AudioSystem.getAudioInputStream(
                        new BufferedInputStream(conn.getInputStream()))) {

                AudioFormat base = raw.getFormat();
                AudioFormat pcm  = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        base.getSampleRate(), 16,
                        base.getChannels(),
                        base.getChannels() * 2,
                        base.getSampleRate(), false);

                try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, raw)) {
                    DataLine.Info info = new DataLine.Info(Clip.class, pcm);
                    if (!AudioSystem.isLineSupported(info)) {
                        LOGGER.error("Audio line not supported for {}", url);
                        return;
                    }
                    wavClip = (Clip) AudioSystem.getLine(info);
                    wavClip.open(converted);
                    applyVolumeToClip();
                    wavClip.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP && !stopped && !paused) {
                            if (wavClip != null && !wavClip.isRunning()) {
                                if (onFinished != null) onFinished.run();
                            }
                        }
                    });
                    wavClip.start();
                    while (!stopped && wavClip != null && (wavClip.isRunning() || paused)) {
                        Thread.sleep(100);
                    }
                }
            }
        } catch (Exception e) {
            if (!stopped) LOGGER.error("WAV error: {}", e.getMessage());
        }
    }

    private void applyVolumeToClip() {
        if (wavClip == null || !wavClip.isOpen()) return;
        if (!wavClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl gain = (FloatControl) wavClip.getControl(FloatControl.Type.MASTER_GAIN);
        float effectiveVolume = muted ? 0f : volume;
        float dB = effectiveVolume > 0
                ? (float)(20.0 * Math.log10(effectiveVolume))
                : gain.getMinimum();
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    // ─── Counting InputStream ────────────────────────────────────────────────────

    private static class CountingInputStream extends FilterInputStream {
        private long count = 0;

        CountingInputStream(InputStream in) { super(in); }

        void setBaseCount(long base) { this.count = base; }

        public long getCount() { return count; }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }
}
