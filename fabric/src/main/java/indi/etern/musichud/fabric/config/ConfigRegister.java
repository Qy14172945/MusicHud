package indi.etern.musichud.fabric.config;

import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.platform.mod.forgeConfig.config.ClientConfigDefinition;
import indi.etern.musichud.platform.mod.forgeConfig.config.ServerConfigDefinition;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraftforge.fml.config.ModConfig;

public class ConfigRegister implements DedicatedServerModInitializer, ClientModInitializer {
    @Override
    public void onInitializeServer() {
        // COMMON rather than SERVER: a SERVER-type config is written to
        // "<world>/serverconfig/music_hud-server.toml", which does not exist until a world has
        // been created and loaded, so a dedicated-server operator has no file to edit before the
        // first start and the mod runs on defaults pointing at localhost:3000. Every value in this
        // spec is process-wide rather than per-world, so COMMON is the correct scope and lands in
        // "config/music_hud-common.toml". Kept identical to the Forge adapter.
        ForgeConfigRegistry.INSTANCE.register(MusicHud.MOD_ID, ModConfig.Type.COMMON, ServerConfigDefinition.configure.getRight());
    }

    @Override
    public void onInitializeClient() {
        ForgeConfigRegistry.INSTANCE.register(MusicHud.MOD_ID, ModConfig.Type.CLIENT, ClientConfigDefinition.configure.getRight());
        ForgeConfigRegistry.INSTANCE.register(MusicHud.MOD_ID, ModConfig.Type.COMMON, ServerConfigDefinition.configure.getRight());
    }
}