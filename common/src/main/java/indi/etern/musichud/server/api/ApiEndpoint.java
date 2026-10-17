package indi.etern.musichud.server.api;

import indi.etern.musichud.interfaces.ServerConfig;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves which NetEase API server the mod should actually talk to.
 *
 * <p>There are two candidates and they can disagree:
 * <ul>
 *   <li><b>configured</b> — {@code serverApiBaseUrl}, which the user may point at a remote
 *       deployment.</li>
 *   <li><b>embedded</b> — {@code http://localhost:<port>}, the bundled binary that
 *       {@link ApiServerManager} launches itself.</li>
 * </ul>
 *
 * <p>When the configured address is unreachable the mod falls back to the embedded server. That
 * fallback has to apply to <em>traffic</em> as well as to status reporting: otherwise the manager
 * would happily keep a healthy local server running while every real request still went to the
 * dead remote address, which is exactly the "cannot connect to API" symptom this exists to fix.
 */
public final class ApiEndpoint {
    /**
     * Address to use instead of the configured one, or {@code null} while the configured address
     * is healthy. Written by the server manager and the availability probe, read by every request.
     */
    private static volatile String fallback;

    private ApiEndpoint() {
    }

    /**
     * @return the configured base URL with trailing slashes removed, or the embedded address when
     * nothing is configured. Never blank.
     */
    public static String configured() {
        String base = rawConfigured();
        if (base == null || base.isBlank()) {
            return embedded();
        }
        return trimTrailingSlashes(base);
    }

    /**
     * @return the configured base URL, or {@code null} when it is unset/blank or the config is not
     * bound yet (early bootstrap).
     */
    public static @Nullable String rawConfigured() {
        try {
            return ServerConfig.getInstance().getServerApiBaseUrl();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return the address the bundled API server binds. Falls back to the documented default port
     * when the config cannot be read.
     */
    public static String embedded() {
        int port = 3000;
        try {
            port = ServerConfig.getInstance().getPort();
        } catch (Exception ignored) {
            // Config not bound yet; use the documented default.
        }
        return "http://localhost:" + port;
    }

    /** The address requests should be sent to right now. */
    public static String effective() {
        String active = fallback;
        return active != null ? active : configured();
    }

    public static boolean isFallbackActive() {
        return fallback != null;
    }

    /** Routes traffic to the embedded server because the configured address did not answer. */
    public static void useEmbeddedFallback() {
        fallback = embedded();
    }

    /** Drops the fallback once the configured address answers again. */
    public static void clearFallback() {
        fallback = null;
    }

    public static String trimTrailingSlashes(String base) {
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        return base.substring(0, end);
    }
}
