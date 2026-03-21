package com.musicmod.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicmod.common.MusicConfig;
import com.musicmod.common.Playlist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Resolves YouTube/Spotify/SoundCloud links to publicly accessible mp3 URLs.
 *
 * Flow:
 *  1. Run yt-dlp -x --audio-format mp3 to download + convert
 *  2. Upload the mp3 to catbox.moe (free public file host)
 *  3. Return the public URL
 *
 * Spotify without credentials: use oembed API to get title, then search YouTube.
 * SoundCloud: yt-dlp handles it natively.
 * Direct audio URLs are passed through unchanged.
 */
public class LinkResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/LinkResolver");
    private static final Path CACHE_DIR;

    static {
        try {
            CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "musicmod_cache");
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create cache dir", e);
        }
    }

    private static LinkResolver instance;
    public static LinkResolver get() {
        if (instance == null) instance = new LinkResolver();
        return instance;
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "musicmod-resolver");
        t.setDaemon(true);
        return t;
    });

    // In-memory access token cache — avoids redundant token requests
    private volatile String cachedAccessToken = null;
    private volatile long   tokenExpiry        = 0;

    private LinkResolver() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean resolve(Playlist.Song song) {
        MusicConfig cfg = MusicConfig.get();
        if (song.isResolved(cfg.urlCacheTtlSeconds)) return true;

        String src = song.getSourceUrl();
        try {
            if (!Playlist.Song.isYouTube(src) && !Playlist.Song.isSpotify(src) && !Playlist.Song.isSoundCloud(src)) {
                song.setResolvedUrl(src);
                return true;
            }

            String targetUrl = src;

            if (Playlist.Song.isSpotify(src)) {
                targetUrl = resolveSpotifyToYoutube(src, cfg);
                if (targetUrl == null) {
                    LOGGER.error("Failed to resolve Spotify URL to YouTube: {}", src);
                    return false;
                }
            }
            // SoundCloud and YouTube go straight to downloadConvertAndUpload

            return downloadConvertAndUpload(song, targetUrl, cfg);

        } catch (Exception e) {
            LOGGER.error("Resolution failed for {}: {}", src, e.getMessage());
            return false;
        }
    }

    public Future<Boolean> resolveAsync(Playlist.Song song,
                                        java.util.function.Consumer<Boolean> onDone) {
        return pool.submit(() -> {
            boolean ok = resolve(song);
            if (onDone != null) onDone.accept(ok);
            return ok;
        });
    }

    // ── Download, convert, upload ─────────────────────────────────────────────

    private boolean downloadConvertAndUpload(Playlist.Song song, String url, MusicConfig cfg)
            throws Exception {

        String idTemp = url.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (idTemp.length() > 60) idTemp = idTemp.substring(idTemp.length() - 60);
        final String id = idTemp;
        final Path outFile = CACHE_DIR.resolve(id + ".mp3");

        // Check cached public URL
        Path urlCacheFile = CACHE_DIR.resolve(id + ".url");
        if (Files.exists(urlCacheFile)) {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(urlCacheFile).toMillis();
            if (age < cfg.urlCacheTtlSeconds * 1000L) {
                String cachedUrl = Files.readString(urlCacheFile).trim();
                if (!cachedUrl.isBlank()) {
                    song.setResolvedUrl(cachedUrl);
                    LOGGER.info("Using cached public URL for: {}", song.getDisplayName());
                    // Try to load cached duration
                    Path durFile = CACHE_DIR.resolve(id + ".dur");
                    if (Files.exists(durFile)) {
                        try { song.setDurationSeconds(Integer.parseInt(Files.readString(durFile).trim())); }
                        catch (Exception ignored) {}
                    }
                    return true;
                }
            }
        }

        // Get title
        List<String> titleCmd = List.of(cfg.ytDlpPath, "--get-title", "--no-playlist", url);
        String title = runProcess(titleCmd, cfg.resolveTimeoutSeconds);
        if (title != null && !title.isBlank())
            song.setDisplayName(title.trim());

        // Get duration
        List<String> durCmd = List.of(cfg.ytDlpPath, "--get-duration", "--no-playlist", url);
        String durStr = runProcess(durCmd, cfg.resolveTimeoutSeconds);
        if (durStr != null && !durStr.isBlank()) {
            int dur = parseDuration(durStr.trim());
            if (dur > 0) song.setDurationSeconds(dur);
        }

        // Download and convert to mp3
        LOGGER.info("Downloading mp3 for: {}", song.getDisplayName());
        List<String> dlCmd = List.of(
            cfg.ytDlpPath,
            "-x", "--audio-format", "mp3",
            "--audio-quality", "0",
            "--no-playlist",
            "--ffmpeg-location", cfg.ffmpegPath,
            "-o", outFile.toString().replace(".mp3", ".%(ext)s"),
            url
        );
        runProcess(dlCmd, cfg.resolveTimeoutSeconds * 3);

        // Find output file
        final Path mp3File;
        if (Files.exists(outFile)) {
            mp3File = outFile;
        } else {
            try (var stream = Files.list(CACHE_DIR)) {
                Optional<Path> found = stream
                    .filter(p -> p.getFileName().toString().startsWith(id)
                              && p.getFileName().toString().endsWith(".mp3"))
                    .findFirst();
                if (found.isEmpty()) {
                    LOGGER.error("yt-dlp did not produce an mp3 file. Is ffmpeg installed?");
                    return false;
                }
                mp3File = found.get();
            }
        }

        // Upload to catbox.moe
        LOGGER.info("Uploading to catbox.moe: {}", song.getDisplayName());
        String publicUrl = uploadTo0x0(mp3File);
        if (publicUrl == null || publicUrl.isBlank()) {
            LOGGER.error("Upload to catbox.moe failed for: {}", song.getDisplayName());
            return false;
        }

        // Cache URL and duration
        Files.writeString(urlCacheFile, publicUrl);
        if (song.getDurationSeconds() > 0) {
            Files.writeString(CACHE_DIR.resolve(id + ".dur"), String.valueOf(song.getDurationSeconds()));
        }
        song.setResolvedUrl(publicUrl);
        LOGGER.info("Uploaded '{}' -> {} ({}s)", song.getDisplayName(), publicUrl, song.getDurationSeconds());
        return true;
    }

    // ── Upload to catbox.moe ──────────────────────────────────────────────────

    private String uploadTo0x0(Path file) {
        try {
            String boundary = "----boundary" + System.currentTimeMillis();
            byte[] fileBytes = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String partHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: audio/mpeg\r\n\r\n";
            body.write(partHeader.getBytes());
            body.write(fileBytes);
            String typeHeader = "\r\n--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n--" + boundary + "--\r\n";
            body.write(typeHeader.getBytes());

            HttpURLConnection conn = (HttpURLConnection)
                    new URL("https://catbox.moe/user/api.php").openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toByteArray());
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                LOGGER.error("catbox.moe returned HTTP {}", code);
                return null;
            }

            String response = new String(conn.getInputStream().readAllBytes()).trim();
            return response.startsWith("https://") ? response : null;

        } catch (Exception e) {
            LOGGER.error("Upload error: {}", e.getMessage());
            return null;
        }
    }

    // ── Spotify → YouTube ─────────────────────────────────────────────────────

    private String resolveSpotifyToYoutube(String spotifyUrl, MusicConfig cfg) throws Exception {
        if (!cfg.hasSpotifyCredentials()) {
            LOGGER.error("Spotify credentials are required to play Spotify links. " +
                    "Set spotifyClientId and spotifyClientSecret in musicmod_config.json.");
            return null;
        }

        String trackId = extractSpotifyTrackId(spotifyUrl);
        if (trackId == null) {
            LOGGER.error("Could not extract Spotify track ID from: {}", spotifyUrl);
            return null;
        }
        SpotifyTrackInfo info = fetchSpotifyTrackInfo(trackId, cfg);
        if (info == null) {
            LOGGER.error("Spotify API returned no info for track: {}", trackId);
            return null;
        }
        return searchYouTube(info.artist + " - " + info.title, cfg);
    }

    private static class SpotifyTrackInfo { String title, artist; }

    private SpotifyTrackInfo fetchSpotifyTrackInfo(String trackId, MusicConfig cfg) {
        try {
            String accessToken = getSpotifyAccessToken(cfg);
            if (accessToken == null) return null;

            HttpURLConnection trackConn = (HttpURLConnection)
                    new URL("https://api.spotify.com/v1/tracks/" + trackId).openConnection();
            trackConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            trackConn.setConnectTimeout(8000);
            trackConn.setReadTimeout(8000);
            if (trackConn.getResponseCode() != 200) {
                LOGGER.error("Spotify API returned HTTP {}", trackConn.getResponseCode());
                return null;
            }
            String trackJson = new String(trackConn.getInputStream().readAllBytes());

            // Use Gson to parse properly — the raw JSON has "album":{"name":"Album Name",...}
            // before the top-level "name":"Track Title", so naive string search gets the
            // album name instead of the track title.
            JsonObject obj = JsonParser.parseString(trackJson).getAsJsonObject();
            SpotifyTrackInfo info = new SpotifyTrackInfo();
            info.title = obj.has("name") ? obj.get("name").getAsString() : null;
            if (obj.has("artists") && obj.getAsJsonArray("artists").size() > 0) {
                info.artist = obj.getAsJsonArray("artists")
                        .get(0).getAsJsonObject().get("name").getAsString();
            }
            if (info.title == null || info.artist == null) {
                LOGGER.error("Spotify track JSON missing title or artist for {}", trackId);
                return null;
            }
            LOGGER.info("Spotify resolved: '{}' by '{}'", info.title, info.artist);
            return info;
        } catch (Exception e) {
            LOGGER.error("Spotify API error: {}", e.getMessage());
            return null;
        }
    }

    private String searchYouTube(String query, MusicConfig cfg) throws Exception {
        // Try YouTube Music first — it has official Artist-Topic uploads and is far more
        // accurate for songs than plain YouTube search.
        List<String> ytmCmd = List.of(cfg.ytDlpPath, "--get-id", "--no-playlist",
                "--default-search", "ytmsearch1", query);
        String videoId = runProcess(ytmCmd, cfg.resolveTimeoutSeconds);
        if (videoId != null && !videoId.isBlank()) {
            return "https://www.youtube.com/watch?v=" + videoId.trim();
        }
        // Fallback to plain YouTube search
        LOGGER.info("YouTube Music search returned nothing for '{}', falling back to YouTube", query);
        List<String> ytCmd = List.of(cfg.ytDlpPath, "--get-id", "--no-playlist",
                "--default-search", "ytsearch1", query);
        videoId = runProcess(ytCmd, cfg.resolveTimeoutSeconds);
        return (videoId != null && !videoId.isBlank())
                ? "https://www.youtube.com/watch?v=" + videoId.trim() : null;
    }

    // ── Process runner ────────────────────────────────────────────────────────

    private String runProcess(List<String> command, int timeoutSeconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line).append("\n");
            } catch (Exception ignored) {}
        }, "yt-dlp-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) { proc.destroyForcibly(); return null; }
        reader.join(1000);
        return out.toString().trim();
    }

    // ── Playlist detection & extraction ───────────────────────────────────────

    /**
     * Returns true if the URL points to a playlist/album rather than a single track.
     * Supports: YouTube playlists, Spotify playlists/albums, SoundCloud sets.
     */
    public static boolean isPlaylistUrl(String url) {
        if (url == null) return false;
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            return url.contains("list=") && !url.contains("/watch?");
        }
        if (url.contains("spotify.com")) {
            try {
                String path = new URL(url).getPath();
                return path.startsWith("/playlist/") || path.startsWith("/album/");
            } catch (Exception e) { return false; }
        }
        if (url.contains("soundcloud.com")) {
            try {
                String path = new URL(url).getPath();
                String[] parts = path.split("/");
                // SoundCloud sets: soundcloud.com/artist/sets/setname
                return path.contains("/sets/") && parts.length >= 4;
            } catch (Exception e) { return false; }
        }
        return false;
    }

    /** A track URL plus an optional pre-fetched display name (may be null). */
    public record TrackEntry(String url, String name) {
        public TrackEntry(String url) { this(url, null); }
    }

    /**
     * Extracts individual track entries (URL + pre-fetched name when available) from a
     * playlist URL.  For Spotify the Spotify API response already contains track names,
     * so they are returned here and can be used to immediately show proper titles without
     * waiting for the full yt-dlp download/upload resolution.
     */
    public List<TrackEntry> extractPlaylistEntries(String url, MusicConfig cfg) {
        if (url.contains("spotify.com")) {
            return extractSpotifyPlaylistEntries(url, cfg);
        }
        // YouTube / SoundCloud: yt-dlp returns URLs only, no titles in flat mode
        try {
            List<String> cmd = List.of(cfg.ytDlpPath,
                    "--flat-playlist", "--get-url", "--no-warnings", url);
            String output = runProcess(cmd, cfg.resolveTimeoutSeconds * 10);
            if (output == null || output.isBlank()) return List.of();
            List<TrackEntry> entries = new ArrayList<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) entries.add(new TrackEntry(trimmed));
            }
            return entries;
        } catch (Exception e) {
            LOGGER.error("Failed to extract playlist entries from {}: {}", url, e.getMessage());
            return List.of();
        }
    }

    /**
     * Convenience wrapper used by callers that only need URLs.
     */
    public List<String> extractPlaylistUrls(String url, MusicConfig cfg) {
        List<TrackEntry> entries = extractPlaylistEntries(url, cfg);
        List<String> urls = new ArrayList<>(entries.size());
        for (TrackEntry e : entries) urls.add(e.url());
        return urls;
    }

    /**
     * Fetches a display name for a playlist/album URL (for creating the playlist).
     * For YouTube/SoundCloud: uses yt-dlp --get-filename with playlist title.
     * For Spotify: uses the Spotify API.
     */
    public String getPlaylistTitle(String url, MusicConfig cfg) {
        if (url.contains("spotify.com")) {
            return getSpotifyCollectionTitle(url, cfg);
        }
        try {
            List<String> cmd = List.of(cfg.ytDlpPath,
                    "--flat-playlist", "--print", "playlist_title",
                    "--no-warnings", "--playlist-items", "1", url);
            String output = runProcess(cmd, cfg.resolveTimeoutSeconds);
            if (output != null && !output.isBlank()) {
                String title = output.split("\n")[0].trim();
                if (!title.isEmpty() && !title.equals("NA")) return title;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not fetch playlist title: {}", e.getMessage());
        }
        // Fallback: derive from URL
        try {
            String path = new URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.isEmpty() ? "Imported Playlist" : last;
        } catch (Exception e) { return "Imported Playlist"; }
    }

    private List<TrackEntry> extractSpotifyPlaylistEntries(String url, MusicConfig cfg) {
        // Try Spotify Web API first — it provides track names so songs show properly immediately
        if (cfg.hasSpotifyCredentials()) {
            List<TrackEntry> apiResult = extractSpotifyViaApi(url, cfg);
            if (!apiResult.isEmpty()) return apiResult;
            LOGGER.warn("Spotify API extraction failed, falling back to yt-dlp.");
        }

        // Fallback: yt-dlp (no names available via this path)
        List<TrackEntry> ytdlpResult = extractPlaylistViaYtDlp(url, cfg);
        if (!ytdlpResult.isEmpty()) return ytdlpResult;

        if (!cfg.hasSpotifyCredentials()) {
            LOGGER.error("No tracks found from Spotify URL. Add spotifyClientId and " +
                    "spotifyClientSecret to musicmod_config.json to enable Spotify imports.");
        }
        return List.of();
    }

    private List<TrackEntry> extractSpotifyViaApi(String url, MusicConfig cfg) {
        try {
            String accessToken = getSpotifyAccessToken(cfg);
            if (accessToken == null) return List.of();

            String path = new URL(url).getPath();
            String[] parts = path.split("/");
            if (parts.length < 3) return List.of();
            String type = parts[1]; // "playlist" or "album"
            String id   = parts[2].split("\\?")[0];

            String apiBase = "https://api.spotify.com/v1/"
                    + (type.equals("album") ? "albums/" : "playlists/")
                    + id + "/tracks?limit=50";

            List<TrackEntry> entries = new ArrayList<>();
            String nextUrl = apiBase;
            while (nextUrl != null) {
                HttpURLConnection conn = (HttpURLConnection) new URL(nextUrl).openConnection();
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                if (conn.getResponseCode() != 200) break;
                String json = new String(conn.getInputStream().readAllBytes());

                JsonObject page = JsonParser.parseString(json).getAsJsonObject();
                JsonArray items = page.has("items") ? page.getAsJsonArray("items") : null;
                if (items == null) break;

                for (JsonElement itemEl : items) {
                    if (itemEl.isJsonNull()) continue;
                    JsonObject item = itemEl.getAsJsonObject();

                    // Playlist pages wrap each track: { "track": { "id": "...", ... } }
                    // Album pages ARE the track object:  { "id": "...", ... }
                    JsonObject track = type.equals("playlist")
                            ? (item.has("track") && !item.get("track").isJsonNull()
                                    ? item.getAsJsonObject("track") : null)
                            : item;

                    if (track == null || !track.has("id") || track.get("id").isJsonNull()) continue;
                    String tid = track.get("id").getAsString();
                    if (tid.isBlank()) continue;

                    // Build a human-readable name from the Spotify response so songs show
                    // proper titles immediately, without waiting for yt-dlp resolution.
                    String displayName = null;
                    if (track.has("name") && !track.get("name").isJsonNull()) {
                        String trackName = track.get("name").getAsString();
                        String artist = "";
                        if (track.has("artists") && track.getAsJsonArray("artists").size() > 0) {
                            JsonObject a = track.getAsJsonArray("artists").get(0).getAsJsonObject();
                            if (a.has("name") && !a.get("name").isJsonNull())
                                artist = a.get("name").getAsString();
                        }
                        displayName = artist.isEmpty() ? trackName : artist + " - " + trackName;
                    }

                    entries.add(new TrackEntry("https://open.spotify.com/track/" + tid, displayName));
                }

                // Pagination — "next" is null when on the last page
                nextUrl = (page.has("next") && !page.get("next").isJsonNull())
                        ? page.get("next").getAsString() : null;
            }
            return entries;
        } catch (Exception e) {
            LOGGER.error("Spotify API extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Generic yt-dlp flat-playlist extraction; used as a fallback for Spotify. */
    private List<TrackEntry> extractPlaylistViaYtDlp(String url, MusicConfig cfg) {
        try {
            List<String> cmd = List.of(cfg.ytDlpPath,
                    "--flat-playlist", "--get-url", "--no-warnings", url);
            String output = runProcess(cmd, cfg.resolveTimeoutSeconds * 10);
            if (output == null || output.isBlank()) return List.of();
            List<TrackEntry> entries = new ArrayList<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) entries.add(new TrackEntry(trimmed));
            }
            return entries;
        } catch (Exception e) {
            LOGGER.warn("yt-dlp extraction failed for {}: {}", url, e.getMessage());
            return List.of();
        }
    }

    private String getSpotifyCollectionTitle(String url, MusicConfig cfg) {
        if (!cfg.hasSpotifyCredentials()) return "Spotify Import";
        try {
            String accessToken = getSpotifyAccessToken(cfg);
            if (accessToken == null) return "Spotify Import";
            String path = new URL(url).getPath();
            String[] parts = path.split("/");
            if (parts.length < 3) return "Spotify Import";
            String type = parts[1];
            String id   = parts[2].split("\\?")[0];
            String apiUrl = "https://api.spotify.com/v1/"
                    + (type.equals("album") ? "albums/" : "playlists/") + id;
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return "Spotify Import";
            String json = new String(conn.getInputStream().readAllBytes());
            // Use Gson to get the top-level "name" field only (not nested artist/track names)
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("name") && !obj.get("name").isJsonNull()) {
                String title = obj.get("name").getAsString();
                if (!title.isBlank()) return title;
            }
            return "Spotify Import";
        } catch (Exception e) {
            return "Spotify Import";
        }
    }

    private String getSpotifyAccessToken(MusicConfig cfg) {
        // Return cached token if still valid (with 60-second buffer)
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiry - 60_000L) {
            return cachedAccessToken;
        }
        try {
            // Prefer refresh token — works for private playlists and doesn't expire
            if (!cfg.spotifyRefreshToken.isBlank()) {
                return refreshAccessToken(cfg);
            }
            // Fall back to client credentials (public playlists only)
            if (cfg.hasSpotifyCredentials()) {
                return fetchClientCredentialsToken(cfg);
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Failed to get Spotify access token: {}", e.getMessage());
            return null;
        }
    }

    private String refreshAccessToken(MusicConfig cfg) throws Exception {
        String body = "grant_type=refresh_token&refresh_token="
                + URLEncoder.encode(cfg.spotifyRefreshToken, "UTF-8");
        JsonObject resp = postSpotifyTokenRequest(cfg, body);
        if (resp == null || !resp.has("access_token")) {
            LOGGER.error("Refresh token flow failed — falling back to client credentials.");
            return cfg.hasSpotifyCredentials() ? fetchClientCredentialsToken(cfg) : null;
        }
        // Spotify sometimes rotates the refresh token
        if (resp.has("refresh_token") && !resp.get("refresh_token").isJsonNull()) {
            String newRefresh = resp.get("refresh_token").getAsString();
            if (!newRefresh.isBlank()) { cfg.spotifyRefreshToken = newRefresh; cfg.save(); }
        }
        int ttl = resp.has("expires_in") ? resp.get("expires_in").getAsInt() : 3600;
        String token = resp.get("access_token").getAsString();
        cacheToken(token, ttl);
        return token;
    }

    private String fetchClientCredentialsToken(MusicConfig cfg) throws Exception {
        JsonObject resp = postSpotifyTokenRequest(cfg, "grant_type=client_credentials");
        if (resp == null || !resp.has("access_token")) return null;
        int ttl = resp.has("expires_in") ? resp.get("expires_in").getAsInt() : 3600;
        String token = resp.get("access_token").getAsString();
        cacheToken(token, ttl);
        return token;
    }

    private JsonObject postSpotifyTokenRequest(MusicConfig cfg, String bodyStr) throws Exception {
        String credentials = cfg.spotifyClientId + ":" + cfg.spotifyClientSecret;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        HttpURLConnection conn = (HttpURLConnection)
                new URL("https://accounts.spotify.com/api/token").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Authorization", "Basic " + encoded);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.getOutputStream().write(bodyStr.getBytes());
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return null;
        String json = new String(is.readAllBytes());
        try { return JsonParser.parseString(json).getAsJsonObject(); }
        catch (Exception e) { LOGGER.error("Could not parse token response: {}", json); return null; }
    }

    private void cacheToken(String token, int expiresInSeconds) {
        cachedAccessToken = token;
        tokenExpiry = System.currentTimeMillis() + expiresInSeconds * 1000L;
    }

    // ── Spotify OAuth (Authorization Code flow) ───────────────────────────────

    /**
     * Builds the Spotify authorization URL the user must open in a browser.
     * Includes the playlist-read-private scope so private playlists are accessible.
     * The redirect_uri must be registered in the user's Spotify Developer Dashboard.
     */
    public String generateSpotifyAuthUrl(MusicConfig cfg) {
        try {
            String redirectUri = "http://127.0.0.1:" + cfg.spotifyOAuthPort + "/callback";
            return "https://accounts.spotify.com/authorize"
                    + "?client_id=" + URLEncoder.encode(cfg.spotifyClientId, "UTF-8")
                    + "&response_type=code"
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
                    + "&scope=" + URLEncoder.encode(
                            "playlist-read-private playlist-read-collaborative", "UTF-8");
        } catch (Exception e) {
            LOGGER.error("Failed to build Spotify auth URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Starts a temporary HTTP server on 127.0.0.1:<cfg.spotifyOAuthPort> that waits for
     * Spotify's OAuth redirect, extracts the authorization code, sends a success page, and
     * calls onCode with the code (or null on failure/timeout).
     * The server accepts one connection then exits.
     */
    public void startOAuthCallbackServer(MusicConfig cfg,
                                         java.util.function.Consumer<String> onCode) {
        int port = cfg.spotifyOAuthPort;
        Thread t = new Thread(() -> {
            try (java.net.ServerSocket srv = new java.net.ServerSocket(
                    port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
                srv.setSoTimeout(120_000); // 2-minute window to complete auth
                LOGGER.info("Waiting for Spotify OAuth callback on http://127.0.0.1:{}/callback", port);
                try (java.net.Socket sock = srv.accept()) {
                    java.io.BufferedReader rdr = new java.io.BufferedReader(
                            new java.io.InputStreamReader(sock.getInputStream()));
                    String line = rdr.readLine(); // "GET /callback?code=XXX HTTP/1.1"
                    String code = null;
                    if (line != null && line.startsWith("GET ")) {
                        String path = line.split(" ")[1];
                        int q = path.indexOf('?');
                        if (q >= 0) {
                            for (String param : path.substring(q + 1).split("&")) {
                                if (param.startsWith("code=")) {
                                    code = URLDecoder.decode(
                                            param.substring(5), "UTF-8");
                                    break;
                                }
                            }
                        }
                    }
                    String html = code != null
                            ? "<html><body><h2>Authorized! You can close this tab.</h2></body></html>"
                            : "<html><body><h2>Error: no code received.</h2></body></html>";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n"
                            + "Content-Length: " + html.length() + "\r\nConnection: close\r\n\r\n" + html;
                    sock.getOutputStream().write(resp.getBytes());
                    sock.getOutputStream().flush();
                    onCode.accept(code);
                }
            } catch (java.net.SocketTimeoutException e) {
                LOGGER.warn("Spotify OAuth server timed out (2 min).");
                onCode.accept(null);
            } catch (Exception e) {
                LOGGER.error("OAuth callback server error: {}", e.getMessage());
                onCode.accept(null);
            }
        }, "musicmod-oauth-server");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Exchanges a Spotify authorization code for an access + refresh token.
     * Saves the refresh token to config for future use.
     * Returns the access token, or null on failure.
     */
    public String exchangeSpotifyAuthCode(String code, MusicConfig cfg) {
        try {
            String redirectUri = "http://127.0.0.1:" + cfg.spotifyOAuthPort + "/callback";
            String body = "grant_type=authorization_code"
                    + "&code=" + URLEncoder.encode(code, "UTF-8")
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8");
            JsonObject resp = postSpotifyTokenRequest(cfg, body);
            if (resp == null || !resp.has("access_token")) {
                LOGGER.error("Code exchange returned no access_token: {}", resp);
                return null;
            }
            String accessToken = resp.get("access_token").getAsString();
            if (resp.has("refresh_token") && !resp.get("refresh_token").isJsonNull()) {
                String refreshToken = resp.get("refresh_token").getAsString();
                if (!refreshToken.isBlank()) {
                    cfg.spotifyRefreshToken = refreshToken;
                    cfg.save();
                    LOGGER.info("Spotify refresh token saved — private playlists now accessible.");
                }
            }
            int ttl = resp.has("expires_in") ? resp.get("expires_in").getAsInt() : 3600;
            cacheToken(accessToken, ttl);
            return accessToken;
        } catch (Exception e) {
            LOGGER.error("Spotify code exchange failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static String placeholderName(String url) {
        if (Playlist.Song.isYouTube(url))
            return "yt:" + url.replaceAll(".*[?&]v=([^&]+).*", "$1");
        if (Playlist.Song.isSpotify(url))
            return "spot:" + extractTrackIdStatic(url);
        if (Playlist.Song.isSoundCloud(url)) {
            try {
                String path = new URL(url).getPath();
                String[] parts = path.split("/");
                // SoundCloud URLs: /artist/track-name
                return parts.length >= 3 ? "sc:" + parts[2] : "sc:" + url.hashCode();
            } catch (Exception e) { return "sc:" + url.hashCode(); }
        }
        try {
            String path = new URL(url).getPath();
            String file = path.substring(path.lastIndexOf('/') + 1);
            return file.isEmpty() ? url : file;
        } catch (Exception e) { return url; }
    }

    private static String extractSpotifyTrackId(String url) {
        try {
            String path = new URL(url).getPath();
            String[] parts = path.split("/");
            return parts.length >= 3 && parts[1].equals("track") ? parts[2] : null;
        } catch (Exception e) { return null; }
    }

    private static String extractTrackIdStatic(String url) {
        try {
            String path = new URL(url).getPath();
            String[] parts = path.split("/");
            return parts.length >= 3 ? parts[2] : url;
        } catch (Exception e) { return url; }
    }

    private static int parseDuration(String s) {
        try {
            String[] parts = s.split(":");
            int seconds = 0;
            for (String p : parts) seconds = seconds * 60 + Integer.parseInt(p.trim());
            return seconds;
        } catch (Exception e) { return -1; }
    }

    private static String extractJsonField(String json, String key) {
        return extractJsonField(json, key, 0);
    }

    private static String extractJsonField(String json, String key, int startIndex) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search, Math.max(0, startIndex));
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
