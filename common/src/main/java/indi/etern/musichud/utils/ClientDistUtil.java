package indi.etern.musichud.utils;

import net.minecraft.client.resources.language.I18n;

/**
 * Client-only display helpers that must never drag a client-only <em>type</em> into a class a
 * dedicated server links.
 * <p>
 * This class used to expose {@code isLocalPlayer(Player)} implemented as
 * {@code player instanceof LocalPlayer}. That {@code instanceof} forced the JVM to resolve
 * {@code net.minecraft.client.player.LocalPlayer} while verifying this class, and on Forge a
 * dedicated server rejects that with
 * <pre>java.lang.RuntimeException: Attempted to load class net/minecraft/client/player/LocalPlayer
 * for invalid dist DEDICATED_SERVER</pre>
 * {@code net.minecraft.world.entity.player.Player} already declares {@code isLocalPlayer()}
 * (returning {@code false}, overridden by {@code LocalPlayer}), so the assignability test is gone
 * and callers use that method directly.
 * <p>
 * {@link #getI18n} is safe to keep: a static call performs no assignability check on
 * {@code I18n}, and it is only invoked from client-side display code.
 */
public class ClientDistUtil {
    public static String getI18n(String key, Object... objects) {
        return I18n.get(key, objects);
    }
}
