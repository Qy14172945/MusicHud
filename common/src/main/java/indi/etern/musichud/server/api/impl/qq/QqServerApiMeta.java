package indi.etern.musichud.server.api.impl.qq;

import indi.etern.musichud.interfaces.ServerConfig;
import indi.etern.musichud.server.api.UrlMeta;

/**
 * Endpoint catalogue for the QQ Music API server ({@code @sansenjian/qq-music-api}).
 * <p>
 * Unlike the NetEase metas, QQ URLs are <b>absolute</b>: the QQ API runs as its own Node.js
 * process (default {@code http://localhost:3200}) and shares nothing with the NetEase base URL.
 * {@link UrlMeta} treats an absolute url verbatim and suppresses the NetEase-only
 * {@code randomCNIP}/{@code noCookie} query flags for it.
 * <p>
 * Every meta is built with {@code autoRetry = false} and {@code responseType = String.class} on
 * purpose. {@code ApiClient.get/post} first decodes the body as {@code {code}} and only parses the
 * payload when that code arrives or retries are disabled; QQ replies use {@code {response:{...}}}
 * or flat envelopes ({@code {img,ptqrtoken,qrsig}}) with no top-level {@code code}, so they would
 * otherwise burn all five retries with 500&nbsp;ms sleeps each.
 * <p>
 * Bodies are handed back as raw strings and parsed by {@link QqMusicApiService} /
 * {@link QqLoginApiService}, because QQ mixes several incompatible envelope shapes.
 */
public final class QqServerApiMeta {
    public static final String DEFAULT_BASE_URL = "http://localhost:3200";

    public static final String SEARCH = "/getSearchByKey";
    public static final String SMARTBOX = "/getSmartbox";
    public static final String ALBUM_INFO = "/getAlbumInfo";
    public static final String ALBUM_SONGS = "/getAlbumSongs";
    public static final String SINGER_HOTSONG = "/getSingerHotsong";
    public static final String SINGER_ALBUM = "/getSingerAlbum";
    public static final String SINGER_DESC = "/getSingerDesc";
    public static final String SONG_LIST_DETAIL = "/getSongListDetail";
    public static final String SONG_LISTS = "/getSongLists";
    public static final String LYRIC = "/getLyric";
    public static final String MUSIC_PLAY = "/getMusicPlay";
    public static final String IMAGE_URL = "/getImageUrl";
    public static final String USER_DETAIL = "/user/getUserDetail";
    public static final String USER_PLAYLISTS = "/user/getUserPlaylists";
    public static final String USER_COLLECTED_ALBUMS = "/user/getUserCollectedAlbums";
    public static final String USER_FOLLOW_SINGERS = "/user/getUserFollowSingers";
    public static final String SET_COOKIE = "/user/setCookie";
    public static final String QQ_LOGIN_QR = "/getQQLoginQr";
    public static final String CHECK_QQ_LOGIN_QR = "/checkQQLoginQr";

    /**
     * Machine-readable endpoint catalogue. This server exposes <b>no version endpoint at all</b>:
     * {@code /inner/version}, {@code /getVersion}, {@code /version}, {@code /api/version} and
     * {@code /package.json} all answer 404, the root page carries no build string, and no response
     * header names a version. The explorer document is therefore the only dependable way to tell
     * "this is a QQ API server" apart from "this address is something else".
     */
    public static final String EXPLORER_METADATA = "/explorer/metadata";

    /** Title of the QQ API root page, matched as a second identity check. */
    public static final String ROOT_TITLE_MARKER = "QQ 音乐 API";

    /** Identity marker inside {@link #EXPLORER_METADATA}. */
    public static final String EXPLORER_TITLE_MARKER = "QQ Music API";

    /**
     * Version label reported for a reachable QQ API server. It is a constant on purpose: there is
     * no version to read, and the value that matters to the user is that the server is reachable
     * rather than {@code unknown}.
     */
    public static final String VERSION_LABEL = "qq-music-api";

    private QqServerApiMeta() {
    }

    /**
     * Base url of the QQ API process as configured by the server operator.
     */
    public static String baseUrl() {
        String base = null;
        try {
            base = ServerConfig.getInstance().getQqApiBaseUrl();
        } catch (Exception ignored) {
            // Config not ready yet (very early class loading); fall through to the default.
        }
        if (base == null || base.isBlank()) {
            base = DEFAULT_BASE_URL;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * Builds a meta for {@code pathAndQuery}, which must already carry its encoded query string.
     */
    public static UrlMeta<String> text(String pathAndQuery) {
        return textAt(null, pathAndQuery);
    }

    /**
     * Builds a meta against an explicit base URL instead of the configured one.
     *
     * @param baseUrl     base to use, or {@code null}/blank for {@link #baseUrl()}. Trailing slashes
     *                    are stripped, so a user-typed {@code http://host:port/} works unchanged.
     * @param pathAndQuery path with its already-encoded query string ({@code ""} for the root page).
     */
    public static UrlMeta<String> textAt(String baseUrl, String pathAndQuery) {
        String base = baseUrl;
        if (base == null || base.isBlank()) {
            base = baseUrl();
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new UrlMeta<>(
                base + pathAndQuery,
                null,
                null,
                true,
                true,
                true,
                false,
                null,
                String.class);
    }
}
