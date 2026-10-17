package indi.etern.musichud.fabric.client;

import fuzs.forgeconfigapiport.api.config.v2.ModConfigEvents;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.platform.mod.forgeConfig.config.ClientConfigDefinition;
import indi.etern.musichud.client.ui.hud.HudRendererManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

public final class ClientInitializer implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Forge Config API Port 8.x (Minecraft 1.20.1) exposes the v2 API.
        ModConfigEvents.loading(MusicHud.MOD_ID).register((config) -> {
            if (config.getSpec() == ClientConfigDefinition.configure.getRight()) {
                HudRendererManager hudRendererManager = HudRendererManager.getInstance();
                // 1.20.1 has no HudElementRegistry (that arrived in 1.21); the Fabric API
                // HudRenderCallback takes (GuiGraphics, float partialTick).
                HudRenderCallback.EVENT.register(hudRendererManager::renderFrame);
            }
        });
    }
}
