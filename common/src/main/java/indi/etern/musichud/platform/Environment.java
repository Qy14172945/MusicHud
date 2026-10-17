package indi.etern.musichud.platform;

import indi.etern.musichud.interfaces.*;
import indi.etern.musichud.network.IClientNetworkService;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.IServerNetworkService;
import lombok.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Supplier;

@Getter
@Setter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Environment {
    private Side side;
    private Platform platform;

    public static Environment of(Side side, Platform platform) {
        return new Environment(side, platform);
    }

    public enum Side {
        CLIENT,
        SERVER
    }

    @Getter
    public enum Platform {
        FABRIC(
                () -> load("indi.etern.musichud.platform.mod.forgeConfig.config.ServerConfigDefinition", ServerConfig.class),
                () -> load("indi.etern.musichud.platform.mod.forgeConfig.config.ClientConfigDefinition", ClientConfig.class),
                () -> load("indi.etern.musichud.platform.mod.fabric.network.FabricNetworkRegister", INetworkRegister.class),
                () -> load("indi.etern.musichud.platform.mod.fabric.network.FabricServerNetworkService", IServerNetworkService.class),
                () -> load("indi.etern.musichud.platform.mod.fabric.network.FabricClientNetworkService", IClientNetworkService.class),
                () -> load("indi.etern.musichud.platform.mod.fabric.event.FabricServerEventService", IServerEventService.class),
                () -> load("indi.etern.musichud.platform.mod.fabric.event.FabricClientEventService", IClientEventService.class),
                () -> load("indi.etern.musichud.platform.mod.fabric.registry.FabricKeyRegistryService", IKeyRegistryService.class)),
        FORGE(
                () -> load("indi.etern.musichud.platform.mod.forgeConfig.config.ServerConfigDefinition", ServerConfig.class),
                () -> load("indi.etern.musichud.platform.mod.forgeConfig.config.ClientConfigDefinition", ClientConfig.class),
                () -> load("indi.etern.musichud.platform.mod.forge.network.ForgeNetworkManager", INetworkRegister.class),
                () -> load("indi.etern.musichud.platform.mod.forge.network.ForgeNetworkManager", IServerNetworkService.class),
                () -> load("indi.etern.musichud.platform.mod.forge.network.ForgeClientNetworkService", IClientNetworkService.class),
                () -> load("indi.etern.musichud.platform.mod.forge.event.ForgeServerEventService", IServerEventService.class),
                () -> load("indi.etern.musichud.platform.mod.forge.event.ForgeClientEventService", IClientEventService.class),
                () -> load("indi.etern.musichud.platform.mod.forge.registry.ForgeKeyRegistryService", IKeyRegistryService.class));

        private final Supplier<ServerConfig> serverConfigSupplier;
        private final Supplier<ClientConfig> clientConfigSupplier;
        private final Supplier<IServerNetworkService> serverNetworkServiceSupplier;
        private final Supplier<IClientNetworkService> clientNetworkServiceSupplier;
        private final Supplier<IServerEventService> serverEventServiceSupplier;
        private final Supplier<IClientEventService> clientEventServiceSupplier;
        private final Supplier<IKeyRegistryService> keyRegistryServiceSupplier;
        private final Supplier<INetworkRegister> networkRegisterSupplier;

        Platform(
                Supplier<ServerConfig> serverConfigSupplier,
                Supplier<ClientConfig> clientConfigSupplier,
                Supplier<INetworkRegister> networkRegisterSupplier,
                Supplier<IServerNetworkService> serverNetworkServiceSupplier,
                Supplier<IClientNetworkService> clientNetworkServiceSupplier,
                Supplier<IServerEventService> serverEventServiceSupplier,
                Supplier<IClientEventService> clientEventServiceSupplier,
                Supplier<IKeyRegistryService> keyRegistryServiceSupplier
        ) {
            this.serverConfigSupplier = serverConfigSupplier;
            this.clientConfigSupplier = clientConfigSupplier;
            this.networkRegisterSupplier = networkRegisterSupplier;
            this.serverNetworkServiceSupplier = serverNetworkServiceSupplier;
            this.clientNetworkServiceSupplier = clientNetworkServiceSupplier;
            this.serverEventServiceSupplier = serverEventServiceSupplier;
            this.clientEventServiceSupplier = clientEventServiceSupplier;
            this.keyRegistryServiceSupplier = keyRegistryServiceSupplier;
        }

        @SneakyThrows
        static <T> T load(String className, Class<T> expectedType) {
            Class<?> clazz = Class.forName(className);
            Object instance = MethodHandles.lookup()
                    .findStatic(clazz, "getInstance", MethodType.methodType(clazz))
                    .invoke();
            return expectedType.cast(instance);
        }
    }

    @Override
    public String toString() {
        return "Environment{" + platform.name() + "-" + side.name() + "}";
    }
}
