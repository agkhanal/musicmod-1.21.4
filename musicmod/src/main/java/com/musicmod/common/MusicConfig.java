package com.musicmod.common;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;

/**
 * Reads/writes musicmod.json in the server config directory.
 * Created with defaults on first launch.
 */
public class MusicConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("musicmod.json");

    private static MusicConfig instance;
    public static MusicConfig get() {
        if (instance == null) instance = new MusicConfig();
        return instance;
    }

    // ─── Fields ──────────────────────────────────────────────────────────────────

    /** Path to yt-dlp binary. "yt-dlp" if it's on PATH, otherwise absolute path. */
    public String ytDlpPath = "yt-dlp";
    public String ffmpegPath = "ffmpeg";
    /**
     * Spotify Web API client credentials (https://developer.spotify.com/dashboard).
     * Leave blank to disable Spotify support (YouTube still works without these).
     */
    public String spotifyClientId     = "";
    public String spotifyClientSecret = "";

    /**
     * Spotify OAuth refresh token — obtained via /music spotify auth.
     * When set, private playlists become accessible.
     * The token is stored here automatically after completing the auth flow.
     */
    public String spotifyRefreshToken = "";

    /**
     * Local port used for the temporary OAuth callback server during /music spotify auth.
     * You must register "http://127.0.0.1:<port>/callback" as a redirect URI in your
     * Spotify Developer Dashboard app settings.
     */
    public int spotifyOAuthPort = 8888;

    /**
     * How long (seconds) to cache a resolved audio URL before re-resolving.
     * YouTube CDN URLs expire after ~6 hours, so 21000 is a safe default.
     */
    public int urlCacheTtlSeconds = 21000;

    /**
     * Maximum resolution time (seconds) before yt-dlp is killed.
     */
    public int resolveTimeoutSeconds = 30;

    /**
     * Preferred audio format/quality passed to yt-dlp.
     * "bestaudio" gets the highest quality available.
     */
    public String ytDlpFormat = "bestaudio";

    // ─── Load / Save ─────────────────────────────────────────────────────────────

    private MusicConfig() { load(); }

    private void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save(); // write defaults
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH))
                    .getAsJsonObject();
            if (obj.has("ytDlpPath"))            ytDlpPath            = obj.get("ytDlpPath").getAsString();
            if (obj.has("ffmpegPath"))           ffmpegPath           = obj.get("ffmpegPath").getAsString();
            if (obj.has("spotifyClientId"))      spotifyClientId      = obj.get("spotifyClientId").getAsString();
            if (obj.has("spotifyClientSecret"))  spotifyClientSecret  = obj.get("spotifyClientSecret").getAsString();
            if (obj.has("spotifyRefreshToken"))  spotifyRefreshToken  = obj.get("spotifyRefreshToken").getAsString();
            if (obj.has("spotifyOAuthPort"))     spotifyOAuthPort     = obj.get("spotifyOAuthPort").getAsInt();
            if (obj.has("urlCacheTtlSeconds"))   urlCacheTtlSeconds   = obj.get("urlCacheTtlSeconds").getAsInt();
            if (obj.has("resolveTimeoutSeconds"))resolveTimeoutSeconds= obj.get("resolveTimeoutSeconds").getAsInt();
            if (obj.has("ytDlpFormat"))          ytDlpFormat          = obj.get("ytDlpFormat").getAsString();
            LOGGER.info("Config loaded. yt-dlp: {}", ytDlpPath);
        } catch (Exception e) {
            LOGGER.error("Failed to load config, using defaults", e);
        }
    }

    public void save() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("ffmpegPath", ffmpegPath);
            obj.addProperty("ytDlpPath", ytDlpPath);
            obj.addProperty("spotifyClientId", spotifyClientId);
            obj.addProperty("spotifyClientSecret", spotifyClientSecret);
            obj.addProperty("spotifyRefreshToken", spotifyRefreshToken);
            obj.addProperty("spotifyOAuthPort", spotifyOAuthPort);
            obj.addProperty("urlCacheTtlSeconds", urlCacheTtlSeconds);
            obj.addProperty("resolveTimeoutSeconds", resolveTimeoutSeconds);
            obj.addProperty("ytDlpFormat", ytDlpFormat);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj));
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    public boolean hasSpotifyCredentials() {
        return !spotifyClientId.isBlank() && !spotifyClientSecret.isBlank();
    }
}
