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
 * channel, and stops that mod from scheduling the next one.
 *
 * <p>Cancelling {@code play} in {@link BiomeBeatsMusicMixin} only covers tracks that have not
 * started yet. Without this hook a song that began before the user requested a MusicHud track keeps
 * sounding over it — the "在游戏内音乐已经被播放了再点歌后无法切断音乐" case — because BiomeBeats owns
 * its own decoder and never consults Minecraft's sound engine about it.
 *
 * <p>{@code BiomeBeatsCommon.tick()} is the mod's client-tick entry point: it advances its condition
 * state machine and its {@code TickListener}s (Lost Beats registers several). Cancelling it while
 * MusicHud is playing freezes that machine instead of letting it fight for the channel, and the
 * explicit reflective {@code stop()} releases the audio device so the already-sounding track ends
 * immediately.
 */
@Pseudo
@Mixin(targets = "io.github.maki99999.biomebeats.BiomeBeatsCommon", remap = false)
public class BiomeBeatsTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void music_hud$suppressBiomeBeatsTick(CallbackInfo ci) {
        if (!VanillaMusicSuppressor.shouldSuppress()) {
            return;
        }
        ExternalMusicSuppressor.stopExternalMusic();
        ci.cancel();
    }
}
