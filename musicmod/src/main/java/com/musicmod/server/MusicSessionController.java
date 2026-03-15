package com.musicmod.server;

import com.musicmod.common.MusicConfig;
import com.musicmod.common.Playlist;
import com.musicmod.network.MusicPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class MusicSessionController {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/Session");
    private static MusicSessionController instance;
    public static MusicSessionController get() {
        if (instance == null) instance = new MusicSessionController();
        return instance;
    }

    private MinecraftServer server;
    private Playlist activePlaylist;
    private Playlist.Song currentSong;
    private volatile boolean playing = false;

    /**
     * Monotonically incrementing counter — incremented every time a new song starts.
     * Clients echo this value back in song_finished; the server ignores finish
     * events that don't match the current value (stale/duplicate from other players).
     */
    private final AtomicInteger songSeq = new AtomicInteger(0);

    public void setServer(MinecraftServer s) {
        this.server = s;
        PlaylistManager.get().setServer(s);
    }

    public void playSongAsync(Playlist.Song song) {
        activePlaylist = null;
        currentSong = song;
        playing = true;
        resolveAndPlay(song);
    }

    public void playPlaylist(Playlist playlist) {
        activePlaylist = playlist;
        playlist.reset();
        currentSong = playlist.current();
        if (currentSong == null) { broadcastChat("\u26a0 Playlist is empty."); return; }
        playing = true;
        resolveAndPlay(currentSong);
    }

    /** Play a specific song by its sourceUrl within the given playlist. */
    public void playFromUrl(Playlist playlist, String sourceUrl) {
        Playlist.Song song = playlist.playByUrl(sourceUrl);
        if (song == null) { broadcastChat("\u26a0 Song not found in playlist."); return; }
        activePlaylist = playlist;
        currentSong = song;
        playing = true;
        resolveAndPlay(song);
    }

    /** Play by index (kept as fallback). */
    public void playFromIndex(Playlist playlist, int index) {
        activePlaylist = playlist;
        currentSong = playlist.playFromIndex(index);
        if (currentSong == null) { broadcastChat("\u26a0 Index out of range."); return; }
        playing = true;
        resolveAndPlay(currentSong);
    }

    /**
     * Advance only if seq matches — prevents multiple clients from all
     * triggering an advance when the same song ends.
     */
    public void skipSongIfSeq(int seq) {
        if (seq != songSeq.get()) return;
        skipSong();
    }

    public void skipSong() {
        if (activePlaylist == null) { stopAll(); return; }
        currentSong = activePlaylist.next();
        if (currentSong == null) { stopAll(); return; }
        resolveAndPlay(currentSong);
    }

    public void stopAll() {
        playing = false;
        activePlaylist = null;
        currentSong = null;
        broadcastStop();
        PlaylistManager.get().syncToAll();
    }

    private void resolveAndPlay(Playlist.Song song) {
        int ttl = MusicConfig.get().urlCacheTtlSeconds;
        if (!song.needsResolution() || song.isResolved(ttl)) {
            broadcastPlay(song.getDisplayName(), song.getPlaybackUrl(), song.getDurationSeconds());
            return;
        }
        broadcastChat("\u23f3 Resolving: " + song.getDisplayName() + "\u2026");
        CompletableFuture.supplyAsync(() -> LinkResolver.get().resolve(song))
            .thenAccept(ok -> {
                if (server == null) return;
                server.execute(() -> {
                    if (!playing || currentSong != song) return;
                    if (ok) {
                        broadcastPlay(song.getDisplayName(), song.getPlaybackUrl(), song.getDurationSeconds());
                    } else {
                        broadcastChat("\u26a0 Could not resolve: " + song.getDisplayName() + " \u2014 skipping.");
                        if (activePlaylist != null) skipSong();
                        else stopAll();
                    }
                });
            });
    }

    private void broadcastPlay(String name, String url, int durationSeconds) {
        if (server == null) return;
        int seq = songSeq.incrementAndGet();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(p, new MusicPackets.PlaySongPayload(name, url, durationSeconds, seq));
            ServerPlayNetworking.send(p, new MusicPackets.NowPlayingPayload(
                    name, activePlaylist != null ? activePlaylist.getName() : ""));
        }
        LOGGER.info("Broadcasting: {} ({}s, seq={})", name, durationSeconds, seq);
    }

    private void broadcastStop() {
        if (server == null) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(p, new MusicPackets.StopMusicPayload());
        }
    }

    private void broadcastChat(String msg) {
        if (server == null) return;
        server.getPlayerManager().broadcast(Text.literal(msg), false);
    }

    public void syncToPlayer(ServerPlayerEntity player) {
        if (!playing || currentSong == null) return;
        int ttl = MusicConfig.get().urlCacheTtlSeconds;
        if (!currentSong.isResolved(ttl)) return;
        int seq = songSeq.get();
        ServerPlayNetworking.send(player, new MusicPackets.PlaySongPayload(
                currentSong.getDisplayName(), currentSong.getPlaybackUrl(),
                currentSong.getDurationSeconds(), seq));
        ServerPlayNetworking.send(player, new MusicPackets.NowPlayingPayload(
                currentSong.getDisplayName(),
                activePlaylist != null ? activePlaylist.getName() : ""));
    }

    public int  getCurrentSongSeq()       { return songSeq.get(); }
    public boolean isPlaying()            { return playing; }
    public Playlist.Song getCurrentSong() { return currentSong; }
    public Playlist getActivePlaylist()   { return activePlaylist; }
}
