package indi.etern.musichud.forge;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.client.ui.hud.HudRendererManager;
import indi.etern.musichud.interfaces.ClientConfig;
import indi.etern.musichud.interfaces.ServerConfig;
import indi.etern.musichud.platform.Environment;
import indi.etern.musichud.platform.mod.forge.event.ForgeClientEventService;
import indi.etern.musichud.platform.mod.forge.event.ForgeServerEventService;
import indi.etern.musichud.platform.mod.forge.network.ForgeNetworkManager;
import indi.etern.musichud.platform.mod.forge.registry.ForgeKeyRegistryService;
import indi.etern.musichud.platform.mod.forgeConfig.config.ClientConfigDefinition;
import indi.etern.musichud.platform.mod.forgeConfig.config.ServerConfigDefinition;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(MusicHud.MOD_ID)
public final class CommonInitializer {
    private static final ServerConfig serverConfig = ServerConfigDefinition.getInstance();
    private static final ClientConfig clientConfig = ClientConfigDefinition.getInstance();

    public static HudRendererManager hudRendererManager;

    private static IEventBus modEventBus;

    /**
     * Forge fires {@link ModConfigEvent.Loading} once per config file, and this mod owns two of
     * them on the client (COMMON and CLIENT), so the gate below can legitimately pass more than
     * once. Forge's {@code EventBus.addListener} does not de-duplicate, so without this latch the
     * HUD render listener would be installed twice and {@code renderFrame} would run twice per
     * frame, double-blending the semi-transparent HUD background.
     */
    private static volatile boolean renderListenerRegistered;

    @SuppressWarnings("unused")
    public CommonInitializer() {
        modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.register(this);

        ModLoadingContext context = ModLoadingContext.get();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MusicHud.setCurrentEnvironment(Environment.of(Environment.Side.CLIENT, Environment.Platform.FORGE));
            context.registerConfig(ModConfig.Type.COMMON, ServerConfigDefinition.configure.getRight());
            context.registerConfig(ModConfig.Type.CLIENT, ClientConfigDefinition.configure.getRight());
            ConfigScreenFactory.register(context);
            // RegisterKeyMappingsEvent is an IModBusEvent, so the key registry has to sit on the MOD
            // event bus or Forge never delivers it and none of the shortcuts reach the controls
            // screen. Registering it here (rather than from FMLClientSetupEvent) is the earliest
            // point the mod controls, which keeps the listener in place no matter when the config
            // load ends up running Keybinds.
            modEventBus.register(ForgeKeyRegistryService.getInstance());
        } else {
            MusicHud.setCurrentEnvironment(Environment.of(Environment.Side.SERVER, Environment.Platform.FORGE));
            // Registered as COMMON, not SERVER, on purpose.
            //
            // Forge writes a ModConfig.Type.SERVER file into per-world
            // "<world>/serverconfig/music_hud-server.toml", which only exists after a world has
            // been created and loaded. A dedicated-server operator therefore cannot find any
            // config to edit before the first world starts, and the mod then runs on the built-in
            // defaults (serverApiBaseUrl=http://localhost:3000, startupBinaryApiServerWhenLaunch=
            // true) - which on a server with no local API process means a permanent
            // "cannot connect to API" stream in the console with no file to fix it in.
            // Everything in this spec is process-wide (API URLs, port, proxy flags), not per-world,
            // so COMMON is both the correct scope and the file an operator expects:
            // "config/music_hud-common.toml", the same name the client already uses.
            context.registerConfig(ModConfig.Type.COMMON, ServerConfigDefinition.configure.getRight());
        }

        // The Forge channel must exist before any payload registration or packet send; payload
        // registration itself happens later, once the config load triggers side registration.
        ForgeNetworkManager.getInstance();

        MusicHud.init();
    }

    /**
     * Draws the HUD on Forge's GUI render pass. Forge 1.20.1 exposes the same
     * {@code getGuiGraphics()}/{@code getPartialTick()} as the NeoForge event it replaces.
     */
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (hudRendererManager != null) {
            hudRendererManager.renderFrame(event.getGuiGraphics(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    void onClientSetup(FMLClientSetupEvent event) {
        // Client-side event services rely on client classes only available after client setup.
        ForgeClientEventService.getInstance();
        // The key registry is deliberately NOT registered here: Forge fires
        // RegisterKeyMappingsEvent during Minecraft's own construction, well before this event, so
        // the registry is put on the mod event bus from the mod constructor instead.
    }

    void onConfigEvent(final ModConfigEvent configEvent) {
        ModConfig config = configEvent.getConfig();
        if (config.getSpec() == ServerConfigDefinition.configure.getRight()) {
            serverConfig.setConfigured(true);
        } else if (config.getSpec() == ClientConfigDefinition.configure.getRight()) {
            clientConfig.setConfigured(true);
            hudRendererManager = HudRendererManager.getInstance();
        }
    }

    @SubscribeEvent
    void onConfigReload(final ModConfigEvent.Reloading configEvent) {
        onConfigEvent(configEvent);
    }

    @SubscribeEvent
    void onConfigLoaded(final ModConfigEvent.Loading configEvent) {
        ModConfig config = configEvent.getConfig();
        if (config.getModId().equals(MusicHud.MOD_ID)) {
            onConfigEvent(configEvent);
            boolean inClient = FMLEnvironment.dist == Dist.CLIENT;
            if ((inClient && clientConfig.isConfigured() && serverConfig.isConfigured())
                    || (!inClient && serverConfig.isConfigured())) {
                MusicHud.onConfigLoaded();
                ForgeServerEventService.getInstance();
                if (inClient) {
                    ForgeClientEventService.getInstance();
                    // Guarded: the config-load gate can pass once per registered config spec, and
                    // Forge's EventBus keeps duplicate listeners, so an unguarded addListener here
                    // renders the whole HUD twice every frame.
                    if (!renderListenerRegistered) {
                        renderListenerRegistered = true;
                        MinecraftForge.EVENT_BUS.addListener(CommonInitializer::onRenderGui);
                    }
                }
            }
        }
    }

    /**
     * Kept for parity with the NeoForge adapter; Forge 1.20.1 registers payloads eagerly through
     * {@link ForgeNetworkManager#init()} rather than through a loader event.
     */
    @SuppressWarnings("unused")
    static ModContainer modContainer() {
        return ModLoadingContext.get().getActiveContainer();
    }

    @SuppressWarnings("unused")
    private static void unused(FMLClientSetupEvent e) {
    }
}
