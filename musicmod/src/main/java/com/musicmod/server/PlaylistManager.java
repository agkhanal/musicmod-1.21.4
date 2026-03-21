package com.musicmod.server;

import com.google.gson.*;
import com.musicmod.common.MusicConfig;
import com.musicmod.common.Playlist;
import com.musicmod.network.MusicPackets;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

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

    // Key = sourceUrl (stable, never changes even when display name resolves)
    private final Map<String, Playlist.Song> library   = new LinkedHashMap<>();
    private final Map<String, Playlist>      playlists = new LinkedHashMap<>();

    // Server reference for syncToAll; set from MusicServerMod on SERVER_STARTED
    private MinecraftServer server;

    private PlaylistManager() { load(); }

    public void setServer(MinecraftServer s) { this.server = s; }

    // ── Song management ───────────────────────────────────────────────────────

    /** Convenience overload without a pre-fetched display name. */
    public synchronized void addSongAsync(String url, String optionalPlaylist,
                             ServerPlayerEntity requester,
                             java.util.function.Consumer<String> onComplete) {
        addSongAsync(url, null, optionalPlaylist, requester, onComplete);
    }

    public synchronized void addSongAsync(String url, String initialName, String optionalPlaylist,
                             ServerPlayerEntity requester,
                             java.util.function.Consumer<String> onComplete) {
        // Use sourceUrl as the stable map key so order never changes after resolution
        if (library.containsKey(url)) {
            // Already in library — just add to the requested playlist if needed
            Playlist.Song existing = library.get(url);
            if (optionalPlaylist != null) {
                Playlist pl = getPlaylist(optionalPlaylist);
                if (pl != null && !pl.getSongs().contains(existing)) pl.addSong(existing);
                save();
            }
            onComplete.accept("\u2714 Already in library: " + existing.getDisplayName());
            return;
        }

        // Use the pre-fetched name if provided, otherwise generate a placeholder.
        // This means Spotify imports show "Artist - Title" immediately instead of "spot:ID".
        String placeholder = (initialName != null && !initialName.isBlank())
                ? initialName : LinkResolver.placeholderName(url);
        Playlist.Song song = new Playlist.Song(placeholder, url);
        library.put(url, song);
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
                // All mutations back on server thread for safety
                if (server != null) {
                    server.execute(() -> {
                        save();
                        if (ok) {
                            onComplete.accept("\u2714 Added: " + song.getDisplayName());
                            syncToAll();
                        } else {
                            onComplete.accept("\u26a0 Could not resolve URL. Song stored but may not play.");
                        }
                    });
                } else {
                    // Fallback if server ref not set yet
                    synchronized (PlaylistManager.this) {
                        save();
                        if (ok) {
                            onComplete.accept("\u2714 Added: " + song.getDisplayName());
                            if (requester != null && requester.networkHandler != null)
                                syncToPlayer(requester);
                        } else {
                            onComplete.accept("\u26a0 Could not resolve URL. Song stored but may not play.");
                        }
                    }
                }
            });
    }

    /**
     * Imports all songs from a playlist/album URL into a named playlist.
     */
    public void importPlaylistAsync(String playlistUrl, ServerPlayerEntity requester,
                                    java.util.function.Consumer<String> onStatus) {
        onStatus.accept("\u23f3 Fetching playlist info\u2026");
        CompletableFuture.supplyAsync(() -> {
            MusicConfig cfg = MusicConfig.get();
            String playlistName = LinkResolver.get().getPlaylistTitle(playlistUrl, cfg);
            List<LinkResolver.TrackEntry> entries = LinkResolver.get().extractPlaylistEntries(playlistUrl, cfg);
            return new Object[]{ playlistName, entries };
        }).thenAccept(result -> {
            String playlistName = (String) result[0];
            @SuppressWarnings("unchecked")
            List<LinkResolver.TrackEntry> entries = (List<LinkResolver.TrackEntry>) result[1];

            if (entries.isEmpty()) {
                onStatus.accept("\u26a0 No tracks found. Check the URL or credentials.");
                return;
            }

            if (server != null) {
                server.execute(() -> {
                    createPlaylist(playlistName);
                    onStatus.accept("\u23f3 Importing " + entries.size()
                            + " tracks into \u201c" + playlistName + "\u201d\u2026");
                    AtomicInteger counter = new AtomicInteger(0);
                    int total = entries.size();
                    for (LinkResolver.TrackEntry entry : entries) {
                        addSongAsync(entry.url(), entry.name(), playlistName, requester, msg -> {
                            if (counter.incrementAndGet() == total) {
                                onStatus.accept("\u2714 Import complete: " + total
                                        + " tracks added to \u201c" + playlistName + "\u201d");
                                syncToAll();
                            }
                        });
                    }
                });
            }
        });
    }

    public synchronized boolean removeSong(String displayName) {
        // Search by display name since keys are now sourceUrls
        String key = null;
        for (Map.Entry<String, Playlist.Song> e : library.entrySet()) {
            if (e.getValue().getDisplayName().equalsIgnoreCase(displayName)) {
                key = e.getKey();
                break;
            }
        }
        if (key == null) return false;
        library.remove(key);
        final String name = displayName;
        playlists.values().forEach(pl -> pl.removeSong(name));
        save();
        return true;
    }

    public synchronized Collection<Playlist.Song> getAllSongs() {
        return Collections.unmodifiableCollection(new ArrayList<>(library.values()));
    }

    public synchronized Playlist.Song getSongByDisplayName(String displayName) {
        for (Playlist.Song s : library.values())
            if (s.getDisplayName().equalsIgnoreCase(displayName)) return s;
        return null;
    }

    public synchronized Playlist.Song getSongByUrl(String url) {
        return library.get(url);
    }

    public synchronized Playlist.Song resolveSong(String nameOrUrl) {
        // Try by URL first (exact)
        Playlist.Song byUrl = library.get(nameOrUrl);
        if (byUrl != null) return byUrl;
        // Try by display name
        Playlist.Song byName = getSongByDisplayName(nameOrUrl);
        if (byName != null) return byName;
        // Construct ephemeral for direct URLs
        if (nameOrUrl.startsWith("http://") || nameOrUrl.startsWith("https://")) {
            Playlist.Song temp = new Playlist.Song(LinkResolver.placeholderName(nameOrUrl), nameOrUrl);
            if (!temp.needsResolution()) temp.setResolvedUrl(nameOrUrl);
            return temp;
        }
        return null;
    }

    // ── Playlist management ───────────────────────────────────────────────────

    public synchronized boolean createPlaylist(String name) {
        if (playlists.containsKey(name.toLowerCase())) return false;
        playlists.put(name.toLowerCase(), new Playlist(name));
        save();
        return true;
    }

    public synchronized boolean deletePlaylist(String name) {
        boolean ok = playlists.remove(name.toLowerCase()) != null;
        if (ok) save();
        return ok;
    }

    public synchronized Playlist getPlaylist(String name) {
        return playlists.get(name.toLowerCase());
    }

    public synchronized Collection<Playlist> getAllPlaylists() {
        return Collections.unmodifiableCollection(new ArrayList<>(playlists.values()));
    }

    public synchronized boolean addSongToPlaylist(String pl, String songName) {
        Playlist p = getPlaylist(pl);
        if (p == null) return false;
        Playlist.Song s = getSongByDisplayName(songName);
        if (s == null) return false;
        p.addSong(s);
        save();
        return true;
    }

    public synchronized boolean removeSongFromPlaylist(String pl, String songDisplayName) {
        Playlist p = getPlaylist(pl);
        if (p == null) return false;
        boolean ok = p.removeSong(songDisplayName);
        if (ok) save();
        return ok;
    }

    public synchronized boolean moveSongInPlaylist(String pl, int fromIndex, int toIndex) {
        Playlist p = getPlaylist(pl);
        if (p == null) return false;
        boolean ok = p.moveSong(fromIndex, toIndex);
        if (ok) save();
        return ok;
    }

    public synchronized boolean toggleShuffle(String name) {
        Playlist p = getPlaylist(name);
        if (p == null) return false;
        p.setShuffle(!p.isShuffle());
        save();
        return true;
    }

    public synchronized void clearPlaylist(String name) {
        Playlist p = getPlaylist(name);
        if (p == null) return;
        p.clearSongs();
        save();
    }

    public synchronized void clearLibrary() {
        library.clear();
        for (Playlist pl : playlists.values()) pl.clearSongs();
        save();
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    public void syncToPlayer(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new MusicPackets.SyncStatePayload(buildSyncJson()));
    }

    /** Push the current state to every connected player. */
    public void syncToAll() {
        if (server == null) return;
        String json = buildSyncJson();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(p, new MusicPackets.SyncStatePayload(json));
        }
    }

    public static void sendFeedback(ServerPlayerEntity player, String msg) {
        ServerPlayNetworking.send(player, new MusicPackets.GuiFeedbackPayload(msg));
    }

    public synchronized String buildSyncJson() {
        JsonObject root = new JsonObject();

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

        JsonArray plArr = new JsonArray();
        for (Playlist pl : playlists.values()) {
            JsonObject pObj = new JsonObject();
            pObj.addProperty("name", pl.getName());
            pObj.addProperty("shuffle", pl.isShuffle());
            pObj.addProperty("loop", pl.isLoop());
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

        MusicSessionController ctrl = MusicSessionController.get();
        JsonObject session = new JsonObject();
        session.addProperty("playing", ctrl.isPlaying());
        session.addProperty("song",    ctrl.getCurrentSong() != null ? ctrl.getCurrentSong().getDisplayName() : "");
        session.addProperty("playlist", ctrl.getActivePlaylist() != null ? ctrl.getActivePlaylist().getName() : "");
        root.add("session", session);

        return GSON.toJson(root);
    }

    public synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            // Save using sourceUrl as key (stable) and displayName as the display value
            JsonArray libArr = new JsonArray();
            for (Playlist.Song s : library.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", s.getDisplayName());
                o.addProperty("url", s.getSourceUrl());
                libArr.add(o);
            }
            root.add("library", libArr);
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
                JsonElement libEl = root.get("library");
                if (libEl.isJsonArray()) {
                    // New format: [{name, url}, ...]
                    for (JsonElement el : libEl.getAsJsonArray()) {
                        JsonObject o = el.getAsJsonObject();
                        String name = o.get("name").getAsString();
                        String url  = o.get("url").getAsString();
                        library.put(url, new Playlist.Song(name, url));
                    }
                } else {
                    // Legacy format: {displayName: sourceUrl, ...}
                    for (var e : libEl.getAsJsonObject().entrySet())
                        library.put(e.getValue().getAsString(),
                                new Playlist.Song(e.getKey(), e.getValue().getAsString()));
                }
            }
            if (root.has("playlists")) {
                for (JsonElement el : root.getAsJsonArray("playlists")) {
                    JsonObject pObj = el.getAsJsonObject();
                    Playlist pl = new Playlist(pObj.get("name").getAsString());
                    pl.setShuffle(pObj.has("shuffle") && pObj.get("shuffle").getAsBoolean());
                    // Don't default loop=true from saved files; respect the saved value
                    if (pObj.has("loop")) pl.setLoop(pObj.get("loop").getAsBoolean());
                    if (pObj.has("songs")) {
                        for (JsonElement se : pObj.getAsJsonArray("songs")) {
                            String srcUrl = se.getAsString();
                            Playlist.Song s = library.get(srcUrl);
                            if (s != null) pl.addSong(s);
                        }
                    }
                    playlists.put(pl.getName().toLowerCase(), pl);
                }
            }
            LOGGER.info("Loaded {} songs, {} playlists.", library.size(), playlists.size());
        } catch (Exception e) { LOGGER.error("Failed to load playlists", e); }
    }
}
