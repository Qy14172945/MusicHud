package indi.etern.musichud.mixin;

import indi.etern.musichud.client.audio.ExternalMusicSuppressor;
import indi.etern.musichud.client.audio.VanillaMusicSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks <b>BiomeBeats</b> from starting a music track while MusicHud owns the music channel.
 *
 * <p>BiomeBeats ({@code io.github.maki99999.biomebeats}) is the mod that plays situational music in
 * modpacks — and it does so through its own JavaSound/StreamPlayer pipeline, not through
 * Minecraft's {@code SoundEngine}. That is why hooking {@code SoundEngine.play} and
 * {@code MusicManager.tick} leaves it audible, and why a pack can look like it "replaced the vanilla
 * music" with no obvious mod responsible.
 *
 * <p>Two hooks are needed and they are split across two classes on purpose:
 * <ul>
 *   <li><b>here</b> — cancelling {@code play(MusicTrack)} is what prevents <em>new</em> tracks from
 *       starting; the injection also records the manager instance so an already-sounding track can
 *       be stopped;</li>
 *   <li>{@link BiomeBeatsTickMixin} — cancels that mod's client tick and stops the current track,
 *       which silences a song that was already playing when the user requested one.</li>
 * </ul>
 *
 * <p>{@code @Pseudo} marks the target as optional, and {@code MusicHudMixinPlugin} additionally
 * skips this mixin on packs without BiomeBeats, so MusicHud keeps loading everywhere. Lost Beats,
 * the popular BiomeBeats add-on, drives the same {@code MusicManager} through an extension
 * interface, so it is covered by the same hook.
 */
@Pseudo
@Mixin(targets = "io.github.maki99999.biomebeats.music.MusicManager", remap = false)
public class BiomeBeatsMusicMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true, remap = false)
    private void music_hud$suppressBiomeBeatsTrack(CallbackInfo ci) {
        ExternalMusicSuppressor.trackBiomeBeatsMusicManager(this);
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ci.cancel();
        }
    }
}
