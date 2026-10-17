package indi.etern.musichud.network.payloads;

import icyllis.modernui.annotation.NonNull;
import indi.etern.musichud.network.INetworkRegister;

/**
 * Platform-neutral payload root.
 * <p>
 * In Minecraft 1.21+ this interface extended {@code CustomPacketPayload} and relied on
 * {@code StreamCodec} for (de)serialization. Minecraft 1.20.1 has neither, so the payload and
 * its {@link PayloadType} identity are declared here and each platform adapter binds them to its
 * own channel mechanism (Fabric API networking v1 {@code ResourceLocation} channels, Forge
 * {@code SimpleChannel} message ids, ...). Serialization is shared through {@code ByteBufCodec}.
 */
public interface IPayload {
    @NonNull
    default PayloadType<? extends IPayload> type() {
        return INetworkRegister.getInstance().getMetaDataOrNew(getClass(), null).type();
    }
}
