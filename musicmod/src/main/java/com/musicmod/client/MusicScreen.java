package com.musicmod.client;

import com.google.gson.*;
import com.musicmod.network.MusicPackets;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.*;

@Environment(EnvType.CLIENT)
public class MusicScreen extends Screen {

    private static final int PANEL_W  = 170;
    private static final int HEADER_H = 24;
    private static final int FOOTER_H = 100; // taller for volume + progress
    private static final int ROW_H    = 14;

    // ── State from server ─────────────────────────────────────────────────────────
    private final List<PlaylistEntry> playlists = new ArrayList<>();
    private final List<SongEntry>     library   = new ArrayList<>();
    private String nowPlayingSong     = "";
    private String nowPlayingPlaylist = "";

    // ── Selection & scroll ────────────────────────────────────────────────────────
    private int selectedPlaylist = -1;
    private int selectedSong     = -1;
    private int playlistScroll   = 0;
    private int songScroll       = 0;

    // ── Settings panel ────────────────────────────────────────────────────────────
    private boolean showSettings = false;

    // ── Widgets ───────────────────────────────────────────────────────────────────
    private TextFieldWidget urlField;
    private TextFieldWidget playlistNameField;

    // ── Feedback ──────────────────────────────────────────────────────────────────
    private String feedback       = "";
    private long   feedbackExpiry = 0;

    // ── Volume drag ───────────────────────────────────────────────────────────────
    private boolean draggingVolume = false;
    private int volumeBarX, volumeBarW;

    public MusicScreen() { super(Text.literal("Music Manager")); }

    // ─── Data records ─────────────────────────────────────────────────────────────

    record SongEntry(String name, String url, boolean resolved, int durationSeconds) {}

    record PlaylistEntry(String name, List<SongEntry> songs, boolean shuffle) {
        int songCount() { return songs.size(); }
    }

    // ─── Data sync ────────────────────────────────────────────────────────────────

    public void applySync(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            library.clear();
            if (root.has("library")) {
                for (JsonElement el : root.getAsJsonArray("library")) {
                    JsonObject o = el.getAsJsonObject();
                    int dur = o.has("duration") ? o.get("duration").getAsInt() : -1;
                    library.add(new SongEntry(
                            o.get("name").getAsString(),
                            o.get("url").getAsString(),
                            o.has("resolved") && o.get("resolved").getAsBoolean(),
                            dur));
                }
            }
            playlists.clear();
            if (root.has("playlists")) {
                for (JsonElement el : root.getAsJsonArray("playlists")) {
                    JsonObject o = el.getAsJsonObject();
                    List<SongEntry> pSongs = new ArrayList<>();
                    if (o.has("songs")) {
                        for (JsonElement se : o.getAsJsonArray("songs")) {
                            JsonObject so = se.getAsJsonObject();
                            int dur = so.has("duration") ? so.get("duration").getAsInt() : -1;
                            pSongs.add(new SongEntry(
                                    so.get("name").getAsString(),
                                    so.has("url") ? so.get("url").getAsString() : "",
                                    so.has("resolved") && so.get("resolved").getAsBoolean(),
                                    dur));
                        }
                    }
                    playlists.add(new PlaylistEntry(
                            o.get("name").getAsString(), pSongs,
                            o.has("shuffle") && o.get("shuffle").getAsBoolean()));
                }
            }
            if (root.has("session")) {
                JsonObject s = root.getAsJsonObject("session");
                nowPlayingSong     = s.has("song")     ? s.get("song").getAsString()     : "";
                nowPlayingPlaylist = s.has("playlist") ? s.get("playlist").getAsString() : "";
            }
            if (selectedPlaylist >= playlists.size()) selectedPlaylist = -1;
        } catch (Exception e) { setFeedback("\u26a0 Sync error: " + e.getMessage()); }
    }

    public void setFeedback(String msg) { feedback = msg; feedbackExpiry = System.currentTimeMillis() + 6000; }
    public void setNowPlaying(String song, String playlist) { nowPlayingSong = song; nowPlayingPlaylist = playlist; }

    // ─── Init ─────────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        int cx = width / 2;
        int footerY = height - FOOTER_H;
        int panelBot = footerY - 4;

        // URL input
        urlField = new TextFieldWidget(textRenderer, cx - 180, footerY + 8, 360, 14, Text.literal("url"));
        urlField.setMaxLength(1024);
        urlField.setPlaceholder(Text.literal("Paste YouTube, Spotify, SoundCloud, or direct audio URL\u2026"));
        addSelectableChild(urlField);
        setInitialFocus(urlField);

        // Playlist name input
        playlistNameField = new TextFieldWidget(textRenderer, cx - 180, footerY + 28, 200, 14, Text.literal("playlist"));
        playlistNameField.setMaxLength(64);
        playlistNameField.setPlaceholder(Text.literal("Playlist name (optional)"));
        addSelectableChild(playlistNameField);
        if (selectedPlaylist >= 0 && selectedPlaylist < playlists.size())
            playlistNameField.setText(playlists.get(selectedPlaylist).name());

        // Add buttons
        addDrawableChild(ButtonWidget.builder(Text.literal("Add to Playlist"), btn -> onAddToPlaylist())
                .dimensions(cx + 26, footerY + 26, 110, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Library Only"), btn -> onAddToLibrary())
                .dimensions(cx + 140, footerY + 26, 90, 14).build());

        // Playback controls row
        int ctrlY = footerY + 46;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u25b6 Play"),  btn -> onPlay())        .dimensions(cx - 180, ctrlY, 50, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u23f8 Pause"), btn -> onPauseResume()) .dimensions(cx - 126, ctrlY, 50, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u23ed Skip"),  btn -> sendAction("skip", "")) .dimensions(cx -  72, ctrlY, 50, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u23f9 Stop"),  btn -> sendAction("stop", "")) .dimensions(cx -  18, ctrlY, 50, 14).build());

        // Settings toggle
        addDrawableChild(ButtonWidget.builder(Text.literal("\u2699 HUD"), btn -> { showSettings = !showSettings; clearAndRebuild(); })
                .dimensions(cx + 36, ctrlY, 50, 14).build());

        // Playlist controls
        addDrawableChild(ButtonWidget.builder(Text.literal("\u25b6 Play"), btn -> onPlayPlaylist())   .dimensions(8,  panelBot - 18, 54, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+ New"),        btn -> onCreatePlaylist()) .dimensions(66, panelBot - 18, 50, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u2715"),        btn -> onDeletePlaylist()) .dimensions(120,panelBot - 18, 18, 14).build());

        // Song controls
        int songCtrlX = PANEL_W + 16;
        addDrawableChild(ButtonWidget.builder(Text.literal("\u25b6"),  btn -> onPlaySong())     .dimensions(songCtrlX,      panelBot - 18, 24, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u2191"),  btn -> onMoveSongUp())   .dimensions(songCtrlX + 28, panelBot - 18, 24, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("\u2193"),  btn -> onMoveSongDown()) .dimensions(songCtrlX + 56, panelBot - 18, 24, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Remove"),  btn -> onRemoveSong())   .dimensions(songCtrlX + 84, panelBot - 18, 56, 14).build());

        // HUD settings panel widgets (only when showSettings)
        if (showSettings) addHudSettingsWidgets(footerY);
    }

    private void clearAndRebuild() {
        // Re-initialize the screen, clearing all widgets and re-adding them
        this.init(this.client, this.width, this.height);
    }

    private void addHudSettingsWidgets(int footerY) {
        ClientMusicConfig cfg = ClientMusicConfig.get();
        int sx = width - 200, sy = HEADER_H + 8;

        addDrawableChild(ButtonWidget.builder(Text.literal("BL"), btn -> { cfg.hudAnchor = "BOTTOM_LEFT";  cfg.save(); })
                .dimensions(sx,      sy,     44, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("BR"), btn -> { cfg.hudAnchor = "BOTTOM_RIGHT"; cfg.save(); })
                .dimensions(sx + 48, sy,     44, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("TL"), btn -> { cfg.hudAnchor = "TOP_LEFT";     cfg.save(); })
                .dimensions(sx + 96, sy,     44, 14).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("TR"), btn -> { cfg.hudAnchor = "TOP_RIGHT";    cfg.save(); })
                .dimensions(sx + 144,sy,     44, 14).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("HUD " + (cfg.hudEnabled ? "ON" : "OFF")),
                btn -> { cfg.hudEnabled = !cfg.hudEnabled; cfg.save(); clearAndRebuild(); })
                .dimensions(sx, sy + 20, 92, 14).build());
    }

    // ─── Render ───────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);

        int panelTop = HEADER_H + 4;
        int panelBot = height - FOOTER_H - 4;
        int rightX   = PANEL_W + 8;
        int rightW   = width - rightX - 8;
        int rowTop   = panelTop + 14;
        int maxVis   = Math.max(1, (panelBot - panelTop - 14) / ROW_H);

        // ── Header ───────────────────────────────────────────────────────────────
        ctx.fill(0, 0, width, HEADER_H, 0xDD000000);
        ctx.drawCenteredTextWithShadow(textRenderer, "\ud83c\udfb5 Music Manager", width / 2, 7, 0xFFFFFF);
        if (!nowPlayingSong.isEmpty()) {
            String np = "\u266a " + nowPlayingSong + (nowPlayingPlaylist.isEmpty() ? "" : "  [" + nowPlayingPlaylist + "]");
            ctx.drawTextWithShadow(textRenderer, np, width - textRenderer.getWidth(np) - 8, 7, 0x55FF55);
        }

        // ── Playlist panel ───────────────────────────────────────────────────────
        ctx.fill(4, panelTop, PANEL_W + 4, panelBot, 0xAA111111);
        drawBorder(ctx, 4, panelTop, PANEL_W, panelBot - panelTop);
        ctx.drawTextWithShadow(textRenderer, "Playlists", 10, panelTop + 3, 0xAAAAAA);

        for (int i = playlistScroll; i < playlists.size() && i < playlistScroll + maxVis; i++) {
            PlaylistEntry pl = playlists.get(i);
            int ry = rowTop + (i - playlistScroll) * ROW_H;
            boolean sel = i == selectedPlaylist;
            boolean hov = mouseX >= 6 && mouseX <= PANEL_W + 2 && mouseY >= ry && mouseY < ry + ROW_H;
            boolean isActivePlaylist = pl.name().equalsIgnoreCase(nowPlayingPlaylist) && !nowPlayingPlaylist.isEmpty();
            int rowBg = sel ? 0xBB2255AA : hov ? 0x44FFFFFF : 0;
            ctx.fill(6, ry, PANEL_W + 2, ry + ROW_H - 1, rowBg);
            int textColor = isActivePlaylist ? 0x55FF55 : (sel ? 0xFFFFFF : 0xCCCCCC);
            ctx.drawTextWithShadow(textRenderer,
                    pl.name() + " (" + pl.songCount() + ")" + (pl.shuffle() ? " \ud83d\udd00" : ""),
                    10, ry + 2, textColor);
        }

        // ── Song panel ───────────────────────────────────────────────────────────
        ctx.fill(rightX, panelTop, rightX + rightW, panelBot, 0xAA111111);
        drawBorder(ctx, rightX, panelTop, rightW, panelBot - panelTop);

        List<SongEntry> songs = getDisplaySongs();
        String hdr = selectedPlaylist >= 0 && selectedPlaylist < playlists.size()
                ? "Songs \u2014 " + playlists.get(selectedPlaylist).name() : "Song Library";
        ctx.drawTextWithShadow(textRenderer, hdr, rightX + 6, panelTop + 3, 0xAAAAAA);

        for (int i = songScroll; i < songs.size() && i < songScroll + maxVis; i++) {
            SongEntry s = songs.get(i);
            int ry = rowTop + (i - songScroll) * ROW_H;
            boolean sel  = i == selectedSong;
            boolean hov  = mouseX >= rightX + 2 && mouseX <= rightX + rightW - 2 && mouseY >= ry && mouseY < ry + ROW_H;
            boolean isNP = s.name().equalsIgnoreCase(nowPlayingSong) && !nowPlayingSong.isEmpty();

            int rowBg = sel ? 0xBB555522 : (isNP ? 0x44004400 : (hov ? 0x44FFFFFF : 0));
            ctx.fill(rightX + 2, ry, rightX + rightW - 2, ry + ROW_H - 1, rowBg);

            // Song name
            String songLabel = (s.resolved() ? "\u266a " : "\u23f3 ") + s.name();
            int nameColor = isNP ? 0x55FF55 : (s.resolved() ? 0xFFFFFF : 0x888888);
            ctx.drawTextWithShadow(textRenderer, songLabel, rightX + 6, ry + 2, nameColor);

            // Duration on right side
            if (s.durationSeconds() > 0) {
                String dur = formatTime(s.durationSeconds());
                ctx.drawTextWithShadow(textRenderer, dur, rightX + rightW - textRenderer.getWidth(dur) - 6, ry + 2, 0x888888);
            }

            // "now playing" indicator
            if (isNP) {
                ctx.drawTextWithShadow(textRenderer, "\u25b6", rightX + rightW - 14, ry + 2, 0x55FF55);
            }
        }

        // ── Footer ───────────────────────────────────────────────────────────────
        int footerY = height - FOOTER_H;
        ctx.fill(0, footerY, width, height, 0xCC000000);
        ctx.drawTextWithShadow(textRenderer, "URL:",      8, footerY + 10, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, "Playlist:", 8, footerY + 30, 0xAAAAAA);

        // Volume slider
        int volY = footerY + 68;
        ctx.drawTextWithShadow(textRenderer, "Volume:", 8, volY, 0xAAAAAA);
        volumeBarX = 56;
        volumeBarW = 120;
        float vol  = MusicPlayer.get().getVolume();
        ctx.fill(volumeBarX, volY + 2, volumeBarX + volumeBarW, volY + 8, 0xFF333333);
        ctx.fill(volumeBarX, volY + 2, volumeBarX + (int)(volumeBarW * vol), volY + 8, 0xFF55AAFF);
        int knobX = volumeBarX + (int)(volumeBarW * vol) - 2;
        ctx.fill(knobX, volY, knobX + 4, volY + 10, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, (int)(vol * 100) + "%", volumeBarX + volumeBarW + 6, volY, 0xFFFFFF);
        if (MusicPlayer.get().isMuted())
            ctx.drawTextWithShadow(textRenderer, "[muted]", volumeBarX + volumeBarW + 38, volY, 0xFF5555);

        // Progress bar for currently playing song
        int progY = footerY + 82;
        MusicPlayer player = MusicPlayer.get();
        if (!nowPlayingSong.isEmpty()) {
            int elapsed  = player.getElapsedSeconds();
            int duration = player.getDurationSeconds();
            String progLabel = (player.isPaused() ? "\u23f8 " : "\u25b6 ")
                    + nowPlayingSong + "  " + formatProgress(elapsed, duration);
            ctx.drawTextWithShadow(textRenderer, progLabel, 8, progY, player.isPaused() ? 0xFFAA00 : 0x55FF55);

            if (duration > 0) {
                int barX = 8, barW2 = width - 16;
                float pct = Math.min(1f, (float) elapsed / duration);
                ctx.fill(barX, progY + 10, barX + barW2, progY + 13, 0xFF222222);
                ctx.fill(barX, progY + 10, barX + (int)(barW2 * pct), progY + 13, 0xFF55FF55);
            }
        }

        // Feedback
        if (!feedback.isEmpty() && System.currentTimeMillis() < feedbackExpiry)
            ctx.drawTextWithShadow(textRenderer, feedback, width / 2 + 20, footerY + 48, 0xFFDD44);

        urlField.render(ctx, mouseX, mouseY, delta);
        playlistNameField.render(ctx, mouseX, mouseY, delta);

        // Settings overlay
        if (showSettings) renderSettingsOverlay(ctx, mouseX, mouseY);

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderSettingsOverlay(DrawContext ctx, int mouseX, int mouseY) {
        int sx = width - 204, sy = HEADER_H + 4;
        int ow = 200, oh = 60;
        ctx.fill(sx, sy, sx + ow, sy + oh, 0xEE111111);
        drawBorder(ctx, sx, sy, ow, oh);
        ctx.drawTextWithShadow(textRenderer, "HUD Position:", sx + 6, sy + 4, 0xAAAAAA);
        ClientMusicConfig cfg = ClientMusicConfig.get();
        ctx.drawTextWithShadow(textRenderer, "Anchor: " + cfg.hudAnchor, sx + 6, sy + 46, 0x888888);
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h) {
        int c = 0xFF444444;
        ctx.fill(x, y, x + w, y + 1, c);
        ctx.fill(x, y + h - 1, x + w, y + h, c);
        ctx.fill(x, y, x + 1, y + h, c);
        ctx.fill(x + w - 1, y, x + w, y + h, c);
    }

    // ─── Input ────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int panelTop = HEADER_H + 4, panelBot = height - FOOTER_H - 4;
        int rowTop = panelTop + 14;
        int rightX = PANEL_W + 8, rightW = width - rightX - 8;
        int footerY = height - FOOTER_H;

        // Volume slider click
        int volY = footerY + 68;
        if (my >= volY && my <= volY + 10 && mx >= volumeBarX && mx <= volumeBarX + volumeBarW) {
            draggingVolume = true;
            updateVolumeDrag((int) mx);
            return true;
        }

        // Mute toggle (click the % label area)
        if (my >= volY && my <= volY + 10 && mx >= volumeBarX + volumeBarW + 6 && mx <= volumeBarX + volumeBarW + 45) {
            MusicPlayer.get().setMuted(!MusicPlayer.get().isMuted());
            return true;
        }

        // Playlist panel click
        if (mx >= 6 && mx <= PANEL_W + 2 && my >= rowTop && my < panelBot) {
            int idx = (int)(my - rowTop) / ROW_H + playlistScroll;
            if (idx >= 0 && idx < playlists.size()) {
                selectedPlaylist = idx; selectedSong = -1; songScroll = 0;
                playlistNameField.setText(playlists.get(idx).name());
                return true;
            }
        }

        // Song panel click
        if (mx >= rightX + 2 && mx <= rightX + rightW - 2 && my >= rowTop && my < panelBot) {
            int idx = (int)(my - rowTop) / ROW_H + songScroll;
            List<SongEntry> songs = getDisplaySongs();
            if (idx >= 0 && idx < songs.size()) {
                selectedSong = idx;
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingVolume) { updateVolumeDrag((int) mx); return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingVolume) {
            draggingVolume = false;
            // Save volume to client config
            ClientMusicConfig cfg = ClientMusicConfig.get();
            cfg.volume = MusicPlayer.get().getVolume();
            cfg.save();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private void updateVolumeDrag(int mx) {
        float v = Math.max(0f, Math.min(1f, (float)(mx - volumeBarX) / volumeBarW));
        MusicPlayer.get().setVolume(v);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (mx < PANEL_W + 8) playlistScroll = Math.max(0, playlistScroll - (int) vAmount);
        else songScroll = Math.max(0, songScroll - (int) vAmount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ─── Actions ─────────────────────────────────────────────────────────────────

    private void onPlay() {
        // If a song is selected in a playlist, play it specifically
        if (selectedPlaylist >= 0 && selectedSong >= 0) {
            onPlaySong();
        } else {
            sendAction("play", "");
        }
    }

    private void onPauseResume() {
        MusicPlayer player = MusicPlayer.get();
        if (player.isPaused()) {
            player.resume();
        } else {
            player.pause();
        }
    }

    private void onPlaySong() {
        List<SongEntry> songs = getDisplaySongs();
        if (selectedSong < 0 || selectedSong >= songs.size()) {
            setFeedback("\u26a0 Select a song first.");
            return;
        }
        if (selectedPlaylist >= 0 && selectedPlaylist < playlists.size()) {
            // Play this specific song in the playlist
            String arg = playlists.get(selectedPlaylist).name() + ":" + selectedSong;
            sendAction("playlist_play_song", arg);
        } else {
            // Play from library
            SongEntry s = songs.get(selectedSong);
            sendAction("play_song_url", s.url());
        }
    }

    private void onMoveSongUp() {
        if (selectedPlaylist < 0 || selectedSong <= 0) return;
        List<SongEntry> songs = getDisplaySongs();
        if (selectedSong >= songs.size()) return;
        String arg = playlists.get(selectedPlaylist).name() + ":" + selectedSong + ":" + (selectedSong - 1);
        sendAction("playlist_move_song", arg);
        selectedSong--;
    }

    private void onMoveSongDown() {
        if (selectedPlaylist < 0) return;
        List<SongEntry> songs = getDisplaySongs();
        if (selectedSong < 0 || selectedSong >= songs.size() - 1) return;
        String arg = playlists.get(selectedPlaylist).name() + ":" + selectedSong + ":" + (selectedSong + 1);
        sendAction("playlist_move_song", arg);
        selectedSong++;
    }

    private void onAddToPlaylist() {
        String url = urlField.getText().trim(), pl = playlistNameField.getText().trim();
        if (url.isEmpty()) { setFeedback("\u26a0 Paste a URL first."); return; }
        if (pl.isEmpty())  { setFeedback("\u26a0 Enter a playlist name."); return; }
        ClientPlayNetworking.send(new MusicPackets.AddSongPayload(url, pl));
        urlField.setText(""); setFeedback("\u23f3 Adding to " + pl + "\u2026");
    }

    private void onAddToLibrary() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) { setFeedback("\u26a0 Paste a URL first."); return; }
        ClientPlayNetworking.send(new MusicPackets.AddSongPayload(url, ""));
        urlField.setText(""); setFeedback("\u23f3 Resolving\u2026");
    }

    private void onPlayPlaylist() {
        if (selectedPlaylist < 0 || selectedPlaylist >= playlists.size()) { setFeedback("\u26a0 Select a playlist first."); return; }
        sendAction("playlist_play", playlists.get(selectedPlaylist).name());
    }

    private void onCreatePlaylist() {
        String name = playlistNameField.getText().trim();
        if (name.isEmpty()) { setFeedback("\u26a0 Enter a playlist name."); return; }
        sendAction("create", name); setFeedback("\u23f3 Creating: " + name);
    }

    private void onDeletePlaylist() {
        if (selectedPlaylist < 0 || selectedPlaylist >= playlists.size()) { setFeedback("\u26a0 Select a playlist first."); return; }
        sendAction("delete", playlists.get(selectedPlaylist).name()); selectedPlaylist = -1;
    }

    private void onRemoveSong() {
        List<SongEntry> songs = getDisplaySongs();
        if (selectedSong < 0 || selectedSong >= songs.size()) { setFeedback("\u26a0 Select a song first."); return; }
        String song = songs.get(selectedSong).name();
        if (selectedPlaylist >= 0 && selectedPlaylist < playlists.size())
            sendAction("remove_from_playlist", playlists.get(selectedPlaylist).name() + ":" + song);
        else
            sendAction("remove_from_library", song);
        selectedSong = -1;
    }

    private void sendAction(String action, String arg) {
        ClientPlayNetworking.send(new MusicPackets.PlaylistActionPayload(action, arg));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /** Returns the songs to display: playlist songs when a playlist is selected, else library. */
    private List<SongEntry> getDisplaySongs() {
        if (selectedPlaylist >= 0 && selectedPlaylist < playlists.size())
            return playlists.get(selectedPlaylist).songs();
        return library;
    }

    private static String formatProgress(int elapsed, int duration) {
        if (duration > 0)
            return formatTime(elapsed) + " / " + formatTime(duration);
        return formatTime(elapsed);
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60, s = seconds % 60;
        return m + ":" + String.format("%02d", s);
    }

    @Override public boolean shouldPause()      { return false; }
    @Override public boolean shouldCloseOnEsc() { return true; }
    public boolean isBlurEnabled() { return false; }
}
