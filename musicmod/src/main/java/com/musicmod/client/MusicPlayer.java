package com.musicmod.client;

import javazoom.jl.player.advanced.AdvancedPlayer;
import javazoom.jl.player.advanced.PlaybackEvent;
import javazoom.jl.player.advanced.PlaybackListener;
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
 *   • MP3  – streamed via JLayer (AdvancedPlayer), with pause via byte-range resume
 *   • WAV / OGG / AIFF – via javax.sound.sampled (native pause/resume)
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

    private volatile float volume = 0.8f;
    private volatile boolean muted = false;
    private volatile boolean stopped = false;
    private volatile boolean paused  = false;

    // Active playback handles
    private AdvancedPlayer mp3Player;
    private Clip wavClip;
    private Future<?> playFuture;

    // For pause/resume and progress tracking
    private volatile String  currentUrl     = null;
    private volatile long    playStartTime  = 0;    // millis when this segment started
    private volatile long    pausedElapsed  = 0;    // accumulated elapsed ms before this segment
    private volatile long    pausedBytes    = 0;    // bytes read before pause (for MP3 resume)
    private volatile int     serverDuration = -1;   // duration from server metadata (seconds)

    // Callback fired when a track finishes naturally
    private Runnable onFinished;

    private MusicPlayer() {}

    // ─── Public API ──────────────────────────────────────────────────────────────

    public void setOnFinished(Runnable cb) { this.onFinished = cb; }

    public void play(String url) {
        play(url, -1);
    }

    public void play(String url, int durationSeconds) {
        stop();
        stopped       = false;
        paused        = false;
        currentUrl    = url;
        pausedElapsed = 0;
        pausedBytes   = 0;
        playStartTime = System.currentTimeMillis();
        serverDuration = durationSeconds;
        startPlayback(url, 0);
    }

    /** Stop completely and clear all state. */
    public void stop() {
        stopped = true;
        paused  = false;
        stopCurrentHandles();
        currentUrl    = null;
        pausedElapsed = 0;
        pausedBytes   = 0;
        serverDuration = -1;
    }

    /** Pause playback, saving position. */
    public void pause() {
        if (stopped || paused || currentUrl == null) return;
        paused = true;
        // Save elapsed time up to this pause
        pausedElapsed += System.currentTimeMillis() - playStartTime;
        if (wavClip != null && wavClip.isOpen()) {
            wavClip.stop(); // position is preserved in Clip
        } else {
            // For MP3: record bytes read and stop
            stopCurrentHandles();
        }
    }

    /** Resume from where we paused. */
    public void resume() {
        if (stopped || !paused || currentUrl == null) return;
        paused = false;
        playStartTime = System.currentTimeMillis();

        if (wavClip != null && wavClip.isOpen()) {
            // WAV clip keeps position — just restart
            wavClip.start();
        } else {
            // MP3: reconnect from byte offset
            final long skipBytes = pausedBytes;
            final String url = currentUrl;
            playFuture = executor.submit(() -> {
                try {
                    playMp3FromOffset(url, skipBytes);
                } catch (Exception e) {
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

    public float getVolume() { return volume; }

    public void setMuted(boolean m) {
        this.muted = m;
        applyVolumeToClip();
    }

    public boolean isMuted() { return muted; }

    // ─── Internal helpers ─────────────────────────────────────────────────────────

    private void stopCurrentHandles() {
        if (mp3Player != null) {
            try { mp3Player.stop(); } catch (Exception ignored) {}
            mp3Player = null;
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
                if (url.toLowerCase().endsWith(".mp3") || isMp3(url)) {
                    playMp3FromOffset(url, skipBytes);
                } else {
                    playWav(url);
                }
            } catch (Exception e) {
                LOGGER.error("Playback error for {}: {}", url, e.getMessage());
            }
        });
    }

    // ─── MP3 Playback ────────────────────────────────────────────────────────────

    private void playMp3FromOffset(String url, long skipBytes) {
        try {
            URLConnection conn = new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");
            if (skipBytes > 0) {
                conn.setRequestProperty("Range", "bytes=" + skipBytes + "-");
            }

            CountingInputStream countingStream =
                    new CountingInputStream(new BufferedInputStream(conn.getInputStream()));
            countingStream.setBaseCount(skipBytes); // start counting from offset

            mp3Player = new AdvancedPlayer(countingStream);
            mp3Player.setPlayBackListener(new PlaybackListener() {
                @Override
                public void playbackFinished(PlaybackEvent evt) {
                    // Save byte position (for potential future seek)
                    pausedBytes = countingStream.getCount();
                    if (!stopped && !paused && onFinished != null) {
                        onFinished.run();
                    }
                }
            });
            mp3Player.play();
            // Record bytes read when this finishes
            pausedBytes = countingStream.getCount();
        } catch (Exception e) {
            if (!stopped && !paused) LOGGER.error("MP3 error: {}", e.getMessage());
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
                AudioFormat pcm = new AudioFormat(
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
                ? (float) (20.0 * Math.log10(effectiveVolume))
                : gain.getMinimum();
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    private static boolean isMp3(String url) {
        try {
            URLConnection c = new URL(url).openConnection();
            c.setConnectTimeout(3000);
            String ct = c.getContentType();
            return ct != null && ct.contains("mpeg");
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Counting InputStream ────────────────────────────────────────────────────

    /** Wraps an InputStream and counts bytes read (with an optional base offset). */
    private static class CountingInputStream extends FilterInputStream {
        private long count = 0;

        CountingInputStream(InputStream in) { super(in); }

        void setBaseCount(long base) { this.count = base; }

        public long getCount() { return count; }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }
}
