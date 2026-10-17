package indi.etern.musichud.network.vanillaUtils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import indi.etern.musichud.network.IPlayerClient;
import lombok.Getter;
import lombok.SneakyThrows;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Wraps a vanilla {@link Player} as an {@link IPlayerClient}.
 * <p>
 * This class is loaded on a dedicated server: the player-quit event wraps every logging-out
 * player, and the C2S packet handler wraps every sending player. It therefore must not name any
 * client-only type — neither in a method body nor in a field initializer.
 * <p>
 * It used to reference two. {@code ClientDistUtil.isLocalPlayer(player)} forced the verifier to
 * resolve {@code net.minecraft.client.player.LocalPlayer}, and {@code ofLocalPlayer()} named
 * {@code net.minecraft.client.Minecraft}. On Forge the dist cleaner aborts the whole server tick
 * loop on the first one:
 * <pre>java.lang.RuntimeException: Attempted to load class net/minecraft/client/player/LocalPlayer
 * for invalid dist DEDICATED_SERVER</pre>
 * That single throw covered two completely different user-visible failures, because it fired both
 * on player logout (server crash) and while handling an incoming {@code ConnectRequest} — the
 * latter being swallowed by {@code ServerDataPacketVThreadExecutor}, so the client never received
 * its {@code ConnectResponse} and "connecting" silently did nothing.
 * <p>
 * Both references are gone: {@link Player#isLocalPlayer()} is declared on the common {@code Player}
 * class (false there, overridden by {@code LocalPlayer}), so it needs no client class, and the
 * local player is supplied by client-only code through {@link #setLocalPlayerSupplier}.
 */
public class VanillaPlayerProxy implements IPlayerClient {
    @Getter
    private final Player player;

    private static final Cache<Player, VanillaPlayerProxy> playerProxyCache = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();

    /**
     * Set once by client-only code; deliberately a {@link Supplier} so this server-loadable class
     * never names {@code Minecraft} itself.
     */
    private static volatile Supplier<Player> localPlayerSupplier;

    private final ClientType clientType;

    private VanillaPlayerProxy(Player player) {
        this.player = player;
        clientType = player != null && player.isLocalPlayer() ? ClientType.LOCAL : ClientType.REMOTE;
    }

    /**
     * Installs the source of the client's own player. Called from client-only registration, so the
     * lambda (and the {@code Minecraft} reference it captures) is never loaded on a dedicated
     * server.
     */
    public static void setLocalPlayerSupplier(@Nullable Supplier<Player> supplier) {
        localPlayerSupplier = supplier;
    }

    @SneakyThrows
    public static VanillaPlayerProxy ofPlayer(Player player) {
        return playerProxyCache.get(player, () -> new VanillaPlayerProxy(player));
    }

    /**
     * Resolves the proxy for the client's own player. Forge 1.20.1's S2C packet context does not
     * expose the receiving player, so client-side receivers fall back to this.
     */
    public static VanillaPlayerProxy ofLocalPlayer() {
        Supplier<Player> supplier = localPlayerSupplier;
        if (supplier == null) {
            throw new IllegalStateException("Local player supplier is not installed yet (client registration has not run)");
        }
        return ofPlayer(supplier.get());
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public String getName() {
        return player.getName().getString();
    }

    @Override
    public ClientType getClientType() {
        return clientType;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof VanillaPlayerProxy vanillaPlayerProxy && player.getUUID().equals(vanillaPlayerProxy.getUUID());
    }

    @Override
    public int hashCode() {
        return getUUID().hashCode();
    }
}
