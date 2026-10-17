package indi.etern.musichud.forge;

import indi.etern.musichud.client.ui.screen.MainFragment;
import indi.etern.musichud.client.ui.screen.MusicHudScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("unused")
public final class ConfigScreenFactory {
    private ConfigScreenFactory() {
    }

    @NotNull
    public static Screen create(@Nullable Screen modListScreen) {
        var fragment = new MainFragment();
        fragment.setDefaultSelectedIndex(3); // Setting page
        return MusicHudScreen.createScreen(fragment, null, modListScreen, "Music HUD");
    }

    /**
     * Registers the mod-list config screen. This lives here, and not in
     * {@link CommonInitializer}, on purpose: Forge's EventBus reflects over every declared
     * method of a class passed to {@code IEventBus.register(Object)}, and a lambda written
     * inside {@code CommonInitializer} would become a declared synthetic method whose
     * {@code net.minecraft.client.Minecraft} parameter is resolved on the dedicated server
     * too, where {@code RuntimeDistCleaner} strips that class and aborts mod loading.
     */
    public static void register(ModLoadingContext context) {
        context.registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (minecraft, modListScreen) -> create(modListScreen)));
    }
}
