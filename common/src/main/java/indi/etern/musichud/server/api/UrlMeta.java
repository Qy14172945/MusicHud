package indi.etern.musichud.server.api;

import indi.etern.musichud.interfaces.ServerConfig;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record UrlMeta<T>(
        String url,
        Set<String> requiredParams,
        Set<String> optionalParams,
        boolean noCache,
        boolean noCookie,
        boolean anonymous,
        boolean autoRetry,
        Set<Integer> allowedHttpCodes,
        Class<T> responseType) {
    private static final ServerConfig serverConfig = ServerConfig.getInstance();

    /**
     * An absolute {@code url} is used verbatim, which lets a provider that lives on a different
     * host (QQ Music API on its own port) build metas without sharing the NetEase base URL.
     *
     * <p>Otherwise the base comes from {@link ApiEndpoint#effective()}, which normalises trailing
     * slashes (a trailing slash would turn {@code .../inner/version} into {@code ...//inner/version},
     * which reverse proxies commonly reject with HTTP 400) and temporarily switches to the bundled
     * server while the configured address is unreachable.
     */
    private String resolveBase() {
        return isAbsolute() ? "" : ApiEndpoint.effective();
    }

    private boolean isAbsolute() {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    @Override
    public @NotNull String toString() {
        return resolveBase() + url;
    }

    public URI toURI() {
        String uri = resolveBase() + url;
        List<String> query = new ArrayList<>();
        if (!isAbsolute()) {
            // randomCNIP / noCookie are NetEase-API-Enhanced specific query flags; the QQ Music API
            // would only echo them, so they are suppressed for absolute (foreign-provider) metas.
            if (serverConfig.getUseRandomCnIp()) {
                //noinspection SpellCheckingInspection
                query.add("randomCNIP=true");
            }
            if (noCookie) {
                query.add("noCookie=true");
            }
        }
        if (noCache) {
            query.add("timestamp=" + System.currentTimeMillis());
        }
        if (!query.isEmpty()) {
            uri += (uri.contains("?") ? "&" : "?") + String.join("&", query);
        }
        return URI.create(uri);
    }
}
