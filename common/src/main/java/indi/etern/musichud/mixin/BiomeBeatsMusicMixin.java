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
 * <p><b>The block is conditional, so the mod is never disabled.</b> {@code play} is cancelled only
 * while {@link VanillaMusicSuppressor#shouldSuppress()} is true — i.e. only while a MusicHud track is
 * playing, buffering or retrying. The moment MusicHud stops, the next {@code play} call from
 * BiomeBeats or Lost Beats runs untouched and the mod's own conditional-music logic takes over
 * again. Nothing in its state machine is switched off, skipped or frozen from here.
 *
 * <p>Cancelling at {@code HEAD} rather than letting the track start and stopping it afterwards is
 * deliberate: it is the only point where no audio has been submitted to the device yet, so there is
 * nothing to hear and no decoder to tear down.
 *
 * <p>The other half of the suppression lives in {@link BiomeBeatsTickMixin}, which runs
 * <em>after</em> BiomeBeats and Lost Beats have finished their own client tick and stops a track
 * that was already sounding. The two hooks are split across two classes because they target two
 * different classes of that mod.
 *
 * <p>{@code priority = 2000} places this above the default 1000, so it applies after the other
 * mixins touching {@code MusicManager} — including Lost Beats' {@code MusicManagerMixin} and its
 * {@code MusicManagerAccessor} — instead of racing them for the same method. {@code @Pseudo} marks
 * the target as optional, and {@code MusicHudMixinPlugin} additionally skips this mixin on packs
 * without BiomeBeats, so MusicHud keeps loading everywhere.
 */
@Pseudo
@Mixin(targets = "io.github.maki99999.biomebeats.music.MusicManager", priority = 2000, remap = false)
public class BiomeBeatsMusicMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true, remap = false)
    private void music_hud$suppressBiomeBeatsTrack(CallbackInfo ci) {
        // Keep the manager reference fresh across reloads and arm the "stop what is sounding" half
        // in BiomeBeatsTickMixin. Doing it before the guard matters: a track started while MusicHud
        // was idle still has to be stoppable once MusicHud takes over.
        ExternalMusicSuppressor.trackBiomeBeatsMusicManager(this);
        if (VanillaMusicSuppressor.shouldSuppress()) {
            ci.cancel();
        }
    }
}
