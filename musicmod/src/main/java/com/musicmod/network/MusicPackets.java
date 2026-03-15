package com.musicmod.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * All custom network payloads for 1.21.4.
 */
public class MusicPackets {

    // ── S2C: Play a song ─────────────────────────────────────────────────────────
    // songSeq increments each time a new song starts; clients echo it in song_finished
    // so the server can ignore stale/duplicate finish events from multiple clients.
    public record PlaySongPayload(String name, String url, int durationSeconds, int songSeq) implements CustomPayload {
        public static final Id<PlaySongPayload> ID =
                new Id<>(Identifier.of("musicmod", "play_song"));
        public static final PacketCodec<PacketByteBuf, PlaySongPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING,  PlaySongPayload::name,
                        PacketCodecs.STRING,  PlaySongPayload::url,
                        PacketCodecs.INTEGER, PlaySongPayload::durationSeconds,
                        PacketCodecs.INTEGER, PlaySongPayload::songSeq,
                        PlaySongPayload::new);
        @Override public Id<PlaySongPayload> getId() { return ID; }
    }

    // ── S2C: Stop music ───────────────────────────────────────────────────────────
    public record StopMusicPayload() implements CustomPayload {
        public static final Id<StopMusicPayload> ID =
                new Id<>(Identifier.of("musicmod", "stop_music"));
        public static final PacketCodec<PacketByteBuf, StopMusicPayload> CODEC =
                PacketCodec.unit(new StopMusicPayload());
        @Override public Id<StopMusicPayload> getId() { return ID; }
    }

    // ── S2C: Now playing HUD update ───────────────────────────────────────────────
    public record NowPlayingPayload(String song, String playlist) implements CustomPayload {
        public static final Id<NowPlayingPayload> ID =
                new Id<>(Identifier.of("musicmod", "now_playing"));
        public static final PacketCodec<PacketByteBuf, NowPlayingPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING, NowPlayingPayload::song,
                        PacketCodecs.STRING, NowPlayingPayload::playlist,
                        NowPlayingPayload::new);
        @Override public Id<NowPlayingPayload> getId() { return ID; }
    }

    // ── S2C: Full state sync JSON for GUI ─────────────────────────────────────────
    public record SyncStatePayload(String json) implements CustomPayload {
        public static final Id<SyncStatePayload> ID =
                new Id<>(Identifier.of("musicmod", "sync_state"));
        public static final PacketCodec<PacketByteBuf, SyncStatePayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING, SyncStatePayload::json,
                        SyncStatePayload::new);
        @Override public Id<SyncStatePayload> getId() { return ID; }
    }

    // ── S2C: GUI feedback message ─────────────────────────────────────────────────
    public record GuiFeedbackPayload(String message) implements CustomPayload {
        public static final Id<GuiFeedbackPayload> ID =
                new Id<>(Identifier.of("musicmod", "gui_feedback"));
        public static final PacketCodec<PacketByteBuf, GuiFeedbackPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING, GuiFeedbackPayload::message,
                        GuiFeedbackPayload::new);
        @Override public Id<GuiFeedbackPayload> getId() { return ID; }
    }

    // ── C2S: Client adds a song ───────────────────────────────────────────────────
    public record AddSongPayload(String url, String playlist) implements CustomPayload {
        public static final Id<AddSongPayload> ID =
                new Id<>(Identifier.of("musicmod", "c2s_add_song"));
        public static final PacketCodec<PacketByteBuf, AddSongPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING, AddSongPayload::url,
                        PacketCodecs.STRING, AddSongPayload::playlist,
                        AddSongPayload::new);
        @Override public Id<AddSongPayload> getId() { return ID; }
    }

    // ── C2S: Playlist/playback action from GUI ────────────────────────────────────
    // Actions: play, skip, stop, playlist_play, create, delete,
    //          remove_from_playlist, remove_from_library,
    //          playlist_play_song (arg = "playlistName:index"),
    //          playlist_move_song (arg = "playlistName:fromIndex:toIndex")
    public record PlaylistActionPayload(String action, String arg) implements CustomPayload {
        public static final Id<PlaylistActionPayload> ID =
                new Id<>(Identifier.of("musicmod", "c2s_playlist_action"));
        public static final PacketCodec<PacketByteBuf, PlaylistActionPayload> CODEC =
                PacketCodec.tuple(
                        PacketCodecs.STRING, PlaylistActionPayload::action,
                        PacketCodecs.STRING, PlaylistActionPayload::arg,
                        PlaylistActionPayload::new);
        @Override public Id<PlaylistActionPayload> getId() { return ID; }
    }

    // ── C2S: Request full state sync ─────────────────────────────────────────────
    public record RequestSyncPayload() implements CustomPayload {
        public static final Id<RequestSyncPayload> ID =
                new Id<>(Identifier.of("musicmod", "c2s_request_sync"));
        public static final PacketCodec<PacketByteBuf, RequestSyncPayload> CODEC =
                PacketCodec.unit(new RequestSyncPayload());
        @Override public Id<RequestSyncPayload> getId() { return ID; }
    }

    private MusicPackets() {}
}
