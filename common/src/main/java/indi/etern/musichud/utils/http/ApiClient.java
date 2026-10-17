package indi.etern.musichud.utils.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.client.ui.ToastUtil;
import indi.etern.musichud.interfaces.PostProcessable;
import indi.etern.musichud.interfaces.ServerConfig;
import indi.etern.musichud.platform.Environment;
import indi.etern.musichud.server.api.ApiEndpoint;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.UrlMeta;
import indi.etern.musichud.server.api.impl.ncm.ServerApiMeta;
import indi.etern.musichud.server.api.impl.qq.QqServerApiMeta;
import indi.etern.musichud.throwable.ApiException;
import indi.etern.musichud.utils.ClientDistUtil;
import indi.etern.musichud.utils.JsonUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.logging.log4j.Logger;

import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ApiClient {
    public static final HttpClient CLIENT;
    private static final int maxTrial = 5;
    @SuppressWarnings("SpellCheckingInspection")
    private static final Set<String> COOKIE_ATTRIBUTE_NAMES = Set.of(
            "max-age", "expires", "path", "domain", "secure", "httponly", "samesite"
    );
    private static final Logger LOGGER = MusicHud.getLogger(ApiClient.class);
    /**
     * Set while a liveness probe runs. A probe that fails is an expected outcome — the two-tier
     * probe deliberately treats "this address did not answer" as a branch to try the next
     * candidate — so the parse failure it provokes is logged at debug level instead of dumping a
     * full stack trace at ERROR on every probe.
     */
    private static final ThreadLocal<Boolean> QUIET_PROBE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    @Getter
    private static String version = "unknown";
    /** Whether the last probe reached an API server, whichever address that turned out to be. */
    @Getter
    private static volatile boolean available = false;
    /** Base URL that answered the last successful probe, or {@code null} while nothing answers. */
    @Getter
    private static volatile String activeBaseUrl = null;
    /**
     * QQ status triple, kept strictly separate from the NetEase one above so both platforms can be
     * connected at the same time. Sharing a single triple is what made "switch the music source to
     * QQ, then point the API address at the QQ server" break the NetEase side as well.
     */
    private static volatile boolean qqAvailable = false;
    private static volatile String qqVersion = "unknown";
    private static volatile String qqActiveBaseUrl = null;

    static {
        CLIENT = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    /**
     * Probes the configured API server, falling back to the bundled one when it does not answer.
     *
     * <p>A successful probe also updates {@link ApiEndpoint}: traffic follows whichever address
     * actually responded, so a configured-but-dead remote deployment cannot leave the mod sending
     * every request into a black hole while a healthy local server sits idle.
     */
    public static boolean checkAvailable() {
        return checkAvailable(ApiProvider.NCM);
    }

    /**
     * Probes the API server of {@code provider}.
     *
     * <p>Providers own independent addresses and independent status, so an NCM server and a QQ
     * server can both be reachable at once. Previously only the NetEase shape was understood, which
     * is why pointing the (single) API address at a QQ server reported "cannot connect" and left
     * the version at {@code unknown} even though the server answered every request.
     */
    public static boolean checkAvailable(ApiProvider provider) {
        if (provider == ApiProvider.QQ) {
            return checkQqAvailable();
        }
        String configured = ApiEndpoint.rawConfigured();
        if (configured != null && !configured.isBlank()) {
            if (checkAvailableAt(ApiEndpoint.trimTrailingSlashes(configured))) {
                ApiEndpoint.clearFallback();
                available = true;
                activeBaseUrl = ApiEndpoint.trimTrailingSlashes(configured);
                return true;
            }
        }
        if (checkAvailableAt(ApiEndpoint.embedded())) {
            ApiEndpoint.useEmbeddedFallback();
            available = true;
            activeBaseUrl = ApiEndpoint.embedded();
            return true;
        }
        ApiEndpoint.clearFallback();
        version = "unknown";
        available = false;
        activeBaseUrl = null;
        return false;
    }

    /**
     * Probes the configured QQ API server.
     *
     * <p>Unlike NetEase there is no embedded or fallback QQ server — the Node.js process is run by
     * the user — so this only ever checks the configured address.
     */
    public static boolean checkQqAvailable() {
        String configured = null;
        try {
            configured = ServerConfig.getInstance().getQqApiBaseUrl();
        } catch (Exception ignored) {
            // Config not bound yet; fall through to the documented default.
        }
        if (configured == null || configured.isBlank()) {
            configured = QqServerApiMeta.DEFAULT_BASE_URL;
        }
        String base = ApiEndpoint.trimTrailingSlashes(configured);
        QUIET_PROBE.set(Boolean.TRUE);
        try {
            if (probeQq(base)) {
                qqAvailable = true;
                qqActiveBaseUrl = base;
                return true;
            }
        } finally {
            QUIET_PROBE.remove();
        }
        qqVersion = "unknown";
        qqAvailable = false;
        qqActiveBaseUrl = null;
        return false;
    }

    /**
     * Probes one API server for liveness and version.
     *
     * @param absoluteBaseUrl absolute base URL to probe. An explicit URL lets callers verify a
     *                        specific server — notably the bundled process the manager started
     *                        itself — instead of whatever address traffic is currently routed to.
     */
    public static boolean checkAvailableAt(String absoluteBaseUrl) {
        return checkAvailableAt(absoluteBaseUrl, ApiProvider.NCM);
    }

    /**
     * Probes one API server of the given platform for liveness and version.
     *
     * @param absoluteBaseUrl absolute base URL to probe. An explicit URL lets callers verify a
     *                        specific server — notably the bundled process the manager started
     *                        itself — instead of whatever address traffic is currently routed to.
     * @param provider        which platform's envelope shape to expect.
     */
    public static boolean checkAvailableAt(String absoluteBaseUrl, ApiProvider provider) {
        QUIET_PROBE.set(Boolean.TRUE);
        try {
            return probe(absoluteBaseUrl, provider);
        } finally {
            QUIET_PROBE.remove();
        }
    }

    private static boolean probe(String absoluteBaseUrl, ApiProvider provider) {
        if (provider == ApiProvider.QQ) {
            return probeQq(absoluteBaseUrl);
        }
        try {
            var response = post(versionMeta(absoluteBaseUrl), null, null, false);
            version = response.data.version;
            return true;
        } catch (Exception e) {
            try {
                String response = get(baseMeta(absoluteBaseUrl), null, false);
                if (response.contains("NCM API Rust Server")) {// especially adapt to ncm-api-rs due to /inner/version won't work on it
                    version = "ncm-rs-api";
                    return true;
                } else if (response.contains("<title>网易云音乐 API Enhanced</title>")) {// original NodeJS api fallback
                    version = "ncm-js-api-unknown";
                    return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    /**
     * Probes a QQ API server.
     *
     * <p>The QQ server publishes no version anywhere — {@code /inner/version}, {@code /version},
     * {@code /getVersion}, {@code /api/version} and {@code /package.json} all 404, the root page
     * has no build string and no header names a version — so liveness is proven by <em>identity</em>
     * instead: the machine-readable explorer catalogue ({@code /explorer/metadata}), or failing
     * that the root page's {@code QQ 音乐 API} title.
     */
    private static boolean probeQq(String absoluteBaseUrl) {
        try {
            String metadata = get(QqServerApiMeta.textAt(absoluteBaseUrl, QqServerApiMeta.EXPLORER_METADATA), null, false);
            if (metadata != null && metadata.contains(QqServerApiMeta.EXPLORER_TITLE_MARKER)) {
                qqVersion = QqServerApiMeta.VERSION_LABEL;
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            String root = get(QqServerApiMeta.textAt(absoluteBaseUrl, ""), null, false);
            if (root != null && root.contains(QqServerApiMeta.ROOT_TITLE_MARKER)) {
                qqVersion = QqServerApiMeta.VERSION_LABEL;
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Status of {@code provider} as reported by its last probe. */
    public static boolean isAvailable(ApiProvider provider) {
        return provider == ApiProvider.QQ ? qqAvailable : available;
    }

    /** Version label of {@code provider} as reported by its last probe, or {@code unknown}. */
    public static String getVersion(ApiProvider provider) {
        return provider == ApiProvider.QQ ? qqVersion : version;
    }

    /** Base URL that answered the last successful probe of {@code provider}, or {@code null}. */
    public static String getActiveBaseUrl(ApiProvider provider) {
        return provider == ApiProvider.QQ ? qqActiveBaseUrl : activeBaseUrl;
    }

    /**
     * Absolute metas are built here rather than reusing {@link ServerApiMeta}: those are relative
     * and would resolve through {@link ApiEndpoint#effective()}, which is precisely the address
     * this probe is trying to bypass.
     */
    private static UrlMeta<ApiVersionResponse> versionMeta(String absoluteBaseUrl) {
        return new UrlMeta<>(
                ApiEndpoint.trimTrailingSlashes(absoluteBaseUrl) + "/inner/version",
                null,
                null,
                false,
                true,
                false,
                false,
                null,
                ApiVersionResponse.class);
    }

    private static UrlMeta<String> baseMeta(String absoluteBaseUrl) {
        return new UrlMeta<>(
                ApiEndpoint.trimTrailingSlashes(absoluteBaseUrl),
                null,
                null,
                true,
                true,
                true,
                false,
                null,
                String.class);
    }

    @SneakyThrows
    public static <T> T post(UrlMeta<T> urlMeta, Object requestBody, String formattedUserCookie, boolean allowAlert) {
        T t = null;
        int trial = 0;
        do {
            try {
                trial++;
                if (trial != 1) {
                    //noinspection BusyWait
                    Thread.sleep(500);
                }
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(urlMeta.toURI())
                        .setHeader("Content-Type", "application/json");
                if (requestBody != null) {
                    JsonElement payload = requestBody instanceof JsonElement element ? element : JsonUtil.gson.toJsonTree(requestBody);
                    if (payload instanceof JsonObject jsonObject) {
                        if (formattedUserCookie != null && !formattedUserCookie.isEmpty()) {
                            String cleanCookie = cleanCookie(formattedUserCookie);
                            if (!cleanCookie.isEmpty()) {
                                requestBuilder.header("Cookie", cleanCookie);
                            }
                        } else {
                            jsonObject.addProperty("noCookie", true);
                        }
                        requestBuilder.POST(HttpRequest.BodyPublishers.ofString(
                                        payload.toString(),
                                        StandardCharsets.UTF_8
                                )
                        );
                    } else {
                        throw new IllegalStateException();
                    }
                } else {
                    if (formattedUserCookie != null && !formattedUserCookie.isEmpty()) {
                        String cleanCookie = cleanCookie(formattedUserCookie);
                        if (!cleanCookie.isEmpty()) {
                            requestBuilder.header("Cookie", cleanCookie);
                        }
                    }
                    requestBuilder.POST(HttpRequest.BodyPublishers.noBody());
                }
                HttpRequest request = requestBuilder
                        .build();
                Class<?> currentlyParsing = null;
                String responseBody = null;
                try {
                    HttpResponse<?> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    responseBody = response.body().toString();
                    currentlyParsing = CodeOnlyResponse.class;
                    var codeOnlyResponse = JsonUtil.gson.fromJson(responseBody, CodeOnlyResponse.class);
                    Set<Integer> allowedHttpCodes = urlMeta.allowedHttpCodes();
                    if (allowedHttpCodes == null || allowedHttpCodes.contains(codeOnlyResponse.code) || trial == maxTrial || !urlMeta.autoRetry()) {
                        if (urlMeta.responseType().equals(String.class)) {
                            //noinspection unchecked
                            t = (T) responseBody;
                        } else {
                            currentlyParsing = urlMeta.responseType();
                            t = JsonUtil.gson.fromJson(responseBody, urlMeta.responseType());
                        }
                    }
                } catch (JsonSyntaxException e) {
                    if (QUIET_PROBE.get()) {
                        // Expected during a liveness probe: a 404/400 page is rarely JSON, and the
                        // probe treats "not the API server" as a branch, not as an error. Log the
                        // fact at debug so a real misconfiguration is still recoverable from logs.
                        LOGGER.debug("API probe: response was not JSON as:{}, original response:{}", currentlyParsing, responseBody);
                    } else {
                        LOGGER.error("Failed to parse response as:{}, original response:{}", currentlyParsing, responseBody, e);
                    }
                    throw e;
                } catch (ConnectException e) {
                    if (allowAlert) {
                        LOGGER.error("Please check Api server status | 请检查 Api 服务器状态");
                        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                            ToastUtil.show(ClientDistUtil.getI18n(MusicHud.MOD_ID + ".error.apiServer"));
                        }
                    }
                    throw e;
                }
            } catch (ConnectException e) {
                throw new ApiException(e);
            }
        } while (t == null && trial < maxTrial && urlMeta.autoRetry());
        if (t instanceof PostProcessable postProcessable) {
            postProcessable.postProcess();
        }
        return t;
    }

    @SneakyThrows
    public static <T> T get(UrlMeta<T> urlMeta, String formattedUserCookie, boolean allowAlert) {
        T t = null;
        int trial = 0;
        do {
            try {
                trial++;
                if (trial != 1) {
                    //noinspection BusyWait
                    Thread.sleep(500);
                }
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(urlMeta.toURI());
                if (formattedUserCookie != null && !formattedUserCookie.isEmpty()) {
                    String cleanCookie = cleanCookie(formattedUserCookie);
                    if (!cleanCookie.isEmpty()) {
                        requestBuilder.header("Cookie", cleanCookie);
                    }
                }
                HttpRequest request = requestBuilder
                        .GET()
                        .build();
                try {
                    HttpResponse<?> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    String string = response.body().toString();
                    var codeOnlyResponse = JsonUtil.gson.fromJson(string, CodeOnlyResponse.class);
                    if (codeOnlyResponse.code == 200 || trial == maxTrial || !urlMeta.autoRetry()) {
                        if (urlMeta.responseType().equals(String.class)) {
                            //noinspection unchecked
                            t = (T) string;
                        } else {
                            t = JsonUtil.gson.fromJson(string, urlMeta.responseType());
                        }
                    }
                } catch (ConnectException e) {
                    if (allowAlert) {
                        LOGGER.error("Please check Api server status | 请检查 Api 服务器状态");
                        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
                            ToastUtil.show(ClientDistUtil.getI18n(MusicHud.MOD_ID + ".error.apiServer"));
                        }
                    }
                    throw e;
                }
            } catch (ConnectException e) {
                throw new ApiException(e);
            }
        } while (t == null && trial < maxTrial && urlMeta.autoRetry());
        if (t instanceof PostProcessable postProcessable) {
            postProcessable.postProcess();
        }
        return t;
    }

    public static boolean checkUrlAvailable(String urlString, int timeoutMillis) {
        HttpURLConnection connection = null;
        try {
            URL url = new URI(urlString).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(false); // 不自动重定向

            int responseCode = connection.getResponseCode();
            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String cleanCookie(String rawCookie) {
        if (rawCookie == null || rawCookie.isEmpty()) {
            return "";
        }
        return Arrays.stream(rawCookie.split(";+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> {
                    int eqIdx = s.indexOf('=');
                    if (eqIdx <= 0) {
                        return false;
                    }
                    String name = s.substring(0, eqIdx).trim().toLowerCase(Locale.ROOT);
                    return !COOKIE_ATTRIBUTE_NAMES.contains(name);
                })
                .collect(Collectors.joining("; "));
    }

    public record ApiVersionResponse(ApiVersionResponseData data) {
    }

    private record ApiVersionResponseData(String version) {
    }

    private record CodeOnlyResponse(int code) {
    }
}
