package com.musicmod.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * "Now Playing" HUD overlay.
 *
 * Shows permanently while a song is playing (configurable position).
 * Includes a progress bar and volume indicator.
 * Fades out after the song stops (brief 2s fade).
 */
@Environment(EnvType.CLIENT)
public class MusicHudRenderer {

    private static final int FADE_TICKS = 40; // 2 seconds fade after stop

    private String songName     = "";
    private String playlistName = "";
    private boolean songPlaying = false; // true while server says a song is active
    private int     fadeTicks   = 0;     // counts down after stop

    private static final MusicHudRenderer INSTANCE = new MusicHudRenderer();
    public static MusicHudRenderer get() { return INSTANCE; }

    private MusicHudRenderer() {}

    public void showNowPlaying(String song, String playlist) {
        this.songName     = song;
        this.playlistName = playlist;
        this.songPlaying  = true;
        this.fadeTicks    = 0;
    }

    public void clear() {
        songPlaying = false;
        fadeTicks   = FADE_TICKS; // start fade-out
    }

    public void tick() {
        if (!songPlaying && fadeTicks > 0) fadeTicks--;
    }

    public void render(DrawContext ctx) {
        ClientMusicConfig cfg = ClientMusicConfig.get();
        if (!cfg.hudEnabled) return;
        if (songName.isEmpty()) return;
        if (!songPlaying && fadeTicks <= 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;

        float alpha = (!songPlaying && fadeTicks < FADE_TICKS)
                ? (float) fadeTicks / FADE_TICKS
                : 1.0f;
        int a = (int)(alpha * 210);
        if (a <= 0) return;

        MusicPlayer player = MusicPlayer.get();
        int duration = player.getDurationSeconds();
        int elapsed  = duration > 0
                ? Math.min(player.getElapsedSeconds(), duration)
                : player.getElapsedSeconds();
        boolean hasDuration = duration > 0;

        // Clamp HUD width to 220px max so long song names don't overflow screen
        final int MAX_TEXT_W = 220;
        String rawLine1 = "\u266a " + songName;
        String line1 = truncate(mc, rawLine1, MAX_TEXT_W);
        String line2 = playlistName.isEmpty() ? "" : "Playlist: " + playlistName;
        if (!line2.isEmpty()) line2 = truncate(mc, line2, MAX_TEXT_W);
        String line3 = formatProgress(elapsed, duration, player.isPaused());

        int textW = mc.textRenderer.getWidth(line1);
        if (!line2.isEmpty()) textW = Math.max(textW, mc.textRenderer.getWidth(line2));
        textW = Math.max(textW, mc.textRenderer.getWidth(line3));

        int barW = textW + 8;
        int lines = 1 + (line2.isEmpty() ? 0 : 1) + 1; // always show progress line
        int boxH  = 4 + lines * 10 + (hasDuration ? 6 : 0); // extra space for bar

        int screenW = ctx.getScaledWindowWidth();
        int screenH = ctx.getScaledWindowHeight();

        // Compute position from anchor
        int x, y;
        switch (cfg.hudAnchor) {
            case "TOP_LEFT" -> {
                x = cfg.hudOffsetX;
                y = cfg.hudOffsetY;
            }
            case "TOP_RIGHT" -> {
                x = screenW - barW - cfg.hudOffsetX;
                y = cfg.hudOffsetY;
            }
            case "BOTTOM_RIGHT" -> {
                x = screenW - barW - cfg.hudOffsetX;
                y = screenH - boxH - cfg.hudOffsetY;
            }
            default -> { // BOTTOM_LEFT
                x = cfg.hudOffsetX;
                y = screenH - boxH - cfg.hudOffsetY;
            }
        }

        // Background
        ctx.fill(x - 4, y - 2, x + barW, y + boxH, (a << 24));

        int ty = y;
        ctx.drawTextWithShadow(mc.textRenderer, line1, x, ty, (a << 24) | 0xFFFFFF);
        ty += 10;
        if (!line2.isEmpty()) {
            ctx.drawTextWithShadow(mc.textRenderer, line2, x, ty, (a << 24) | 0xAAAAAA);
            ty += 10;
        }
        ctx.drawTextWithShadow(mc.textRenderer, line3, x, ty, (a << 24) | (player.isPaused() ? 0xFFAA00 : 0x55FF55));
        ty += 10;

        // Progress bar
        if (hasDuration) {
            float pct = Math.min(1f, (float) elapsed / duration);
            int filledW = (int)(barW * pct);
            ctx.fill(x - 2, ty, x + barW - 2, ty + 3, (a << 24) | 0x333333);
            ctx.fill(x - 2, ty, x - 2 + filledW, ty + 3, (a << 24) | 0x55FF55);
        }
    }

    private static String truncate(MinecraftClient mc, String text, int maxPx) {
        if (mc.textRenderer.getWidth(text) <= maxPx) return text;
        String ellipsis = "...";
        while (!text.isEmpty() && mc.textRenderer.getWidth(text + ellipsis) > maxPx)
            text = text.substring(0, text.length() - 1);
        return text + ellipsis;
    }

    private static String formatProgress(int elapsed, int duration, boolean paused) {
        String elapsedStr = formatTime(elapsed);
        String prefix = paused ? "\u23f8 " : "\u25b6 ";
        if (duration > 0) {
            return prefix + elapsedStr + " / " + formatTime(duration);
        }
        return prefix + elapsedStr;
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + String.format("%02d", s);
    }
}
