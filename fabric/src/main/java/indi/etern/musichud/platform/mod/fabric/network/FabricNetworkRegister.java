package indi.etern.musichud.platform.mod.fabric.network;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.NetworkReceiver;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.network.payloads.IPayload;
import indi.etern.musichud.network.payloads.PayloadType;
import indi.etern.musichud.network.payloads.S2CPayload;
import indi.etern.musichud.network.vanillaUtils.VanillaPlayerProxy;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric 1.20.1 has no {@code PayloadTypeRegistry}/{@code CustomPacketPayload} — raw
 * {@link ResourceLocation} channels carrying a {@code FriendlyByteBuf} are used instead, which
 * {@link ByteBufCodec}(ByteBuf) serialization maps onto directly.
 */
@SuppressWarnings("unused")
public class FabricNetworkRegister implements INetworkRegister {
    private static volatile FabricNetworkRegister instance;

    public static FabricNetworkRegister getInstance() {
        if (instance == null) {
            synchronized (FabricNetworkRegister.class) {
                if (instance == null) {
                    instance = new FabricNetworkRegister();
                }
            }
        }
        return instance;
    }

    @Override
    public <T extends IPayload> void registerC2SPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> serverReceiver
    ) {
        PayloadType<T> type = getMetaDataOrNew(clazz, serverReceiver).type();
        cacheCodec(clazz, codec);
        ResourceLocation channel = type.id();

        ServerPlayNetworking.registerGlobalReceiver(channel, (server, player, handler, buf, responseSender) -> {
            T payload = codec.decode(buf);
            server.execute(() -> serverReceiver.receive(payload, VanillaPlayerProxy.ofPlayer(player)));
        });
    }

    @Override
    public <T extends IPayload> void registerS2CPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver
    ) {
        PayloadType<T> type = getMetaDataOrNew(clazz, clientReceiver).type();
        cacheCodec(clazz, codec);
        ResourceLocation channel = type.id();

        if (MusicHud.getCurrentEnvironment().getSide() == indi.etern.musichud.platform.Environment.Side.CLIENT) {
            FabricClientNetworkRegisterUtil.register(channel, codec, clientReceiver);
        }
    }

    @Override
    public <T extends IPayload> void autoRegisterPayload(
            Class<T> clazz,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientOrServerReceiver
    ) {
        if (S2CPayload.class.isAssignableFrom(clazz)) {
            registerS2CPayload(clazz, codec, clientOrServerReceiver);
        } else if (C2SPayload.class.isAssignableFrom(clazz)) {
            registerC2SPayload(clazz, codec, clientOrServerReceiver);
        } else {
            throw new IllegalArgumentException("Payload class must implements S2CPayload or C2SPayload");
        }
    }
}
