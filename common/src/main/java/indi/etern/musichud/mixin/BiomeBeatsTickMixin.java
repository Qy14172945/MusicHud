package indi.etern.musichud.mixin;

import indi.etern.musichud.client.audio.ExternalMusicSuppressor;
import indi.etern.musichud.client.audio.VanillaMusicSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silences a BiomeBeats track that is <em>already playing</em> when MusicHud takes over the music
 * channel, without switching that mod off.
 *
 * <p>{@code BiomeBeatsCommon.tick()} is the mod's client-tick entry point: it advances its condition
 * state machine, reads its own key mappings and runs every registered {@code TickListener} - Lost
 * Beats registers several of them exactly there. This hook used to run at {@code HEAD} and cancel
 * the whole method while MusicHud was playing. That did keep the music away, but it also froze the
 * entire machine: conditions stopped being evaluated, Lost Beats' listeners never ran, and after
 * playback ended the mod could sit in a state it would never have reached on its own.
 *
 * <p>It therefore runs at {@code RETURN} now. BiomeBeats and its add-ons get to run their own tick
 * logic - including "decide whether to start a track" - and whatever they started is stopped
 * <em>afterwards</em>. Nothing is cancelled, so once MusicHud stops playing the very next tick
 * simply has nothing to silence and the conditional-music logic resumes by itself.
 *
 * <p>{@code priority = 2000} sits <em>above</em> the default 1000 on purpose, so this mixin is
 * transformed <em>after</em> every default-priority mixin that BiomeBeats and Lost Beats ship: we
 * modify the class only once they have finished modifying it, instead of racing them for the same
 * method. (Mixin applies lower priority first — {@code MixinInfo.compareTo} returns
 * {@code this.priority - other.priority} and {@code MixinApplicatorStandard.apply} walks that sorted
 * set in ascending order.) Combined with {@code @At("RETURN")} this makes our callback the last
 * thing to run in the tick: their logic decides what plays, ours only silences what they started.
 * The audible start is still prevented slightly earlier by {@link BiomeBeatsMusicMixin}, which
 * cancels {@code MusicManager.play(...)} at {@code HEAD} — so no track is ever heard even inside the
 * one-tick window this hook covers.
 */
@Pseudo
@Mixin(targets = "io.github.maki99999.biomebeats.BiomeBeatsCommon", priority = 2000, remap = false)
public class BiomeBeatsTickMixin {
    @Inject(method = "tick", at = @At("RETURN"), remap = false)
    private static void music_hud$silenceBiomeBeatsAfterTick(CallbackInfo ci) {
        if (!VanillaMusicSuppressor.shouldSuppress()) {
            return;
        }
        // Reaches the mod through reflection on its own public stop(); see ExternalMusicSuppressor
        // for why MusicHud must never name BiomeBeats types directly.
        ExternalMusicSuppressor.stopExternalMusic();
    }
}
