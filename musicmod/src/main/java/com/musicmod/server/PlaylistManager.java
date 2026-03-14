package com.musicmod.server;

import com.google.gson.*;
import com.musicmod.common.MusicConfig;
import com.musicmod.common.Playlist;
import com.musicmod.network.MusicPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class PlaylistManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/PlaylistManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SAVE_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("musicmod_playlists.json");

    private static PlaylistManager instance;
    public static PlaylistManager get() {
        if (instance == null) instance = new PlaylistManager();
        return instance;
    }

    private final Map<String, Playlist.Song> library   = new LinkedHashMap<>();
    private final Map<String, Playlist>      playlists = new LinkedHashMap<>();

    private PlaylistManager() { load(); }

    public void addSongAsync(String url, String optionalPlaylist,
                             ServerPlayerEntity requester,
                             java.util.function.Consumer<String> onComplete) {
        String placeholder = LinkResolver.placeholderName(url);
        Playlist.Song song = new Playlist.Song(placeholder, url);
        library.put(placeholder.toLowerCase(), song);
        if (optionalPlaylist != null) {
            Playlist pl = getPlaylist(optionalPlaylist);
            if (pl != null) pl.addSong(song);
        }
        save();
        if (!song.needsResolution()) {
            song.setResolvedUrl(url);
            onComplete.accept("\u2714 Added: " + placeholder);
            return;
        }
        CompletableFuture.supplyAsync(() -> LinkResolver.get().resolve(song))
            .thenAccept(ok -> {
                save();
                if (ok) {
                    library.remove(placeholder.toLowerCase());
                    library.put(song.getDisplayName().toLowerCase(), song);
                    save();
                    onComplete.accept("\u2714 Added: " + song.getDisplayName());
                    if (requester != null && requester.networkHandler != null)
                        syncToPlayer(requester);
                } else {
                    onComplete.accept("\u26a0 Could not resolve URL. Song stored but may not play.");
                }
            });
    }

    /**
     * Imports all songs from a playlist/album URL into a named playlist.
     * Resolves each track URL in the background and sends progress feedback.
     */
    public void importPlaylistAsync(String playlistUrl, ServerPlayerEntity requester,
                                    java.util.function.Consumer<String> onStatus) {
        onStatus.accept("\u23f3 Fetching playlist info\u2026");
        CompletableFuture.supplyAsync(() -> {
            MusicConfig cfg = MusicConfig.get();
            // Get the playlist title for the new playlist name
            String playlistName = LinkResolver.get().getPlaylistTitle(playlistUrl, cfg);
            List<String> trackUrls = LinkResolver.get().extractPlaylistUrls(playlistUrl, cfg);
            return new Object[]{ playlistName, trackUrls };
        }).thenAccept(result -> {
            String playlistName = (String) result[0];
            @SuppressWarnings("unchecked")
            List<String> trackUrls = (List<String>) result[1];

            if (trackUrls.isEmpty()) {
                onStatus.accept("\u26a0 No tracks found in playlist. Check the URL or credentials.");
                return;
            }

            // Create or reuse the playlist
            createPlaylist(playlistName);
            onStatus.accept("\u23f3 Importing " + trackUrls.size() + " tracks into \u201c" + playlistName + "\u201d\u2026");

            // Add all songs — each resolves in its own thread
            int[] counter = {0};
            int total = trackUrls.size();
            for (String trackUrl : trackUrls) {
                addSongAsync(trackUrl, playlistName, requester, msg -> {
                    counter[0]++;
                    if (counter[0] == total) {
                        onStatus.accept("\u2714 Import complete: " + total + " tracks added to \u201c" + playlistName + "\u201d");
                        if (requester != null && requester.networkHandler != null)
                            syncToPlayer(requester);
                    }
                });
            }
        });
    }

    public boolean removeSong(String name) {
        boolean ok = library.remove(name.toLowerCase()) != null;
        if (ok) { playlists.values().forEach(pl -> pl.removeSong(name)); save(); }
        return ok;
    }

    public Collection<Playlist.Song> getAllSongs()   { return Collections.unmodifiableCollection(library.values()); }
    public Playlist.Song getSong(String name)        { return library.get(name.toLowerCase()); }

    public Playlist.Song resolveSong(String nameOrUrl) {
        Playlist.Song byName = library.get(nameOrUrl.toLowerCase());
        if (byName != null) return byName;
        if (nameOrUrl.startsWith("http://") || nameOrUrl.startsWith("https://")) {
            Playlist.Song temp = new Playlist.Song(LinkResolver.placeholderName(nameOrUrl), nameOrUrl);
            if (!temp.needsResolution()) temp.setResolvedUrl(nameOrUrl);
            return temp;
        }
        return null;
    }

    public boolean createPlaylist(String name) {
        if (playlists.containsKey(name.toLowerCase())) return false;
        playlists.put(name.toLowerCase(), new Playlist(name)); save(); return true;
    }
    public boolean deletePlaylist(String name) {
        boolean ok = playlists.remove(name.toLowerCase()) != null;
        if (ok) save(); return ok;
    }
    public Playlist getPlaylist(String name)          { return playlists.get(name.toLowerCase()); }
    public Collection<Playlist> getAllPlaylists()     { return Collections.unmodifiableCollection(playlists.values()); }

    public boolean addSongToPlaylist(String pl, String song) {
        Playlist p = getPlaylist(pl); if (p == null) return false;
        Playlist.Song s = getSong(song); if (s == null) return false;
        p.addSong(s); save(); return true;
    }
    public boolean removeSongFromPlaylist(String pl, String song) {
        Playlist p = getPlaylist(pl); if (p == null) return false;
        boolean ok = p.removeSong(song); if (ok) save(); return ok;
    }

    public boolean moveSongInPlaylist(String pl, int fromIndex, int toIndex) {
        Playlist p = getPlaylist(pl); if (p == null) return false;
        boolean ok = p.moveSong(fromIndex, toIndex); if (ok) save(); return ok;
    }

    public void syncToPlayer(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new MusicPackets.SyncStatePayload(buildSyncJson()));
    }

    public static void sendFeedback(ServerPlayerEntity player, String msg) {
        ServerPlayNetworking.send(player, new MusicPackets.GuiFeedbackPayload(msg));
    }

    public String buildSyncJson() {
        JsonObject root = new JsonObject();

        // Library
        JsonArray libArr = new JsonArray();
        for (Playlist.Song s : library.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.getDisplayName());
            o.addProperty("url", s.getSourceUrl());
            o.addProperty("resolved", s.isResolved(MusicConfig.get().urlCacheTtlSeconds));
            o.addProperty("duration", s.getDurationSeconds());
            libArr.add(o);
        }
        root.add("library", libArr);

        // Playlists — include full song details so client can show playlist songs
        JsonArray plArr = new JsonArray();
        for (Playlist pl : playlists.values()) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("name", pl.getName());
            pObj.addProperty("shuffle", pl.isShuffle());
            JsonArray songs = new JsonArray();
            for (Playlist.Song s : pl.getSongs()) {
                JsonObject sObj = new JsonObject();
                sObj.addProperty("name", s.getDisplayName());
                sObj.addProperty("url", s.getSourceUrl());
                sObj.addProperty("resolved", s.isResolved(MusicConfig.get().urlCacheTtlSeconds));
                sObj.addProperty("duration", s.getDurationSeconds());
                songs.add(sObj);
            }
            pObj.add("songs", songs);
            plArr.add(pObj);
        }
        root.add("playlists", plArr);

        // Session
        MusicSessionController ctrl = MusicSessionController.get();
        JsonObject session = new JsonObject();
        session.addProperty("playing", ctrl.isPlaying());
        session.addProperty("song",     ctrl.getCurrentSong() != null ? ctrl.getCurrentSong().getDisplayName() : "");
        session.addProperty("playlist", ctrl.getActivePlaylist() != null ? ctrl.getActivePlaylist().getName() : "");
        root.add("session", session);

        return GSON.toJson(root);
    }

    public void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject lib = new JsonObject();
            for (Playlist.Song s : library.values()) lib.addProperty(s.getDisplayName(), s.getSourceUrl());
            root.add("library", lib);
            JsonArray pArr = new JsonArray();
            for (Playlist pl : playlists.values()) {
                JsonObject pObj = new JsonObject();
                pObj.addProperty("name", pl.getName());
                pObj.addProperty("shuffle", pl.isShuffle());
                pObj.addProperty("loop", pl.isLoop());
                JsonArray sArr = new JsonArray();
                for (Playlist.Song s : pl.getSongs()) sArr.add(s.getSourceUrl());
                pObj.add("songs", sArr);
                pArr.add(pObj);
            }
            root.add("playlists", pArr);
            Files.writeString(SAVE_PATH, GSON.toJson(root));
        } catch (IOException e) { LOGGER.error("Failed to save playlists", e); }
    }

    private void load() {
        if (!Files.exists(SAVE_PATH)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(SAVE_PATH)).getAsJsonObject();
            if (root.has("library")) {
                for (var e : root.getAsJsonObject("library").entrySet())
                    library.put(e.getKey().toLowerCase(), new Playlist.Song(e.getKey(), e.getValue().getAsString()));
            }
            if (root.has("playlists")) {
                for (JsonElement el : root.getAsJsonArray("playlists")) {
                    JsonObject pObj = el.getAsJsonObject();
                    Playlist pl = new Playlist(pObj.get("name").getAsString());
                    pl.setShuffle(pObj.has("shuffle") && pObj.get("shuffle").getAsBoolean());
                    pl.setLoop(!pObj.has("loop") || pObj.get("loop").getAsBoolean());
                    if (pObj.has("songs")) {
                        for (JsonElement se : pObj.getAsJsonArray("songs")) {
                            String srcUrl = se.getAsString();
                            library.values().stream().filter(s -> s.getSourceUrl().equals(srcUrl))
                                .findFirst().ifPresent(pl::addSong);
                        }
                    }
                    playlists.put(pl.getName().toLowerCase(), pl);
                }
            }
            LOGGER.info("Loaded {} songs, {} playlists.", library.size(), playlists.size());
        } catch (Exception e) { LOGGER.error("Failed to load playlists", e); }
    }
}
