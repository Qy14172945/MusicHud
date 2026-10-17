package indi.etern.musichud.mixin;

import indi.etern.musichud.client.audio.VanillaMusicSuppressor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Vanilla's music <em>scheduler</em> while MusicHud owns the music channel.
 *
 * <p>{@link SoundEngineMixin} only blocks tracks that are about to start; this closes the other two
 * halves of the problem:
 * <ul>
 *   <li>a track that is <b>already playing</b> when MusicHud takes over would keep sounding,
 *       because nothing had asked it to stop — cancelling the tick and stopping the MUSIC source
 *       every client tick guarantees it is silenced as soon as MusicHud starts;</li>
 *   <li>{@code MusicManager} also drives situational music transitions (dimension change, boss
 *       fight, creative mode). Leaving it running means it keeps calling into
 *       {@code SoundManager.play} with new tracks, relying on the engine hook alone. Suppressing
 *       the scheduler removes that whole class of leak, which is what makes this work against packs
 *       that swap in their own music selection.</li>
 * </ul>
 *
 * <p>{@code Minecraft.getMusicManager()} exposes this instance, and cancelling {@code tick()} is
 * safe: the method does nothing but pick and stop music.
 */
@Mixin(MusicManager.class)
public class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void music_hud$suppressVanillaMusic(CallbackInfo ci) {
        if (!VanillaMusicSuppressor.shouldSuppress()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.getSoundManager() != null) {
            // null location + MUSIC source stops every MUSIC-source instance (SoundEngine falls
            // back to "all instances of that source" when the location is null).
            minecraft.getSoundManager().stop(null, SoundSource.MUSIC);
        }
        ci.cancel();
    }
}
