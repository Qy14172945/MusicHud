package indi.etern.musichud.platform.mod.forge.event;

import indi.etern.musichud.interfaces.IClientEventService;
import indi.etern.musichud.interfaces.Unregister;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

public class ForgeClientEventService implements IClientEventService {
    private String serverIp;
    private static volatile ForgeClientEventService instance;
    private final Set<Consumer<Player>> joinListeners = new HashSet<>();
    private final Set<Consumer<Player>> quitListeners = new HashSet<>();
    private final Set<Runnable> tickPostListeners = new HashSet<>();
    private final Set<Runnable> stoppingListeners = new HashSet<>();

    private ForgeClientEventService() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override
    public Unregister registerClientPlayerJoin(Consumer<Player> listener) {
        joinListeners.add(listener);
        return () -> joinListeners.remove(listener);
    }

    @Override
    public Unregister registerClientPlayerQuit(Consumer<Player> listener) {
        quitListeners.add(listener);
        return () -> quitListeners.remove(listener);
    }

    @Override
    public Unregister registerClientTickPost(Runnable listener) {
        tickPostListeners.add(listener);
        return () -> tickPostListeners.remove(listener);
    }

    @Override
    public Unregister registerClientLifecycleStopping(Runnable listener) {
        stoppingListeners.add(listener);
        return () -> stoppingListeners.remove(listener);
    }

    @SubscribeEvent
    public void onClientPlayerJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        ServerData currentServer = Minecraft.getInstance().getCurrentServer();
        if (currentServer == null || !currentServer.ip.equals(serverIp)) {
            serverIp = currentServer == null ? null : currentServer.ip;
            joinListeners.forEach(l -> l.accept(event.getPlayer()));
        }
    }

    @SubscribeEvent
    public void onClientPlayerQuit(ClientPlayerNetworkEvent.LoggingOut event) {
        if (event.getPlayer() != null) {
            serverIp = null;
            quitListeners.forEach(q -> q.accept(event.getPlayer()));
        }
    }

    /**
     * Forge 1.20.1 has no {@code ClientTickEvent.Post}; the phase has to be checked explicitly.
     */
    @SubscribeEvent
    public void onClientTickPost(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickPostListeners.forEach(Runnable::run);
        }
    }

    @SubscribeEvent
    public void onGameShuttingDown(GameShuttingDownEvent event) {
        stoppingListeners.forEach(Runnable::run);
    }

    public static ForgeClientEventService getInstance() {
        if (instance == null) {
            synchronized (ForgeClientEventService.class) {
                if (instance == null) {
                    instance = new ForgeClientEventService();
                }
            }
        }
        return instance;
    }
}
