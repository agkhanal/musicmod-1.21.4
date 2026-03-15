package com.musicmod.client;

import com.musicmod.network.MusicPackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class MusicClientMod implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/client");
    private static KeyBinding openGuiKey;
    private static MusicScreen openScreen = null;

    @Override
    public void onInitializeClient() {
        LOGGER.info("MusicMod client initializing (1.21.4)...");

        // Load client config and apply saved volume
        ClientMusicConfig cfg = ClientMusicConfig.get();
        MusicPlayer.get().setVolume(cfg.volume);

        // ── Keybinding ────────────────────────────────────────────────────────────
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.musicmod.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.musicmod"));

        // ── S2C receivers ─────────────────────────────────────────────────────────
        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.PlaySongPayload.ID,
            (payload, context) -> context.client().execute(() -> {
                MusicPlayer player = MusicPlayer.get();
                player.play(payload.url(), payload.durationSeconds());
                // Echo the song sequence number back when done so the server can
                // ignore duplicate finish events from other players.
                final int seq = payload.songSeq();
                player.setOnFinished(() ->
                    ClientPlayNetworking.send(
                        new MusicPackets.PlaylistActionPayload("song_finished", String.valueOf(seq))));
            }));

        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.StopMusicPayload.ID,
            (payload, context) -> context.client().execute(() -> {
                MusicPlayer.get().stop();
                MusicHudRenderer.get().clear();
            }));

        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.NowPlayingPayload.ID,
            (payload, context) -> context.client().execute(() -> {
                MusicHudRenderer.get().showNowPlaying(payload.song(), payload.playlist());
                if (openScreen != null) openScreen.setNowPlaying(payload.song(), payload.playlist());
            }));

        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.SyncStatePayload.ID,
            (payload, context) -> context.client().execute(() -> {
                if (openScreen != null) openScreen.applySync(payload.json());
            }));

        ClientPlayNetworking.registerGlobalReceiver(MusicPackets.GuiFeedbackPayload.ID,
            (payload, context) -> context.client().execute(() -> {
                if (openScreen != null) openScreen.setFeedback(payload.message());
            }));

        // ── Stop music when leaving world ─────────────────────────────────────────
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> {
                MusicPlayer.get().stop();
                MusicHudRenderer.get().clear();
                LOGGER.info("Disconnected from server — stopped music.");
            });
        });

        // ── Tick ─────────────────────────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MusicHudRenderer.get().tick();
            if (openGuiKey.wasPressed() && client.currentScreen == null) {
                MusicScreen screen = new MusicScreen();
                openScreen = screen;
                client.setScreen(screen);
                ClientPlayNetworking.send(new MusicPackets.RequestSyncPayload());
            }
            if (openScreen != null && client.currentScreen != openScreen) openScreen = null;
        });

        // ── HUD ──────────────────────────────────────────────────────────────────
        HudRenderCallback.EVENT.register((ctx, tickDelta) ->
            MusicHudRenderer.get().render(ctx));

        LOGGER.info("MusicMod client ready. Press M to open Music Manager.");
    }
}
