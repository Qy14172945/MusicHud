package indi.etern.musichud.platform.mod.fabric.network;

import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.IPlayerClient;
import indi.etern.musichud.network.IServerNetworkService;
import indi.etern.musichud.network.payloads.IPayload;
import indi.etern.musichud.network.payloads.S2CPayload;
import indi.etern.musichud.network.vanillaUtils.VanillaPlayerProxy;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

@SuppressWarnings("unused")
public class FabricServerNetworkService implements IServerNetworkService {
    private static volatile FabricServerNetworkService instance;

    public static FabricServerNetworkService getInstance() {
        if (instance == null) {
            synchronized (FabricServerNetworkService.class) {
                if (instance == null) {
                    instance = new FabricServerNetworkService();
                }
            }
        }
        return instance;
    }

    @Override
    public void sendToNetworkPlayer(IPlayerClient playerClient, S2CPayload payload) {
        if (playerClient instanceof VanillaPlayerProxy player && player.getPlayer() instanceof ServerPlayer serverPlayer) {
            INetworkRegister.PayloadCodecInfo<?> info = INetworkRegister.getCodecInfo(payload.getClass());
            if (info == null) {
                return;
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            encode(info, payload, buf);
            ServerPlayNetworking.send(serverPlayer, info.type().id(), buf);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends IPayload> void encode(
            INetworkRegister.PayloadCodecInfo<T> info,
            IPayload payload,
            FriendlyByteBuf buf
    ) {
        info.codec().encode(buf, (T) payload);
    }
}
