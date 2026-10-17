package indi.etern.musichud.client.audio;

import indi.etern.musichud.interfaces.ClientConfig;

/**
 * Single decision point for "MusicHud currently owns the music channel, so Vanilla music must be
 * silent".
 *
 * <p>Every suppression hook (the {@code MusicManager} scheduler and the {@code SoundEngine} play
 * path) asks this class instead of re-deriving the condition, so the two can never disagree about
 * when MusicHud is in charge.
 *
 * <p>Two things about the previous implementation are deliberately different here:
 * <ul>
 *   <li>{@code isConfigured()} is no longer part of the condition. It only says "the loader has
 *       fired the config-loaded event", not "the user wants Vanilla music", and it defaults to
 *       {@code false} until that event arrives — which turned the whole feature into a no-op
 *       whenever the event was missed or fired late. {@link ClientConfig#getDisableVanillaMusic()}
 *       already defaults to {@code true}, so the user's actual choice is the only thing consulted;
 *       the "MusicHud is playing" half of the condition is what keeps this from touching the main
 *       menu.</li>
 *   <li>Any active MusicHud state counts, not just {@code PLAYING}. MusicHud spends real time in
 *       {@code BUFFERING}/{@code RETRYING} between tracks and while an uncached song downloads, and
 *       the strict {@code PLAYING} test let Vanilla music start in exactly those gaps.</li>
 * </ul>
 */
public final class VanillaMusicSuppressor {
    private static volatile ClientConfig clientConfig;

    private VanillaMusicSuppressor() {
    }

    private static ClientConfig config() {
        ClientConfig cached = clientConfig;
        if (cached != null) {
            return cached;
        }
        ClientConfig resolved = ClientConfig.getInstance();
        clientConfig = resolved;
        return resolved;
    }

    /**
     * @return whether the user asked MusicHud to keep Vanilla music out of the way at all.
     */
    public static boolean isEnabled() {
        try {
            return config().getDisableVanillaMusic();
        } catch (Throwable e) {
            // Config/platform not bound yet (very early class loading) — do not suppress.
            return false;
        }
    }

    /**
     * @return whether MusicHud is holding the music channel right now: playing, or waiting on data
     * for a track it has already committed to.
     */
    public static boolean isMusicHudActive() {
        try {
            StreamAudioPlayer.Status status = StreamAudioPlayer.getInstance().getStatus();
            return status == StreamAudioPlayer.Status.PLAYING
                    || status == StreamAudioPlayer.Status.BUFFERING
                    || status == StreamAudioPlayer.Status.RETRYING;
        } catch (Throwable e) {
            // Throwable, not Exception: StreamAudioPlayer's own static initializer calls
            // ClientConfig.getInstance(), which raises ExceptionInInitializerError if this is ever
            // reached before the loader binds the platform. A suppression probe must never be the
            // thing that crashes the client.
            return false;
        }
    }

    /** @return whether Vanilla music must be blocked from starting or kept from sounding now. */
    public static boolean shouldSuppress() {
        return isEnabled() && isMusicHudActive();
    }
}
