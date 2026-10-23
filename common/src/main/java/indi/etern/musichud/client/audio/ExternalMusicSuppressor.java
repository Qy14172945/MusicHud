package indi.etern.musichud.client.audio;

import indi.etern.musichud.MusicHud;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Cuts off music that <em>other mods</em> play through their own audio stack.
 *
 * <p>Vanilla music is reachable from Minecraft's {@code SoundEngine}/{@code MusicManager}, which is
 * what {@link VanillaMusicSuppressor} and the two mixins in this package cover. Modded music is not:
 * <b>BiomeBeats</b> (and its add-on <b>Lost Beats</b>) decode and play their own audio through
 * {@code com.goxr3plus.streamplayer.stream.StreamPlayer} / JavaSound, never touching
 * {@code SoundEngine}. No amount of SoundEngine hooking can silence them — the hook has to be in
 * the mod that owns the player.
 *
 * <p>So the two {@code BiomeBeats*} mixins cancel that mod's {@code play(...)} while MusicHud is
 * active and hook the tail of its client tick, and this class performs the "stop what is already
 * sounding" half. No part of that mod's tick is cancelled, so its conditional-music logic resumes on
 * its own as soon as MusicHud stops. It is deliberately a plain
 * class holding the manager as {@link Object} and invoking {@code stop()} reflectively: MusicHud
 * must keep compiling and loading on servers and packs where BiomeBeats is not installed at all,
 * so it can never name that mod's types in its own code.
 */
public final class ExternalMusicSuppressor {
    private static final Logger LOGGER = MusicHud.getLogger(ExternalMusicSuppressor.class);

    /**
     * The live {@code io.github.maki99999.biomebeats.music.MusicManager} instance, recorded the first
     * time its {@code play} ran. Held as {@link Object} on purpose — see the class javadoc.
     */
    private static volatile Object biomeBeatsMusicManager;
    private static volatile Method biomeBeatsStop;
    private static volatile boolean biomeBeatsStopUnavailable;
    /**
     * Set when that mod tries to start a track (or started one before MusicHud took over) and
     * cleared once it has been stopped. Without it the client tick would call {@code stop()} twenty
     * times a second on an already-silent player.
     */
    private static volatile boolean biomeBeatsStopPending;

    private ExternalMusicSuppressor() {
    }

    /**
     * Remembers the BiomeBeats music manager so it can be stopped later, and arms the next stop.
     * Called from the mixin on every {@code play}, so the reference is never stale after a reload.
     */
    public static void trackBiomeBeatsMusicManager(Object musicManager) {
        if (musicManager != null) {
            biomeBeatsMusicManager = musicManager;
        }
        biomeBeatsStopPending = true;
    }

    /**
     * Stops whatever BiomeBeats is currently playing, once per started/attempted track. Safe to call
     * on any client tick and on any platform: it is a no-op when nothing was started since the last
     * call, and it never throws.
     */
    public static void stopExternalMusic() {
        Object musicManager = biomeBeatsMusicManager;
        if (musicManager == null || !biomeBeatsStopPending) {
            return;
        }
        biomeBeatsStopPending = false;
        try {
            Method stop = biomeBeatsStop;
            if (stop == null) {
                if (biomeBeatsStopUnavailable) {
                    return;
                }
                stop = musicManager.getClass().getMethod("stop");
                biomeBeatsStop = stop;
            }
            stop.invoke(musicManager);
        } catch (Throwable e) {
            // A signature change in a future BiomeBeats must degrade to "cannot suppress" rather
            // than to a crash loop on every tick.
            biomeBeatsStopUnavailable = true;
            LOGGER.warn("Cannot stop BiomeBeats music playback ({}). MusicHud will not be able to " +
                    "silence tracks that mod starts.", e.toString());
        }
    }
}
