package indi.etern.musichud.mixin;

import indi.etern.musichud.client.audio.ExternalMusicSuppressor;
import indi.etern.musichud.client.audio.VanillaMusicSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks <b>BiomeBeats</b> / <b>Lost Beats</b> audio at the last possible moment: the
 * {@code StreamPlayer} that owns the JavaSound {@code SourceDataLine}.
 *
 * <p>The two hooks in {@link BiomeBeatsStreamPlayerMixin} sit on the mod's own
 * {@code JavaStreamPlayer} facade and cover every path that <em>opens a new track</em>. They do not
 * cover paths that <em>resume a stream that is already open</em>: BiomeBeats' {@code FadeInState}
 * calls {@code StreamPlayer.resume()} directly on the underlying player (verified against
 * {@code FadeInState.class}: its only two playback calls are {@code JavaStreamPlayer.openPlay} and
 * {@code StreamPlayer.resume}), and Lost Beats' own {@code StreamPlayerMixin} manipulates that same
 * object to seek and drive playback. When those run, nothing above {@code StreamPlayer} is entered,
 * so the facade hooks never fire and the track plays over MusicHud's.
 *
 * <p>{@code StreamPlayer.play()} and {@code StreamPlayer.resume()} are the only two methods in that
 * class that start the {@code SourceDataLine}; {@code open(Object)} merely prepares it and
 * {@code pause()} / {@code stop()} cannot start it. Both are therefore hooked here, which makes this
 * the one point no caller — current or future, BiomeBeats' or Lost Beats' or any other add-on's —
 * can route around.
 *
 * <p><b>The block stays conditional, so that mod is never disabled.</b> It cancels only while
 * {@link VanillaMusicSuppressor#shouldSuppress()} is true; once MusicHud stops, the next
 * {@code play} / {@code resume} runs untouched and their conditional-music logic resumes.
 *
 * <p>{@code priority = 2000} applies this after the default-1000 mixins. See
 * {@code MixinInfo.compareTo}, which orders by {@code this.priority - other.priority} and is
 * consumed in ascending order by {@code MixinApplicatorStandard.apply}.
 */
@Pseudo
@Mixin(targets = "io.github.maki99999.biomebeats.com.goxr3plus.streamplayer.stream.StreamPlayer", priority = 2000, remap = false)
public class BiomeBeatsStreamDeviceMixin {
    /**
     * Covers a freshly opened stream being started.
     *
     * <p>{@code require = 0} because the target is an optional third-party mod (and a shaded copy of
     * a third-party library inside it): a future version moving this class must degrade to
     * "suppression stops working" rather than to a crash on launch for everyone using that pack.
     */
    @Inject(method = "play", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void music_hud$suppressStreamPlayerPlay(CallbackInfo ci) {
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ExternalMusicSuppressor.markBiomeBeatsStopPending();
            ci.cancel();
        }
    }

    /**
     * Covers resuming an already-open stream (the {@code FadeInState} path).
     *
     * <p>Needs a returnable callback because {@code resume()} returns {@code boolean}: returning
     * {@code false} is exactly what the untouched method reports when it declines to start, so
     * callers cannot tell this apart from "the line was not paused".
     */
    @Inject(method = "resume", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void music_hud$suppressStreamPlayerResume(CallbackInfoReturnable<Boolean> cir) {
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ExternalMusicSuppressor.markBiomeBeatsStopPending();
            cir.setReturnValue(false);
        }
    }
}
