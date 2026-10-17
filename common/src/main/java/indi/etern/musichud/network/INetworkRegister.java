package indi.etern.musichud.network;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.network.payloads.IPayload;
import indi.etern.musichud.network.payloads.PayloadType;
import indi.etern.musichud.platform.Environment;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public interface INetworkRegister {
    record PayloadMetadata<T extends IPayload>(PayloadType<T> type, NetworkReceiver<?> receiver){}

    /**
     * Payload class to (channel id, serializer). On Minecraft 1.20.1 the platform adapters need the
     * serializer at the raw-channel level (Fabric receives a {@code FriendlyByteBuf} per channel and
     * Forge registers encode/decode per message id), so it is kept alongside the receiver metadata.
     */
    record PayloadCodecInfo<T extends IPayload>(PayloadType<T> type, ByteBufCodec<T> codec) {}

    Map<Class<? extends IPayload>, PayloadMetadata<?>> metadataMap = new ConcurrentHashMap<>();
    Map<Class<? extends IPayload>, PayloadCodecInfo<?>> codecInfoMap = new ConcurrentHashMap<>();

    static INetworkRegister getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<INetworkRegister> supplier = platform.getNetworkRegisterSupplier();
        if (supplier != null) {
            INetworkRegister networkRegister = supplier.get();
            if (networkRegister != null) {
                return networkRegister;
            }
        }
        throw new UnsupportedOperationException();
    }

    <T extends IPayload> void registerC2SPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> serverReceiver
    );

    <T extends IPayload> void registerS2CPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver
    );

    <T extends IPayload> void autoRegisterPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientOrServerReceiver
    );

    @SuppressWarnings("unchecked")
    default <T extends IPayload> PayloadMetadata<T> getMetaDataOrNew(Class<T> customPacketPayloadClass, @Nullable NetworkReceiver<T> networkReceiver) {
        return (PayloadMetadata<T>) metadataMap.computeIfAbsent(customPacketPayloadClass, clazz -> {
            if (networkReceiver == null) {
                throw new IllegalStateException("No pre-cached metadata, and networkReceiver is null");
            }
            String name = String.join("_", StringUtils.splitByCharacterTypeCamelCase(clazz.getSimpleName())).toLowerCase();
            return new PayloadMetadata<T>(new PayloadType<>(MusicHud.location(name)), networkReceiver);
        });
    }

    /**
     * Records the serializer of a payload class. Called by platform adapters during registration;
     * afterwards {@link #getCodecInfo(Class)} can resolve the channel id and codec for sending.
     */
    default <T extends IPayload> void cacheCodec(Class<T> clazz, ByteBufCodec<T> codec) {
        PayloadType<T> type = getMetaDataOrNew(clazz, null).type();
        codecInfoMap.put(clazz, new PayloadCodecInfo<>(type, codec));
    }

    @SuppressWarnings("unchecked")
    static <T extends IPayload> PayloadCodecInfo<T> getCodecInfo(Class<T> clazz) {
        return (PayloadCodecInfo<T>) codecInfoMap.get(clazz);
    }

    @SuppressWarnings("unchecked")
    static <T extends IPayload> PayloadCodecInfo<T> getCodecInfo(ResourceLocation channelId) {
        for (PayloadCodecInfo<?> info : codecInfoMap.values()) {
            if (info.type().id().equals(channelId)) {
                return (PayloadCodecInfo<T>) info;
            }
        }
        return null;
    }
}
