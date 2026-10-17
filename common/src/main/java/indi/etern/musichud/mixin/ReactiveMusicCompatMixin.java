package indi.etern.musichud.mixin;

import indi.etern.musichud.client.audio.VanillaMusicSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "circuitlord.reactivemusic.ReactiveMusic", remap = false)
public abstract class ReactiveMusicCompatMixin {
    @Unique
    private static boolean music_hud$reactive_music_killed = false;

    @SuppressWarnings("UnresolvedMixinReference")
    @Inject(method = "newTick", at = @At("HEAD"), cancellable = true)
    private static void onNewTick(CallbackInfo ci) {
        // Shares VanillaMusicSuppressor with the Vanilla hooks so a pack that ships Reactive Music
        // and the plain music engine can never disagree about when MusicHud owns the music channel
        // (the old local check only accepted Status.PLAYING, which let Reactive Music restart in the
        // buffering/retrying gaps between tracks).
        if (!VanillaMusicSuppressor.shouldSuppress()) {
            music_hud$reactive_music_killed = false;
            return;
        }

        if (!music_hud$reactive_music_killed) {
            music_hud$reactive_music_killed = true;
            try {
                Object thread = Class.forName("circuitlord.reactivemusic.ReactiveMusic")
                        .getField("thread").get(null);
                thread.getClass().getMethod("resetPlayer").invoke(thread);
            } catch (Exception ignored) {}
        }
        ci.cancel();
    }
}
