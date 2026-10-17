package indi.etern.musichud.client.utils;

import indi.etern.musichud.interfaces.ClientRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.vanillaUtils.VanillaPlayerProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Installs the client-only source of the local {@link Player} into the server-loadable
 * {@link VanillaPlayerProxy}.
 * <p>
 * This class is listed in {@code RegistrationManager.CLIENT_REGISTRIES}, so it is loaded on the
 * client only. Keeping the {@code Minecraft} reference here is what lets
 * {@link VanillaPlayerProxy} stay free of client-only types while remaining usable on a dedicated
 * server, where naming such a type makes Forge's {@code RuntimeDistCleaner} abort the server.
 */
@RegisterMark
public class ClientLocalPlayerInstaller implements ClientRegister {
    public void register() {
        VanillaPlayerProxy.setLocalPlayerSupplier(() -> Minecraft.getInstance().player);
    }
}