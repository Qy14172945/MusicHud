package indi.etern.musichud.platform.mod.forge.event;

import indi.etern.musichud.interfaces.IServerEventService;
import indi.etern.musichud.interfaces.Unregister;
import indi.etern.musichud.network.IPlayerClient;
import indi.etern.musichud.network.vanillaUtils.VanillaPlayerProxy;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class ForgeServerEventService implements IServerEventService {
    private static volatile ForgeServerEventService instance;
    private final Set<Consumer<IPlayerClient>> disconnectListeners = new HashSet<>();
    private final Set<Runnable> stoppingListeners = new HashSet<>();

    private ForgeServerEventService() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public Unregister registerCommonPlayerQuit(Consumer<IPlayerClient> listener) {
        disconnectListeners.add(listener);
        return () -> {
            disconnectListeners.remove(listener);
        };
    }

    @Override
    public Unregister registerServerLifecycleStopping(Runnable listener) {
        stoppingListeners.add(listener);
        return () -> {
            stoppingListeners.remove(listener);
        };
    }

    @SubscribeEvent
    public void onPlayerQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof Player player) {
            disconnectListeners.forEach(d -> d.accept(VanillaPlayerProxy.ofPlayer(player)));
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        stoppingListeners.forEach(Runnable::run);
    }

    public static ForgeServerEventService getInstance() {
        if (instance == null) {
            synchronized (ForgeServerEventService.class) {
                if (instance == null) {
                    instance = new ForgeServerEventService();
                }
            }
        }
        return instance;
    }
}
