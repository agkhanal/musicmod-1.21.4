package com.musicmod.server;

import com.musicmod.common.MusicConfig;
import com.musicmod.common.Playlist;
import com.musicmod.network.MusicPackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicServerMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("musicmod");

    @Override
    public void onInitialize() {
        LOGGER.info("ServerMusicPlayer initializing (1.21.1)...");

        MusicConfig.get();
        MusicCommands.register();

        // ── Register all payload types ────────────────────────────────────────────
        // S2C
        PayloadTypeRegistry.playS2C().register(MusicPackets.PlaySongPayload.ID,    MusicPackets.PlaySongPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MusicPackets.StopMusicPayload.ID,   MusicPackets.StopMusicPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MusicPackets.NowPlayingPayload.ID,  MusicPackets.NowPlayingPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MusicPackets.SyncStatePayload.ID,   MusicPackets.SyncStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MusicPackets.GuiFeedbackPayload.ID, MusicPackets.GuiFeedbackPayload.CODEC);

        // C2S
        PayloadTypeRegistry.playC2S().register(MusicPackets.AddSongPayload.ID,         MusicPackets.AddSongPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MusicPackets.PlaylistActionPayload.ID,  MusicPackets.PlaylistActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MusicPackets.RequestSyncPayload.ID,     MusicPackets.RequestSyncPayload.CODEC);

        // ── Lifecycle ─────────────────────────────────────────────────────────────
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            MusicSessionController.get().setServer(server);
            LOGGER.info("ServerMusicPlayer ready. yt-dlp: {}", MusicConfig.get().ytDlpPath);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            MusicSessionController.get().syncToPlayer(handler.player));

        // ── C2S: add song ─────────────────────────────────────────────────────────
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.AddSongPayload.ID,
            (payload, context) -> {
                ServerPlayerEntity player = context.player();
                context.server().execute(() -> {
                    String url = payload.url();
                    if (LinkResolver.isPlaylistUrl(url)) {
                        // Import entire playlist/album
                        PlaylistManager.get().importPlaylistAsync(url, player,
                            msg -> context.server().execute(() ->
                                PlaylistManager.sendFeedback(player, msg)));
                    } else {
                        PlaylistManager.get().addSongAsync(
                            url,
                            payload.playlist().isBlank() ? null : payload.playlist(),
                            player,
                            msg -> context.server().execute(() -> {
                                PlaylistManager.sendFeedback(player, msg);
                                PlaylistManager.get().syncToPlayer(player);
                            })
                        );
                    }
                });
            });

        // ── C2S: playlist actions ─────────────────────────────────────────────────
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.PlaylistActionPayload.ID,
            (payload, context) -> {
                ServerPlayerEntity player = context.player();
                context.server().execute(() ->
                    handleGuiAction(player, payload.action(), payload.arg()));
            });

        // ── C2S: request sync ─────────────────────────────────────────────────────
        ServerPlayNetworking.registerGlobalReceiver(MusicPackets.RequestSyncPayload.ID,
            (payload, context) ->
                context.server().execute(() ->
                    PlaylistManager.get().syncToPlayer(context.player())));

        LOGGER.info("Packet handlers registered.");
    }

    private static void handleGuiAction(ServerPlayerEntity player, String action, String arg) {
        PlaylistManager pm       = PlaylistManager.get();
        MusicSessionController c = MusicSessionController.get();

        switch (action) {
            case "play" -> {
                if (c.getCurrentSong() != null) c.playSongAsync(c.getCurrentSong());
            }
            case "skip" -> c.skipSong();
            case "stop" -> c.stopAll();

            case "playlist_play" -> {
                Playlist pl = pm.getPlaylist(arg);
                if (pl == null)         PlaylistManager.sendFeedback(player, "\u26a0 Not found: " + arg);
                else if (pl.size() == 0) PlaylistManager.sendFeedback(player, "\u26a0 Playlist is empty.");
                else { c.playPlaylist(pl); PlaylistManager.sendFeedback(player, "\u25b6 Playing: " + arg); }
            }

            case "playlist_play_song" -> {
                // arg = "playlistName:songIndex"
                String[] parts = arg.split(":", 2);
                if (parts.length == 2) {
                    Playlist pl = pm.getPlaylist(parts[0]);
                    if (pl == null) { PlaylistManager.sendFeedback(player, "\u26a0 Playlist not found."); break; }
                    try {
                        int idx = Integer.parseInt(parts[1]);
                        c.playFromIndex(pl, idx);
                        PlaylistManager.sendFeedback(player, "\u25b6 Playing song " + (idx + 1) + " in " + parts[0]);
                    } catch (NumberFormatException e) {
                        PlaylistManager.sendFeedback(player, "\u26a0 Invalid song index.");
                    }
                }
            }

            case "play_song_url" -> {
                // Play a single song by URL from the library
                Playlist.Song song = pm.resolveSong(arg);
                if (song == null) PlaylistManager.sendFeedback(player, "\u26a0 Song not found.");
                else c.playSongAsync(song);
            }

            case "playlist_move_song" -> {
                // arg = "playlistName:fromIndex:toIndex"
                String[] parts = arg.split(":", 3);
                if (parts.length == 3) {
                    try {
                        int from = Integer.parseInt(parts[1]);
                        int to   = Integer.parseInt(parts[2]);
                        boolean ok = pm.moveSongInPlaylist(parts[0], from, to);
                        PlaylistManager.sendFeedback(player, ok ? "\u2714 Reordered." : "\u26a0 Failed to reorder.");
                        pm.syncToPlayer(player);
                    } catch (NumberFormatException e) {
                        PlaylistManager.sendFeedback(player, "\u26a0 Invalid indices.");
                    }
                }
            }

            case "create" -> {
                boolean ok = pm.createPlaylist(arg);
                PlaylistManager.sendFeedback(player, ok ? "\u2714 Created: " + arg : "\u26a0 Already exists: " + arg);
                pm.syncToPlayer(player);
            }

            case "delete" -> {
                boolean ok = pm.deletePlaylist(arg);
                PlaylistManager.sendFeedback(player, ok ? "\u2714 Deleted: " + arg : "\u26a0 Not found: " + arg);
                pm.syncToPlayer(player);
            }

            case "remove_from_playlist" -> {
                String[] parts = arg.split(":", 2);
                if (parts.length == 2) {
                    boolean ok = pm.removeSongFromPlaylist(parts[0], parts[1]);
                    PlaylistManager.sendFeedback(player, ok ? "\u2714 Removed." : "\u26a0 Not found.");
                    pm.syncToPlayer(player);
                }
            }

            case "remove_from_library" -> {
                boolean ok = pm.removeSong(arg);
                PlaylistManager.sendFeedback(player, ok ? "\u2714 Removed: " + arg : "\u26a0 Not found.");
                pm.syncToPlayer(player);
            }

            case "song_finished" -> {
                // Client reports natural end of song — advance playlist or stop
                if (c.getActivePlaylist() != null) c.skipSong();
                else c.stopAll();
            }

            default -> PlaylistManager.sendFeedback(player, "\u26a0 Unknown action: " + action);
        }
    }
}
