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
     * Set once the {@code Constants.MUSIC_MANAGER} fallback has failed, so a pack without that field
     * is not re-probed on every tick.
     */
    private static volatile boolean biomeBeatsManagerResolveUnavailable;
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
     * Arms the next {@link #stopExternalMusic()} without touching the manager reference.
     *
     * <p>Used by the hooks on BiomeBeats' {@code JavaStreamPlayer}, which sits below
     * {@code MusicManager} and therefore has no manager instance to hand over. It still has to arm
     * the stop, because a track may already be sounding in the underlying stream player (queued by
     * {@code playNext}, or started before MusicHud took over) even though the {@code play} call
     * this hook just cancelled never completed.
     */
    public static void markBiomeBeatsStopPending() {
        biomeBeatsStopPending = true;
    }

    /**
     * Returns the BiomeBeats music manager, falling back to that mod's {@code Constants.MUSIC_MANAGER}
     * singleton when we have not seen a {@code play} call yet.
     *
     * <p>The fallback matters for the JavaSound path: when BiomeBeats starts its very first track
     * through {@code playNext} rather than {@code play}, nothing has ever handed us the manager
     * instance, so the reflective {@code stop()} below would have nothing to invoke and the stop
     * would be silently dropped while the track kept sounding.
     *
     * <p>Resolving a field of that mod's {@code Constants} class does load it, which is only safe
     * outside mixin prepare — and this runs from a client tick, long after every mixin is applied.
     * The lookup is attempted once and the outcome cached either way.
     */
    private static Object resolveBiomeBeatsMusicManager() {
        Object known = biomeBeatsMusicManager;
        if (known != null) {
            return known;
        }
        if (biomeBeatsManagerResolveUnavailable) {
            return null;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> constants = Class.forName("io.github.maki99999.biomebeats.Constants", true, loader);
            Object instance = constants.getField("MUSIC_MANAGER").get(null);
            if (instance != null) {
                biomeBeatsMusicManager = instance;
            }
            return instance;
        } catch (Throwable e) {
            biomeBeatsManagerResolveUnavailable = true;
            return null;
        }
    }

    /**
     * Stops whatever BiomeBeats is currently playing, once per started/attempted track. Safe to call
     * on any client tick and on any platform: it is a no-op when nothing was started since the last
     * call, and it never throws.
     */
    public static void stopExternalMusic() {
        if (!biomeBeatsStopPending) {
            return;
        }
        Object musicManager = resolveBiomeBeatsMusicManager();
        if (musicManager == null) {
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
