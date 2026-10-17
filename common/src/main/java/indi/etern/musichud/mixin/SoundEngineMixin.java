package indi.etern.musichud.mixin;

import indi.etern.musichud.client.audio.VanillaMusicSuppressor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks music that Vanilla — or a mod/pack standing in for it — tries to start while MusicHud owns
 * the music channel.
 *
 * <p>Two entry points are hooked because {@code SoundEngine.play} is not the only way music begins:
 * {@code playDelayed} is used for delayed starts and would otherwise bypass the block.
 *
 * <p>{@link #music_hud$isVanillaMusic} deliberately does not rely on
 * {@link SoundInstance#getSource()} alone. Modpacks that "replace the vanilla music" frequently
 * re-register the same sound events under a different {@link SoundSource} instead of overriding the
 * resource pack files, and a source-only test let that music through. The sound event id is the
 * stable part of a vanilla music replacement, so {@code music.*} (music.game, music.menu,
 * music.nether.warped_forest, …) is treated as Vanilla music whatever source it declares.
 */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At("HEAD"), cancellable = true)
    private void music_hud$blockVanillaMusic(SoundInstance soundInstance, CallbackInfo ci) {
        if (music_hud$isVanillaMusic(soundInstance)) {
            ci.cancel();
        }
    }

    @Inject(method = "playDelayed(Lnet/minecraft/client/resources/sounds/SoundInstance;I)V",
            at = @At("HEAD"), cancellable = true)
    private void music_hud$blockDelayedVanillaMusic(SoundInstance soundInstance, int delay, CallbackInfo ci) {
        if (music_hud$isVanillaMusic(soundInstance)) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean music_hud$isVanillaMusic(SoundInstance soundInstance) {
        if (soundInstance == null || !VanillaMusicSuppressor.shouldSuppress()) {
            return false;
        }
        if (soundInstance.getSource() == SoundSource.MUSIC) {
            return true;
        }
        ResourceLocation location = soundInstance.getLocation();
        return location != null && location.getPath().startsWith("music.");
    }
}
