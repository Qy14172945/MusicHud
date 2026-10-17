package indi.etern.musichud.utils;

import indi.etern.musichud.MusicHud;
import org.apache.logging.log4j.Logger;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns the one environment failure that silently breaks every external download into an
 * actionable log line.
 * <p>
 * Cover art and audio are fetched by the client straight from the provider CDNs. When the JVM
 * prefers IPv6 (a launcher passing {@code -Djava.net.preferIPv6Addresses=system}) but the machine
 * has no working IPv6 route, every connection to an IPv6-capable host stalls until the connect
 * timeout — while IPv4-only hosts such as the configured API server keep working, so search and
 * login look healthy. That failure mode is indistinguishable from a broken CDN unless it is
 * spelled out, so it is reported once per session.
 */
public final class NetworkDiagnostics {
    private static final Logger LOGGER = MusicHud.getLogger(NetworkDiagnostics.class);
    private static final AtomicBoolean CONNECT_TIMEOUT_HINT_LOGGED = new AtomicBoolean();

    private static final String IPV6_HINT = """
            A timed-out connect to an IPv6-capable host while IPv4 hosts still work is the \
            signature of a JVM that prefers IPv6 on a machine without a working IPv6 route. Add \
            -Djava.net.preferIPv4Stack=true to the game's JVM arguments (or disable IPv6) to \
            restore cover art and audio streaming — other mods downloading over HTTPS are hit by \
            the same setting.""";

    private NetworkDiagnostics() {
    }

    /**
     * Reports a failed external request, logging the environment hint at most once.
     */
    public static void reportExternalFailure(String url, Throwable error) {
        if (!isConnectTimeout(error) || !CONNECT_TIMEOUT_HINT_LOGGED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warn("Connection to {} timed out ({}). {}", hostOf(url), error,
                IPV6_HINT.replace('\n', ' ').trim());
    }

    /**
     * True when the failure is a connect that never completed, as opposed to a rejected request,
     * a decoding error or a plain 404.
     */
    public static boolean isConnectTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (current instanceof SocketTimeoutException && lower.contains("connect")) {
                return true;
            }
            if (current instanceof ConnectException && lower.contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isEmpty() ? url : host;
        } catch (Exception e) {
            return url;
        }
    }
}
