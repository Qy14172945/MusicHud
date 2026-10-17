package indi.etern.musichud.platform.mod.fabric.network;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.network.IClientNetworkService;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.network.payloads.IPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

@SuppressWarnings("unused")
public class FabricClientNetworkService implements IClientNetworkService {
    private static volatile FabricClientNetworkService instance;

    public static FabricClientNetworkService getInstance() {
        if (instance == null) {
            synchronized (FabricClientNetworkService.class) {
                if (instance == null) {
                    instance = new FabricClientNetworkService();
                }
            }
        }
        return instance;
    }

    @Override
    public void sendToNetworkServer(C2SPayload payload) {
        INetworkRegister.PayloadCodecInfo<?> info = INetworkRegister.getCodecInfo(payload.getClass());
        if (info == null) {
            MusicHud.LOGGER.error("C2S payload {} was never registered", payload.getClass().getName());
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        encode(info, payload, buf);
        ClientPlayNetworking.send(info.type().id(), buf);
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
