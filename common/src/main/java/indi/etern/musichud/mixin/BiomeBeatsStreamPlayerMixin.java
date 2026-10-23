package indi.etern.musichud.mixin;

import indi.etern.musichud.client.audio.ExternalMusicSuppressor;
import indi.etern.musichud.client.audio.VanillaMusicSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks <b>BiomeBeats</b> / <b>Lost Beats</b> audio at the point where it actually reaches the
 * audio device, instead of at the per-caller methods.
 *
 * <p>{@link BiomeBeatsMusicMixin} cancels {@code MusicManager.play(MusicTrack)}, which covers the
 * case where something asks that mod to play a specific track. It does <em>not</em> cover the much
 * more common case: {@code MusicManager.playNext(boolean)} picks the next track from the current
 * conditions itself and calls {@code JavaStreamPlayer.play(...)} directly, so cancelling
 * {@code MusicManager.play} lets conditional music start anyway.
 *
 * <p>{@code JavaStreamPlayer} is the single class every playback path in that mod funnels through —
 * {@code MusicManager.play}, {@code playNext}, the player state machine ({@code IdleState} /
 * {@code FadeInState} / {@code MusicState} / {@code WaitingBreakState}) and Lost Beats' smart
 * transition system all end up in {@code openPlay(MusicTrack)}, whose only job is to feed a
 * {@code StreamPlayer} and start it. Hooking there is therefore independent of <em>who</em> asked
 * for the track, and independent of which of the two mods did the asking.
 *
 * <p><b>The block is still conditional, so that mod is never disabled.</b> Cancelling happens only
 * while {@link VanillaMusicSuppressor#shouldSuppress()} is true — i.e. only while a MusicHud track
 * is playing, buffering or retrying. As soon as MusicHud stops, the next {@code openPlay} runs
 * untouched and its own conditional-music logic resumes with no state having been skipped here.
 *
 * <p>{@code priority = 2000} applies this after the default-1000 mixins, so Lost Beats' accessors
 * and overrides are already in place when these hooks are woven in. See
 * {@code MixinInfo.compareTo}, which orders by {@code this.priority - other.priority} and is
 * consumed in ascending order by {@code MixinApplicatorStandard.apply}.
 */
@Pseudo
@Mixin(targets = "io.github.maki99999.biomebeats.music.statemachine.JavaStreamPlayer", priority = 2000, remap = false)
public class BiomeBeatsStreamPlayerMixin {
    /**
     * Covers direct requests for a track ({@code MusicManager.play}, {@code playNext}).
     *
     * <p>{@code require = 0} because the target is an optional third-party mod: a future BiomeBeats
     * renaming this method must degrade to "suppression stops working" rather than to a crash on
     * launch for every player of that pack. {@code openPlay} below is the hook that actually
     * guarantees silence, so losing this one alone is survivable.
     */
    @Inject(method = "play", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void music_hud$suppressJavaStreamPlayerPlay(CallbackInfo ci) {
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ExternalMusicSuppressor.markBiomeBeatsStopPending();
            ci.cancel();
        }
    }

    /**
     * Covers the actual audio submission point. Every state-machine transition that starts sound
     * goes through here, so this is the hook that cannot be routed around.
     *
     * <p>{@code require = 0} for the same reason as above.
     */
    @Inject(method = "openPlay", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void music_hud$suppressJavaStreamPlayerOpenPlay(CallbackInfo ci) {
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ExternalMusicSuppressor.markBiomeBeatsStopPending();
            ci.cancel();
        }
    }
}
