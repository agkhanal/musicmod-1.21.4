package com.musicmod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side config: HUD position, volume, and display preferences.
 * Persisted to config/musicmod_client.json.
 */
@Environment(EnvType.CLIENT)
public class ClientMusicConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/ClientConfig");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SAVE_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("musicmod_client.json");

    private static ClientMusicConfig instance;
    public static ClientMusicConfig get() {
        if (instance == null) { instance = new ClientMusicConfig(); instance.load(); }
        return instance;
    }

    // ── HUD settings ─────────────────────────────────────────────────────────────
    /** Anchor corner for HUD: TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT */
    public String hudAnchor  = "BOTTOM_LEFT";
    /** X offset from anchor corner (pixels). */
    public int    hudOffsetX = 8;
    /** Y offset from anchor corner (pixels). */
    public int    hudOffsetY = 36;
    /** Whether the permanent HUD is enabled. */
    public boolean hudEnabled = true;

    // ── Audio settings ────────────────────────────────────────────────────────────
    /** Volume 0.0–1.0. */
    public float volume = 0.8f;

    private ClientMusicConfig() {}

    public void save() {
        try {
            Files.writeString(SAVE_PATH, GSON.toJson(this));
        } catch (IOException e) {
            LOGGER.error("Failed to save client config", e);
        }
    }

    private void load() {
        if (!Files.exists(SAVE_PATH)) return;
        try {
            ClientMusicConfig loaded = GSON.fromJson(Files.readString(SAVE_PATH), ClientMusicConfig.class);
            if (loaded != null) {
                this.hudAnchor  = loaded.hudAnchor  != null ? loaded.hudAnchor : hudAnchor;
                this.hudOffsetX = loaded.hudOffsetX;
                this.hudOffsetY = loaded.hudOffsetY;
                this.hudEnabled = loaded.hudEnabled;
                this.volume     = Math.max(0f, Math.min(1f, loaded.volume));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load client config", e);
        }
    }
}
