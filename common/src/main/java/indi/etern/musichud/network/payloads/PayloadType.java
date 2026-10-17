package indi.etern.musichud.network.payloads;

import net.minecraft.resources.ResourceLocation;

/**
 * Platform-neutral replacement for {@code CustomPacketPayload.Type}, which does not exist in
 * Minecraft 1.20.1. Each payload kind is identified by a {@link ResourceLocation}; platform
 * adapters map that id onto their own channel representation (Fabric
 * {@code PacketByteBuf} channels, Forge {@code SimpleChannel} message indices, ...).
 */
public record PayloadType<T extends IPayload>(ResourceLocation id) {
    @Override
    public String toString() {
        return id.toString();
    }
}
