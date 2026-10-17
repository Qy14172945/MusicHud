package indi.etern.musichud.platform.mod.fabric.network;

import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.NetworkReceiver;
import indi.etern.musichud.network.payloads.IPayload;
import indi.etern.musichud.network.vanillaUtils.VanillaPlayerProxy;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

public class FabricClientNetworkRegisterUtil {
    public static <T extends IPayload> void register(
            ResourceLocation channel,
            ByteBufCodec<T> codec,
            NetworkReceiver<T> clientReceiver
    ) {
        ClientPlayNetworking.registerGlobalReceiver(channel, (client, handler, buf, responseSender) -> {
            T payload = codec.decode(buf);
            client.execute(() -> clientReceiver.receive(payload, VanillaPlayerProxy.ofPlayer(Minecraft.getInstance().player)));
        });
    }
}
