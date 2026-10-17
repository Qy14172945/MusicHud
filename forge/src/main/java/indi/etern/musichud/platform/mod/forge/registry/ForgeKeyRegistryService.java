package indi.etern.musichud.platform.mod.forge.registry;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.interfaces.IClientEventService;
import indi.etern.musichud.interfaces.IKeyRegistryService;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Forge key-binding registry.
 *
 * <p>Forge 1.20.1 has no self-registering {@code KeyMapping}: a binding only becomes visible in the
 * controls screen and {@code options.txt} once it sits in {@code Options.keyMappings}, and the only
 * thing that puts it there is {@link RegisterKeyMappingsEvent#register(KeyMapping)}. Vanilla's
 * {@code KeyMapping} constructor alone merely fills {@code KeyMapping.ALL} and Forge's
 * {@code KeyMappingLookup}, so a binding that never reaches that event is invisible to the player.
 *
 * <p>Forge fires the event from {@code ForgeHooksClient.initClientHooks} inside the {@code Minecraft}
 * constructor, while {@code Keybinds} runs whenever the mod config finishes loading on a mod-loading
 * worker thread. Neither order is guaranteed, and Forge can additionally skip the event altogether
 * when some unrelated mod is in a broken state ("Cowardly refusing to send event ... to a broken mod
 * state"). Relying on the event alone therefore loses every shortcut.
 *
 * <p>This class covers all three cases: it is placed on the mod event bus from the mod constructor
 * so the event is observed whenever it is delivered, and a client-tick reconciliation appends any
 * binding that is still missing from {@code Options.keyMappings} directly - the same mutation the
 * event performs. The reconciliation is idempotent, so a binding delivered by the event is never
 * added twice.
 */
@SuppressWarnings("unused")
public class ForgeKeyRegistryService implements IKeyRegistryService {
    private static volatile ForgeKeyRegistryService instance;
    private final Map<KeyMapping, Runnable> bindings = new LinkedHashMap<>();
    /**
     * Bindings already reported as installed, so the reconciliation logs each one only once.
     */
    private final Set<KeyMapping> reported = new HashSet<>();
    private final Object lock = new Object();

    private ForgeKeyRegistryService() {
        IClientEventService.getInstance().registerClientTickPost(() -> {
            Map<KeyMapping, Runnable> snapshot;
            synchronized (lock) {
                snapshot = new LinkedHashMap<>(bindings);
            }
            snapshot.forEach((mapping, runnable) -> {
                while (mapping.consumeClick()) {
                    runnable.run();
                }
            });
            reconcile();
        });
    }

    @Override
    public void register(KeyMapping keyMapping, Runnable action) {
        synchronized (lock) {
            bindings.put(keyMapping, action);
        }
    }

    @SubscribeEvent
    public void registerBindings(RegisterKeyMappingsEvent event) {
        Map<KeyMapping, Runnable> snapshot;
        synchronized (lock) {
            snapshot = new LinkedHashMap<>(bindings);
        }
        if (snapshot.isEmpty()) {
            // The event can arrive before the config load has created the bindings; the client-tick
            // reconciliation installs them later.
            MusicHud.LOGGER.info("Forge requested key mappings before any Music HUD binding existed; they will be installed by the client-tick reconciliation");
            return;
        }
        snapshot.keySet().forEach(event::register);
        MusicHud.LOGGER.info("Handed {} Music HUD key binding(s) to Forge's RegisterKeyMappingsEvent", snapshot.size());
    }

    /**
     * Installs any binding that never made it into {@code Options.keyMappings}, mirroring what
     * {@link RegisterKeyMappingsEvent#register(KeyMapping)} does. This is the safety net for the
     * event being split from the config load or never delivered at all.
     */
    private void reconcile() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null) {
            return;
        }
        Map<KeyMapping, Runnable> snapshot;
        synchronized (lock) {
            if (bindings.isEmpty()) {
                return;
            }
            snapshot = new LinkedHashMap<>(bindings);
        }
        for (KeyMapping mapping : snapshot.keySet()) {
            if (!appendToOptions(minecraft, mapping)) {
                continue;
            }
            boolean firstReport;
            synchronized (lock) {
                firstReport = reported.add(mapping);
            }
            if (firstReport) {
                MusicHud.LOGGER.info("Installed Music HUD key binding {} into Options.keyMappings", mapping.getName());
            }
        }
    }

    /**
     * @return whether the binding is present in {@code Options.keyMappings} afterwards
     */
    private static boolean appendToOptions(Minecraft minecraft, KeyMapping keyMapping) {
        KeyMapping[] current = minecraft.options.keyMappings;
        if (current == null) {
            minecraft.options.keyMappings = new KeyMapping[]{keyMapping};
            return true;
        }
        for (KeyMapping existing : current) {
            if (existing == keyMapping) {
                return false;
            }
        }
        KeyMapping[] extended = Arrays.copyOf(current, current.length + 1);
        extended[current.length] = keyMapping;
        minecraft.options.keyMappings = extended;
        return true;
    }

    public static ForgeKeyRegistryService getInstance() {
        if (instance == null) {
            synchronized (ForgeKeyRegistryService.class) {
                if (instance == null) {
                    instance = new ForgeKeyRegistryService();
                }
            }
        }
        return instance;
    }
}
