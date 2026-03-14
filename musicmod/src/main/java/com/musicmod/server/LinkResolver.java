package com.musicmod.server;

import com.musicmod.common.MusicConfig;
import com.musicmod.common.Playlist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.net.http.*;
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
        if (cfg.hasSpotifyCredentials()) {
            // Use Spotify API
            String trackId = extractSpotifyTrackId(spotifyUrl);
            if (trackId != null) {
                SpotifyTrackInfo info = fetchSpotifyTrackInfo(trackId, cfg);
                if (info != null) {
                    return searchYouTube(info.artist + " - " + info.title, cfg);
                }
            }
        }

        // Fallback: use Spotify oEmbed API (no credentials required) to get title
        String oembedTitle = fetchSpotifyTitleFromOembed(spotifyUrl);
        if (oembedTitle != null && !oembedTitle.isBlank()) {
            LOGGER.info("Got Spotify title via oEmbed: {}", oembedTitle);
            String ytUrl = searchYouTube(oembedTitle, cfg);
            if (ytUrl != null) return ytUrl;
        }

        // Last resort: try yt-dlp directly on the Spotify URL
        // (works for Spotify podcast episodes and sometimes previews)
        LOGGER.warn("Trying yt-dlp directly on Spotify URL (may fail for full tracks): {}", spotifyUrl);
        return spotifyUrl;
    }

    /**
     * Fetch track title from Spotify's public oEmbed endpoint.
     * Returns "Artist - Title" format or just title. No credentials required.
     */
    private String fetchSpotifyTitleFromOembed(String spotifyUrl) {
        try {
            String encodedUrl = URLEncoder.encode(spotifyUrl, "UTF-8");
            URL apiUrl = new URL("https://open.spotify.com/oembed?url=" + encodedUrl);
            HttpURLConnection conn = (HttpURLConnection) apiUrl.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");
            if (conn.getResponseCode() != 200) return null;
            String json = new String(conn.getInputStream().readAllBytes());
            // oEmbed returns {"title": "Artist Name - Track Name", ...}
            return extractJsonField(json, "title");
        } catch (Exception e) {
            LOGGER.warn("Spotify oEmbed lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private static class SpotifyTrackInfo { String title, artist; }

    private SpotifyTrackInfo fetchSpotifyTrackInfo(String trackId, MusicConfig cfg) {
        try {
            String credentials = cfg.spotifyClientId + ":" + cfg.spotifyClientSecret;
            String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
            HttpURLConnection tokenConn = (HttpURLConnection)
                    new URL("https://accounts.spotify.com/api/token").openConnection();
            tokenConn.setRequestMethod("POST");
            tokenConn.setDoOutput(true);
            tokenConn.setRequestProperty("Authorization", "Basic " + encoded);
            tokenConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            tokenConn.getOutputStream().write("grant_type=client_credentials".getBytes());
            String tokenJson = new String(tokenConn.getInputStream().readAllBytes());
            String accessToken = extractJsonField(tokenJson, "access_token");
            if (accessToken == null) return null;

            HttpURLConnection trackConn = (HttpURLConnection)
                    new URL("https://api.spotify.com/v1/tracks/" + trackId).openConnection();
            trackConn.setRequestProperty("Authorization", "Bearer " + accessToken);
            String trackJson = new String(trackConn.getInputStream().readAllBytes());
            SpotifyTrackInfo info = new SpotifyTrackInfo();
            info.title  = extractJsonField(trackJson, "name");
            info.artist = extractJsonField(trackJson, "name", trackJson.indexOf("\"artists\""));
            return info.title != null ? info : null;
        } catch (Exception e) {
            LOGGER.error("Spotify API error: {}", e.getMessage());
            return null;
        }
    }

    private String searchYouTube(String query, MusicConfig cfg) throws Exception {
        List<String> cmd = List.of(cfg.ytDlpPath, "--get-id", "--no-playlist",
                "--default-search", "ytsearch1", query);
        String videoId = runProcess(cmd, cfg.resolveTimeoutSeconds);
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
