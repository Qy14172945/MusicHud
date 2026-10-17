package indi.etern.musichud.server.api.impl.qq;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.api.SearchType;
import indi.etern.musichud.beans.music.*;
import indi.etern.musichud.beans.user.Profile;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.ILoginApiService;
import indi.etern.musichud.server.api.IMusicApiService;
import indi.etern.musichud.server.api.MusicIdRegistry;
import indi.etern.musichud.server.api.UrlMeta;
import indi.etern.musichud.utils.JsonUtil;
import indi.etern.musichud.utils.http.ApiClient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * QQ Music implementation of {@link IMusicApiService}, backed by the standalone
 * {@code @sansenjian/qq-music-api} Node.js process.
 * <p>
 * <b>Identity model.</b> QQ Music addresses songs/albums/singers by <i>MID</i> strings
 * ({@code songmid}/{@code albummid}/{@code singermid}/{@code disstid}) and its
 * {@code /getSongInfo}, {@code /getLyric} and {@code /getMusicPlay} endpoints reject the numeric
 * {@code songid} outright (HTTP 400 / {@code code:500001}). MusicHud's shared bean model is keyed
 * by a numeric {@code long id}, so this service keeps a bidirectional map: every entity is
 * registered in a {@code long -> MID} cache, and the beans carry the MID in
 * {@link MusicDetail#getExternalId()}. Numeric ids come from the QQ payload when it provides one
 * ({@code songid}/{@code albumid}/{@code singer.id}/{@code dissid}); when it does not, a stable
 * 63-bit hash of the MID is used so that the client-side response routers (which match on the
 * numeric id) stay deterministic.
 * <p>
 * <b>Envelope handling.</b> QQ mixes {@code {response:{...}}}, flat ({@code {img,...}}) and
 * {@code {error:"..."}} envelopes, and only some of them carry a {@code code} field. All metas are
 * therefore issued with {@code autoRetry=false} + {@code responseType=String.class}; the raw body
 * is parsed here by {@link JsonUtil#gson}.
 */
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class QqMusicApiService implements IMusicApiService {
    private static final Logger logger = MusicHud.getLogger(QqMusicApiService.class);

    /**
     * Per-request page size used for QQ's offset-based endpoints.
     */
    private static final int PAGE_SIZE = 50;

    private static final Cache<Long, String> songMidCache = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(2000).build();
    private static final Cache<String, MusicDetail> songDetailCache = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(2000).build();
    private static final Cache<Long, String> albumMidCache = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(400).build();
    private static final Cache<Long, String> artistMidCache = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(400).build();
    private static final Cache<Long, String> playlistMidCache = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(400).build();
    private static final Cache<Long, Playlist> playlistCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES).maximumSize(50).build();
    private static final Cache<Long, Album> albumCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES).maximumSize(50).build();
    private static final Cache<Long, Artist> artistCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES).maximumSize(50).build();

    private static volatile QqMusicApiService qqMusicApiService;

    private final Gson gson = JsonUtil.gson;

    public static QqMusicApiService getInstance() {
        if (qqMusicApiService == null) {
            synchronized (QqMusicApiService.class) {
                if (qqMusicApiService == null) {
                    qqMusicApiService = new QqMusicApiService();
                }
            }
        }
        return qqMusicApiService;
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Issues a GET against the QQ API and hands back the raw body, or {@code null} on failure.
     * Never throws: the caller decides whether an absent payload is fatal.
     */
    @Nullable
    private String getText(String pathAndQuery, @Nullable String cookie) {
        try {
            return ApiClient.get(QqServerApiMeta.text(pathAndQuery), cookie, false);
        } catch (Exception e) {
            logger.warn("QQ Music request failed: {} ({})", pathAndQuery, e.toString());
            return null;
        }
    }

    @Nullable
    private static String encode(String value) {
        return URLEncoder.encode(Objects.requireNonNullElse(value, ""), StandardCharsets.UTF_8);
    }

    private static String query(String path, String... keyValues) {
        StringBuilder builder = new StringBuilder(path);
        char separator = '?';
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = keyValues[i];
            String value = keyValues[i + 1];
            if (value == null || value.isEmpty()) {
                continue;
            }
            builder.append(separator).append(key).append('=').append(encode(value));
            separator = '&';
        }
        return builder.toString();
    }

    /**
     * Stable numeric identity for entities QQ only exposes as a MID string. Uses a 63-bit
     * FNV-1a hash so the value is positive and never collides with a real QQ numeric id in
     * practice, while staying deterministic across restarts (the client routes responses by it).
     */
    private static long stableId(@Nullable String mid) {
        if (mid == null || mid.isEmpty()) {
            return -1;
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < mid.length(); i++) {
            hash ^= mid.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash & 0x7fffffffffffffffL;
    }

    /**
     * Prefers a numeric id supplied by QQ, falling back to {@link #stableId(String)}.
     */
    private static long numericId(@Nullable String rawNumericId, @Nullable String mid) {
        if (rawNumericId != null && !rawNumericId.isEmpty()) {
            try {
                return Long.parseLong(rawNumericId.trim());
            } catch (NumberFormatException ignored) {
                // e.g. a 19-digit id beyond Long.MAX_VALUE — fall through to the stable hash
            }
        }
        return stableId(mid);
    }

    private String cookieFor(@Nullable UUID playerUUID) {
        try {
            return QqLoginApiService.getInstance().getRawCookieOrElse(playerUUID, () -> "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String songCoverUrl(@Nullable String albumMid) {
        return albumMid == null || albumMid.isEmpty()
                ? ""
                : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
    }

    private static String singerAvatarUrl(@Nullable String singerMid) {
        return singerMid == null || singerMid.isEmpty()
                ? ""
                : "https://y.gtimg.cn/music/photo_new/T001R300x300M000" + singerMid + ".jpg";
    }

    // ------------------------------------------------------------------ mapping

    /**
     * QQ song item. One DTO covers the four shapes QQ returns songs in: search results
     * ({@code songmid}/{@code songname}/{@code singer[]}), album {@code list} entries,
     * {@code songInfo} wrappers ({@code mid}/{@code name}/{@code singer[]}) and playlist
     * {@code songlist} entries.
     */
    private static final class QqSongItem {
        @SerializedName(value = "songmid", alternate = {"mid"})
        String songMid;
        @SerializedName(value = "songid", alternate = {"id"})
        String songId;
        @SerializedName(value = "songname", alternate = {"name", "title"})
        String name;
        @SerializedName("interval")
        int intervalSeconds;
        @SerializedName("singer")
        List<QqSingerItem> singers;
        // flat album reference (search + album list shapes)
        @SerializedName("albummid")
        String albumMid;
        @SerializedName("albumname")
        String albumName;
        @SerializedName("albumid")
        String albumId;
        // nested album reference (songInfo / songlist shapes)
        @SerializedName("album")
        QqAlbumRef album;
    }

    private static final class QqSingerItem {
        @SerializedName("id")
        String id;
        @SerializedName("mid")
        String mid;
        @SerializedName(value = "name", alternate = {"singer_name"})
        String name;
        @SerializedName(value = "pic", alternate = {"singer_pic"})
        String pic;
    }

    private static final class QqAlbumRef {
        @SerializedName("id")
        String id;
        @SerializedName("mid")
        String mid;
        @SerializedName("name")
        String name;
    }

    private static final class QqEnvelope {
        @SerializedName("response")
        QqResponseBody response;
        @SerializedName("error")
        String error;
    }

    private static final class QqResponseBody {
        @SerializedName("code")
        int code;
        @SerializedName("subcode")
        int subcode;
        @SerializedName("message")
        String message;
        @SerializedName("data")
        QqSearchData data;
        @SerializedName("singer")
        QqSingerData singer;
        @SerializedName("albumSonglist")
        QqAlbumSongList albumSongList;
        @SerializedName("cdlist")
        List<QqCd> cdList;
    }

    private static final class QqSearchData {
        @SerializedName("keyword")
        String keyword;
        @SerializedName("song")
        QqSongPage song;
        @SerializedName("zhida")
        QqZhida zhida;
        @SerializedName("list")
        List<QqSongItem> list;
        // album detail
        @SerializedName("mid")
        String mid;
        @SerializedName("name")
        String name;
        @SerializedName("id")
        String id;
        @SerializedName("singermid")
        String singerMid;
        @SerializedName("singername")
        String singerName;
        @SerializedName("desc")
        String description;
        @SerializedName("total_song_num")
        int totalSongNum;
    }

    private static final class QqSongPage {
        @SerializedName("curnum")
        int currentNumber;
        @SerializedName("curpage")
        int currentPage;
        @SerializedName("totalnum")
        int totalNumber;
        @SerializedName("list")
        List<QqSongItem> list;
    }

    private static final class QqZhida {
        @SerializedName("type")
        int type;
        @SerializedName("zhida_singer")
        QqZhidaSinger singer;
    }

    private static final class QqZhidaSinger {
        @SerializedName("singerID")
        String singerId;
        @SerializedName("singerMID")
        String singerMid;
        @SerializedName("singerName")
        String singerName;
        @SerializedName("singerPic")
        String singerPic;
    }

    private static final class QqSingerData {
        @SerializedName("code")
        int code;
        @SerializedName("data")
        QqSingerDataBody data;
        @SerializedName("singerMid")
        String singerMid;
        @SerializedName("albumList")
        List<QqAlbumListItem> albumList;
        @SerializedName("total")
        int total;
    }

    private static final class QqSingerDataBody {
        @SerializedName("songlist")
        List<QqSongItem> songList;
        @SerializedName("singerlist")
        List<QqSingerItem> singerList;
    }

    private static final class QqAlbumSongList {
        @SerializedName("code")
        int code;
        @SerializedName("data")
        QqAlbumSongListData data;
    }

    private static final class QqAlbumSongListData {
        @SerializedName("totalNum")
        int totalNum;
        @SerializedName("songList")
        List<QqWrappedSong> songList;
    }

    private static final class QqWrappedSong {
        @SerializedName("songInfo")
        QqSongItem songInfo;
    }

    private static final class QqAlbumListItem {
        // Singer/album endpoints emit camel-case keys (albumMID/albumName/albumID), the user's
        // collected-albums endpoint emits all-lowercase ones (albummid/albumname/albumid).
        @SerializedName(value = "albumMid", alternate = {"albummid", "albumMID"})
        String albumMid;
        @SerializedName(value = "albumName", alternate = {"albumname"})
        String albumName;
        @SerializedName(value = "albumID", alternate = {"albumid", "albumId"})
        String albumId;
        @SerializedName(value = "singerName", alternate = {"singername"})
        String singerName;
        @SerializedName(value = "totalNum", alternate = {"songnum"})
        int totalNum;
        @SerializedName(value = "pic", alternate = {"cover", "imgurl"})
        String coverUrl;
    }

    private static final class QqCd {
        @SerializedName("disstid")
        String dissIdString;
        @SerializedName("dissid")
        String dissId;
        @SerializedName("dissname")
        String name;
        @SerializedName("logo")
        String logo;
        @SerializedName("pic_mid")
        String picMid;
        @SerializedName("desc")
        String description;
        @SerializedName("nickname")
        String nickname;
        @SerializedName("headurl")
        String headUrl;
        @SerializedName("songnum")
        int songNumber;
        @SerializedName("songlist")
        List<QqSongItem> songList;
    }

    private static final class QqPlayListEntry {
        // Two shapes share this DTO: the browse/recommend shape (dissid/dissname/imgurl/
        // introduction/creator) and the user's own playlists shape (dissid/dirid/picurl/title/
        // subtitle), so every readable field needs an alternate key.
        @SerializedName(value = "dissid", alternate = {"dissId", "id"})
        String dissId;
        @SerializedName(value = "dissname", alternate = {"title", "name"})
        String name;
        @SerializedName(value = "imgurl", alternate = {"picurl", "pic", "cover"})
        String imageUrl;
        @SerializedName(value = "introduction", alternate = {"subtitle", "desc"})
        String description;
        @SerializedName("creator")
        QqPlayListCreator creator;
    }

    private static final class QqPlayListCreator {
        @SerializedName("name")
        String name;
        @SerializedName("avatarUrl")
        String avatarUrl;
    }

    private static final class QqPlayUrlData {
        @SerializedName("playUrl")
        Map<String, QqPlayUrl> playUrl;
    }

    private static final class QqPlayUrl {
        @SerializedName("url")
        String url;
        @SerializedName("error")
        String error;
    }

    private static final class QqPlayEnvelope {
        @SerializedName("data")
        QqPlayUrlData data;
        @SerializedName("error")
        String error;
    }

    private static final class QqLyricEnvelope {
        @SerializedName("response")
        QqLyricBody response;
    }

    private static final class QqLyricBody {
        @SerializedName("code")
        int code;
        @SerializedName("retcode")
        int retCode;
        @SerializedName("lyric")
        String lyric;
        @SerializedName("trans")
        String translation;
    }

    /**
     * Records a song's numeric-id to MID mapping, and tells the server-side id registry that this
     * id belongs to QQ so long-only request handlers route to this service.
     */
    private static void trackSong(long id, String mid) {
        songMidCache.put(id, mid);
        MusicIdRegistry.register(id, ApiProvider.QQ, mid);
    }

    private static void trackAlbum(long id, String mid) {
        albumMidCache.put(id, mid);
        MusicIdRegistry.register(id, ApiProvider.QQ, mid);
    }

    private static void trackArtist(long id, String mid) {
        artistMidCache.put(id, mid);
        MusicIdRegistry.register(id, ApiProvider.QQ, mid);
    }

    private static void trackPlaylist(long id, String mid) {
        playlistMidCache.put(id, mid);
        MusicIdRegistry.register(id, ApiProvider.QQ, mid);
    }

    /**
     * Builds artists from a QQ {@code singer[]} array, registering their MID mapping.
     */
    private List<Artist> toArtists(@Nullable List<QqSingerItem> singers) {
        if (singers == null || singers.isEmpty()) {
            return List.of();
        }
        List<Artist> artists = new ArrayList<>(singers.size());
        for (QqSingerItem singer : singers) {
            if (singer == null || singer.mid == null || singer.mid.isEmpty()) {
                continue;
            }
            long id = numericId(singer.id, singer.mid);
            trackArtist(id, singer.mid);
            String avatar = singer.pic != null && !singer.pic.isEmpty()
                    ? singer.pic
                    : singerAvatarUrl(singer.mid);
            artists.add(new Artist(id, Objects.requireNonNullElse(singer.name, ""), avatar,
                    0, 0, "", new ArrayList<>(), 0, ApiProvider.QQ, singer.mid));
        }
        return artists;
    }

    /**
     * Converts a QQ song item into the shared {@link MusicDetail} model and registers the
     * numeric-id to MID mapping used by the long-only entry points.
     */
    private MusicDetail toMusicDetail(@Nullable QqSongItem item) {
        if (item == null || item.songMid == null || item.songMid.isEmpty()) {
            return MusicDetail.NONE;
        }
        long id = numericId(item.songId, item.songMid);
        String albumMid = item.album != null && item.album.mid != null && !item.album.mid.isEmpty()
                ? item.album.mid
                : item.albumMid;
        String albumName = item.album != null && item.album.name != null && !item.album.name.isEmpty()
                ? item.album.name
                : item.albumName;
        String albumNumericId = item.album != null ? item.album.id : item.albumId;
        long albumId = numericId(albumNumericId, albumMid);
        if (albumMid != null && !albumMid.isEmpty()) {
            trackAlbum(albumId, albumMid);
        }
        Album album = new Album(albumId, Objects.requireNonNullElse(albumName, ""),
                songCoverUrl(albumMid), new ArrayList<>(), new ArrayList<>(),
                PusherInfo.EMPTY, ApiProvider.QQ, Objects.requireNonNullElse(albumMid, ""));
        List<Artist> artists = toArtists(item.singers);
        MusicDetail detail = new MusicDetail(
                Objects.requireNonNullElse(item.name, ""),
                id,
                artists,
                List.of(),
                album,
                Math.max(item.intervalSeconds, 0) * 1000,
                List.of(),
                PusherInfo.EMPTY,
                LyricInfo.NONE,
                ApiProvider.QQ,
                item.songMid);
        trackSong(id, item.songMid);
        songDetailCache.put(item.songMid, detail);
        // Keep the bean reachable by bare numeric id: QQ cannot re-resolve a track from one.
        MusicIdRegistry.registerDetail(detail);
        return detail;
    }

    private List<MusicDetail> toMusicDetails(@Nullable List<QqSongItem> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        List<MusicDetail> details = new ArrayList<>(items.size());
        for (QqSongItem item : items) {
            MusicDetail detail = toMusicDetail(item);
            if (detail != MusicDetail.NONE) {
                details.add(detail);
            }
        }
        return details;
    }

    private List<MusicDetail> toWrappedMusicDetails(@Nullable List<QqWrappedSong> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        List<QqSongItem> flattened = new ArrayList<>(items.size());
        for (QqWrappedSong wrapped : items) {
            if (wrapped != null && wrapped.songInfo != null) {
                flattened.add(wrapped.songInfo);
            }
        }
        return toMusicDetails(flattened);
    }

    @Nullable
    private QqEnvelope parseEnvelope(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return gson.fromJson(body, QqEnvelope.class);
        } catch (Exception e) {
            logger.warn("Failed to parse QQ Music response: {}", e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ search

    private List<QqSongItem> searchSongItems(String keywords, int offset, int limit) {
        String path = query(QqServerApiMeta.SEARCH,
                "key", keywords,
                "limit", String.valueOf(limit),
                // QQ's search pages are 1-based and the service treats page 0 as page 1.
                "page", String.valueOf(Math.max(1, offset / Math.max(limit, 1) + 1)));
        QqEnvelope envelope = parseEnvelope(getText(path, null));
        if (envelope == null || envelope.response == null || envelope.response.data == null) {
            return List.of();
        }
        QqSongPage page = envelope.response.data.song;
        return page == null || page.list == null ? List.of() : page.list;
    }

    @Override
    public List<MusicDetail> searchMusic(String keywords, int offset) {
        return toMusicDetails(searchSongItems(keywords, offset, PAGE_SIZE));
    }

    /**
     * QQ's keyword search has no content-type selector (every {@code type}/{@code t}/
     * {@code catZhida}/{@code remoteplace} combination is ignored — verified live). Albums and
     * singers are therefore served from {@code /getSmartbox}, which returns one {@code itemlist}
     * per content type, falling back to the search response's {@code zhida} block.
     */
    private static final class QqSmartboxEnvelope {
        @SerializedName("response")
        QqSmartboxBody response;
    }

    private static final class QqSmartboxBody {
        @SerializedName("data")
        QqSmartboxData data;
    }

    private static final class QqSmartboxData {
        @SerializedName("singer")
        QqSmartboxGroup singer;
        @SerializedName("album")
        QqSmartboxGroup album;
        @SerializedName("song")
        QqSmartboxGroup song;
    }

    private static final class QqSmartboxGroup {
        @SerializedName("count")
        int count;
        @SerializedName("itemlist")
        List<QqSmartboxItem> itemList;
    }

    private static final class QqSmartboxItem {
        @SerializedName(value = "id", alternate = {"docid"})
        String id;
        @SerializedName("mid")
        String mid;
        @SerializedName("name")
        String name;
        @SerializedName("singer")
        String singer;
        @SerializedName("pic")
        String pic;
    }

    @Nullable
    private QqSmartboxData parseSmartbox(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            QqSmartboxEnvelope envelope = gson.fromJson(body, QqSmartboxEnvelope.class);
            if (envelope == null || envelope.response == null) {
                return null;
            }
            return envelope.response.data;
        } catch (Exception e) {
            logger.warn("Failed to parse QQ smartbox response: {}", e.toString());
            return null;
        }
    }

    @Override
    public List<Artist> searchArtists(String keywords, int offset) {
        QqSmartboxData data = parseSmartbox(getText(
                query(QqServerApiMeta.SMARTBOX, "key", keywords), null));
        List<Artist> artists = new ArrayList<>();
        if (data != null && data.singer != null && data.singer.itemList != null) {
            for (QqSmartboxItem item : data.singer.itemList) {
                if (item == null || item.mid == null || item.mid.isEmpty()) {
                    continue;
                }
                long id = numericId(item.id, item.mid);
                trackArtist(id, item.mid);
                String avatar = item.pic != null && !item.pic.isEmpty()
                        ? item.pic
                        : singerAvatarUrl(item.mid);
                artists.add(new Artist(id, Objects.requireNonNullElse(item.name, ""), avatar,
                        0, 0, "", new ArrayList<>(), 0, ApiProvider.QQ, item.mid));
            }
        }
        if (artists.isEmpty()) {
            // Fall back to the "did you mean this singer" block of the song search.
            QqEnvelope envelope = parseEnvelope(getText(
                    query(QqServerApiMeta.SEARCH, "key", keywords, "limit", "1"), null));
            if (envelope != null && envelope.response != null && envelope.response.data != null
                    && envelope.response.data.zhida != null
                    && envelope.response.data.zhida.singer != null) {
                QqZhidaSinger zhida = envelope.response.data.zhida.singer;
                if (zhida.singerMid != null && !zhida.singerMid.isEmpty()) {
                    long id = numericId(zhida.singerId, zhida.singerMid);
                    trackArtist(id, zhida.singerMid);
                    artists.add(new Artist(id, Objects.requireNonNullElse(zhida.singerName, ""),
                            Objects.requireNonNullElse(zhida.singerPic, singerAvatarUrl(zhida.singerMid)),
                            0, 0, "", new ArrayList<>(), 0, ApiProvider.QQ, zhida.singerMid));
                }
            }
        }
        return artists;
    }

    @Override
    public List<Album> searchAlbums(String keywords, int offset) {
        QqSmartboxData data = parseSmartbox(getText(
                query(QqServerApiMeta.SMARTBOX, "key", keywords), null));
        List<Album> albums = new ArrayList<>();
        if (data != null && data.album != null && data.album.itemList != null) {
            for (QqSmartboxItem item : data.album.itemList) {
                if (item == null || item.mid == null || item.mid.isEmpty()) {
                    continue;
                }
                long id = numericId(item.id, item.mid);
                trackAlbum(id, item.mid);
                albums.add(new Album(id, Objects.requireNonNullElse(item.name, ""),
                        songCoverUrl(item.mid), new ArrayList<>(), new ArrayList<>(),
                        PusherInfo.EMPTY, ApiProvider.QQ, item.mid));
            }
        }
        return albums;
    }

    /**
     * QQ exposes no playlist keyword search; {@code /getSongLists} browses the playlist square by
     * category. The browse page is filtered by the keyword locally, and returned unfiltered when
     * no keyword is given.
     */
    @Override
    public List<Playlist> searchPlaylists(String keywords, int offset) {
        List<Playlist> playlists = browsePlaylists(offset, PAGE_SIZE);
        if (keywords == null || keywords.isBlank()) {
            return playlists;
        }
        String needle = keywords.toLowerCase(Locale.ROOT);
        List<Playlist> matched = playlists.stream()
                .filter(playlist -> playlist.getName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        return matched;
    }

    private List<Playlist> browsePlaylists(int offset, int limit) {
        String path = query(QqServerApiMeta.SONG_LISTS,
                "categoryId", "10000000",
                "sortId", "5",
                "page", String.valueOf(Math.max(0, offset / Math.max(limit, 1))),
                "limit", String.valueOf(limit));
        String body = getText(path, null);
        List<Playlist> playlists = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return playlists;
        }
        List<QqPlayListEntry> entries;
        try {
            // This endpoint reuses data.list for playlist entries, so it needs its own envelope:
            // the shared search DTO types data.list as songs, which would silently drop dissid.
            QqPlaylistBrowseEnvelope envelope = gson.fromJson(body, QqPlaylistBrowseEnvelope.class);
            if (envelope == null || envelope.response == null || envelope.response.data == null
                    || envelope.response.data.list == null) {
                return playlists;
            }
            entries = envelope.response.data.list;
        } catch (Exception e) {
            logger.warn("Failed to parse QQ playlist browse response: {}", e.toString());
            return playlists;
        }
        for (QqPlayListEntry entry : entries) {
            if (entry == null || entry.dissId == null || entry.dissId.isEmpty()) {
                continue;
            }
            long id = numericId(entry.dissId, entry.dissId);
            trackPlaylist(id, entry.dissId);
            Profile creator = entry.creator == null
                    ? Profile.ANONYMOUS
                    : new Profile(Objects.requireNonNullElse(entry.creator.name, ""),
                    Objects.requireNonNullElse(entry.creator.avatarUrl, ""), 0L);
            playlists.add(new Playlist(id, Objects.requireNonNullElse(entry.name, ""),
                    -1, "", Objects.requireNonNullElse(entry.imageUrl, ""),
                    creator, Privacy.PUBLIC, List.of(), PusherInfo.EMPTY,
                    ApiProvider.QQ, entry.dissId));
        }
        return playlists;
    }

    private static final class QqPlaylistBrowseEnvelope {
        @SerializedName("response")
        QqPlaylistBrowseBody response;
    }

    private static final class QqPlaylistBrowseBody {
        @SerializedName("code")
        int code;
        @SerializedName("data")
        QqPlaylistBrowseData data;
    }

    private static final class QqPlaylistBrowseData {
        @SerializedName("list")
        List<QqPlayListEntry> list;
    }

    @Override
    public <T> T search(String keywords, int offset, int limit, SearchType searchType,
                        Function<String, T> transformer) {
        // QQ has no per-type search endpoint; the raw search body is handed to the transformer.
        String path = query(QqServerApiMeta.SEARCH,
                "key", keywords,
                "limit", String.valueOf(limit),
                "page", String.valueOf(Math.max(1, offset / Math.max(limit, 1) + 1)));
        String body = getText(path, null);
        if (body == null) {
            throw new RuntimeException("QQ Music search failed for keywords: " + keywords);
        }
        return transformer.apply(body);
    }

    // ------------------------------------------------------------------ details

    @Override
    public List<MusicDetail> getMusicDetailByIds(List<Long> ids, UUID playerUUID) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<MusicDetail> details = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            MusicDetail cached = findCachedDetail(id);
            if (cached != null) {
                details.add(cached);
            }
        }
        if (!details.isEmpty()) {
            return details;
        }
        // Nothing cached: QQ's /getSongInfo is broken upstream (always code:500001) and
        // /getLyric//getMusicPlay reject numeric ids, so the numeric id cannot be resolved
        // without an earlier search. Report nothing rather than failing the caller.
        logger.warn("QQ Music cannot resolve numeric ids {} without a prior search on this server",
                ids);
        return details;
    }

    @Nullable
    private MusicDetail findCachedDetail(long id) {
        String mid = songMidCache.getIfPresent(id);
        if (mid == null) {
            return null;
        }
        MusicDetail cached = songDetailCache.getIfPresent(mid);
        if (cached == null) {
            // The detail cache expired but the id mapping survived; drop the stale mapping.
            songMidCache.invalidate(id);
        }
        return cached;
    }

    @Override
    public Album getAlbumInfoDetail(long id, UUID playerUUID) {
        return getAlbumInfoDetail(id, albumMidCache.getIfPresent(id), playerUUID);
    }

    @Override
    public Album getAlbumInfoDetail(long id, String externalId, UUID playerUUID) {
        String mid = externalId != null && !externalId.isEmpty()
                ? externalId
                : albumMidCache.getIfPresent(id);
        if (mid == null || mid.isEmpty()) {
            return Album.NONE;
        }
        Album cached = albumCache.getIfPresent(id);
        if (cached != null && !cached.getMusicDetails().isEmpty()) {
            return cached;
        }
        QqEnvelope envelope = parseEnvelope(getText(
                query(QqServerApiMeta.ALBUM_INFO, "albummid", mid), cookieFor(playerUUID)));
        if (envelope == null || envelope.response == null || envelope.response.data == null) {
            return Album.NONE;
        }
        QqSearchData data = envelope.response.data;
        long albumId = numericId(data.id, mid);
        trackAlbum(albumId, mid);
        List<MusicDetail> tracks = toMusicDetails(data.list);
        Album brief = new Album(albumId, Objects.requireNonNullElse(data.name, ""),
                songCoverUrl(mid), new ArrayList<>(), new ArrayList<>(),
                PusherInfo.EMPTY, ApiProvider.QQ, mid);
        // Point every track at a brief copy of its album so the bean graph stays acyclic,
        // mirroring the NetEase implementation.
        for (MusicDetail track : tracks) {
            track.setAlbum(brief.shallowCopyBriefInfo());
        }
        Album album = new Album(albumId, Objects.requireNonNullElse(data.name, ""),
                songCoverUrl(mid), tracks, new ArrayList<>(), PusherInfo.EMPTY,
                ApiProvider.QQ, mid);
        if (data.singerMid != null && !data.singerMid.isEmpty()) {
            long artistId = stableId(data.singerMid);
            trackArtist(artistId, data.singerMid);
            album.getArtists().add(new Artist(artistId,
                    Objects.requireNonNullElse(data.singerName, ""),
                    singerAvatarUrl(data.singerMid), 0, 0, "", new ArrayList<>(),
                    Math.max(data.totalSongNum, 0), ApiProvider.QQ, data.singerMid));
        }
        albumCache.put(albumId, album);
        albumCache.put(id, album);
        return album;
    }

    @Override
    public Artist getArtistDetail(long id, UUID playerUUID) {
        return getArtistDetail(id, artistMidCache.getIfPresent(id), playerUUID);
    }

    @Override
    public Artist getArtistDetail(long id, String externalId, UUID playerUUID) {
        String mid = externalId != null && !externalId.isEmpty()
                ? externalId
                : artistMidCache.getIfPresent(id);
        if (mid == null || mid.isEmpty()) {
            return new Artist(id, "", "", 0, 0, "", new ArrayList<>(), 0,
                    ApiProvider.QQ, "");
        }
        Artist cached = artistCache.getIfPresent(id);
        if (cached != null && !cached.getMusicDetails().isEmpty()) {
            return cached;
        }
        String cookie = cookieFor(playerUUID);
        List<MusicDetail> hotSongs = toMusicDetails(fetchSingerHotSongs(mid, 0, PAGE_SIZE, cookie));
        String description = fetchSingerDescription(mid, cookie);
        int albumCount = fetchSingerAlbumCount(mid, cookie);
        Artist artist = new Artist(id, cached != null ? cached.getName() : "",
                singerAvatarUrl(mid), albumCount, hotSongs.size(), description,
                new ArrayList<>(hotSongs), hotSongs.size(), ApiProvider.QQ, mid);
        artistCache.put(id, artist);
        artistCache.put(stableId(mid), artist);
        return artist;
    }

    @Override
    public List<MusicDetail> getArtistMoreMusic(long id, int offset, UUID playerUUID) {
        return getArtistMoreMusic(id, artistMidCache.getIfPresent(id), offset, playerUUID);
    }

    @Override
    public List<MusicDetail> getArtistMoreMusic(long id, String externalId, int offset,
                                                UUID playerUUID) {
        String mid = externalId != null && !externalId.isEmpty()
                ? externalId
                : artistMidCache.getIfPresent(id);
        if (mid == null || mid.isEmpty()) {
            return List.of();
        }
        // QQ's singer page index is effectively 1-based (page 0 and 1 both return the first page),
        // so the MusicHud offset is translated with a +1 base.
        return toMusicDetails(fetchSingerHotSongs(mid, offset, PAGE_SIZE, cookieFor(playerUUID)));
    }

    @Nullable
    private List<QqSongItem> fetchSingerHotSongs(String singerMid, int offset, int limit,
                                                 @Nullable String cookie) {
        String path = query(QqServerApiMeta.SINGER_HOTSONG,
                "singermid", singerMid,
                "limit", String.valueOf(limit),
                "page", String.valueOf(Math.max(1, offset / Math.max(limit, 1) + 1)));
        QqEnvelope envelope = parseEnvelope(getText(path, cookie));
        if (envelope == null || envelope.response == null || envelope.response.singer == null
                || envelope.response.singer.data == null) {
            return List.of();
        }
        List<QqSongItem> songList = envelope.response.singer.data.songList;
        return songList == null ? List.of() : songList;
    }

    private String fetchSingerDescription(String singerMid, @Nullable String cookie) {
        String body = getText(query(QqServerApiMeta.SINGER_DESC, "singermid", singerMid), cookie);
        if (body == null) {
            return "";
        }
        // /getSingerDesc answers with {"response":"<XML/>"}, so the description lives in a CDATA
        // block inside a plain string envelope.
        int start = body.indexOf("<![CDATA[");
        if (start < 0) {
            return "";
        }
        int end = body.indexOf("]]>", start);
        return end < 0 ? "" : body.substring(start + "<![CDATA[".length(), end).trim();
    }

    private int fetchSingerAlbumCount(String singerMid, @Nullable String cookie) {
        QqEnvelope envelope = parseEnvelope(getText(
                query(QqServerApiMeta.SINGER_ALBUM, "singermid", singerMid, "limit", "1"), cookie));
        if (envelope == null || envelope.response == null || envelope.response.singer == null) {
            return 0;
        }
        return Math.max(envelope.response.singer.total, 0);
    }

    @Override
    public Playlist getPlaylistDetail(long id, @Nullable UUID player) {
        return getPlaylistDetail(id, playlistMidCache.getIfPresent(id), player);
    }

    @Override
    public Playlist getPlaylistDetail(long id, String externalId, @Nullable UUID player) {
        String dissId = externalId != null && !externalId.isEmpty()
                ? externalId
                : playlistMidCache.getIfPresent(id);
        if (dissId == null || dissId.isEmpty()) {
            return Playlist.empty(id);
        }
        Playlist cached = playlistCache.getIfPresent(id);
        if (cached != null && !cached.getTracks().isEmpty()) {
            return cached;
        }
        QqEnvelope envelope = parseEnvelope(getText(
                query(QqServerApiMeta.SONG_LIST_DETAIL, "disstid", dissId), cookieFor(player)));
        if (envelope == null || envelope.response == null || envelope.response.cdList == null
                || envelope.response.cdList.isEmpty()) {
            return Playlist.empty(id);
        }
        QqCd cd = envelope.response.cdList.getFirst();
        if (cd == null) {
            return Playlist.empty(id);
        }
        long playlistId = numericId(cd.dissId != null ? cd.dissId : cd.dissIdString, dissId);
        trackPlaylist(playlistId, dissId);
        String cover = cd.logo != null && !cd.logo.isEmpty() ? cd.logo : songCoverUrl(cd.picMid);
        Profile creator = new Profile(Objects.requireNonNullElse(cd.nickname, ""),
                Objects.requireNonNullElse(cd.headUrl, ""), 0L);
        Playlist playlist = new Playlist(playlistId, Objects.requireNonNullElse(cd.name, ""),
                -1, "", cover, creator, Privacy.PUBLIC,
                toMusicDetails(cd.songList), PusherInfo.EMPTY, ApiProvider.QQ, dissId);
        playlistCache.put(playlistId, playlist);
        playlistCache.put(id, playlist);
        return playlist;
    }

    // ------------------------------------------------------------------ playback

    /**
     * QQ quality tokens ordered best-first, so a request can degrade when the account (or the
     * track) does not grant the preferred tier. QQ only returns a playable URL for a logged-in
     * account; anonymous requests come back with an empty {@code url} and an explanatory
     * {@code error}.
     */
    private static List<String> qualityChain(Quality quality) {
        if (quality == null) {
            return List.of("320", "128");
        }
        return switch (quality) {
            case HIRES, JY_MASTER, SKY, DOLBY -> List.of("ape", "flac", "320", "128");
            case LOSSLESS, JY_EFFECT -> List.of("flac", "320", "128");
            case EX_HIGH, HIGHER -> List.of("320", "128");
            case STANDARD, NONE -> List.of("128", "320");
        };
    }

    @Override
    public MusicResourceInfo getResourceInfo(MusicDetail musicDetail, Quality quality,
                                             UUID playerUUID) {
        if (musicDetail == null || musicDetail == MusicDetail.NONE) {
            return MusicResourceInfo.NONE;
        }
        String mid = musicDetail.getExternalId();
        if (mid.isEmpty()) {
            // Album/playlist tracks built from QQ payloads always carry a MID; a missing one
            // means the detail did not come from this provider.
            return MusicResourceInfo.NONE;
        }
        String cookie = cookieFor(playerUUID);
        for (String token : qualityChain(quality)) {
            String body = getText(query(QqServerApiMeta.MUSIC_PLAY,
                    "songmid", mid, "quality", token), cookie);
            QqPlayEnvelope envelope = parsePlayEnvelope(body);
            if (envelope == null || envelope.data == null || envelope.data.playUrl == null) {
                continue;
            }
            QqPlayUrl playUrl = envelope.data.playUrl.get(mid);
            if (playUrl == null || playUrl.url == null || playUrl.url.isEmpty()) {
                continue;
            }
            MusicResourceInfo info = MusicResourceInfo.from(playUrl.url, musicDetail);
            info.setType(formatOf(token, playUrl.url));
            info.setBitrate(bitrateOf(token));
            info.setFee(Fee.FREE);
            return info;
        }
        logger.debug("QQ Music returned no playable url for songmid={} (quality={})", mid, quality);
        return MusicResourceInfo.NONE;
    }

    @Nullable
    private QqPlayEnvelope parsePlayEnvelope(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return gson.fromJson(body, QqPlayEnvelope.class);
        } catch (Exception e) {
            logger.warn("Failed to parse QQ Music play response: {}", e.toString());
            return null;
        }
    }

    private static FormatType formatOf(String qualityToken, String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".flac")) {
            return FormatType.FLAC;
        }
        if (lower.contains(".mp3")) {
            return FormatType.MP3;
        }
        return switch (qualityToken) {
            case "flac", "ape" -> FormatType.FLAC;
            default -> FormatType.MP3;
        };
    }

    private static int bitrateOf(String qualityToken) {
        return switch (qualityToken) {
            case "ape" -> 900_000;
            case "flac" -> 800_000;
            case "320" -> 320_000;
            default -> 128_000;
        };
    }

    // ------------------------------------------------------------------ lyrics

    @Override
    public LyricInfo getLyricInfo(MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail == MusicDetail.NONE) {
            return LyricInfo.NONE;
        }
        String mid = musicDetail.getExternalId();
        if (mid.isEmpty()) {
            return LyricInfo.NONE;
        }
        QqLyricEnvelope envelope;
        try {
            String body = getText(query(QqServerApiMeta.LYRIC, "songmid", mid), null);
            if (body == null) {
                return LyricInfo.NONE;
            }
            envelope = gson.fromJson(body, QqLyricEnvelope.class);
        } catch (Exception e) {
            logger.warn("Failed to parse QQ Music lyric response: {}", e.toString());
            return LyricInfo.NONE;
        }
        if (envelope == null || envelope.response == null) {
            return LyricInfo.NONE;
        }
        QqLyricBody lyricBody = envelope.response;
        // QQ reports success as code 0 and failure as 1101; there is no word-by-word (QRC) lyric.
        if (lyricBody.code != 0 && lyricBody.retCode != 0) {
            return LyricInfo.NONE;
        }
        return new LyricInfo(
                new Lyric(Objects.requireNonNullElse(lyricBody.lyric, "")),
                new Lyric(Objects.requireNonNullElse(lyricBody.translation, "")),
                Lyric.NONE,
                Lyric.NONE);
    }

    // ------------------------------------------------------------------ user library

    @Override
    public List<Playlist> getPlayersUserSubscribedPlaylists(UUID playerUUID) {
        if (playerUUID == null) {
            return List.of();
        }
        String cookie = cookieFor(playerUUID);
        String body = getText(userLibraryPath(QqServerApiMeta.USER_PLAYLISTS, cookie), cookie);
        List<Playlist> playlists = new ArrayList<>();
        for (QqPlayListEntry entry : entriesFromUserBody(body, QqPlayListEntry.class)) {
            if (entry == null || entry.dissId == null || entry.dissId.isEmpty()) {
                continue;
            }
            long id = numericId(entry.dissId, entry.dissId);
            trackPlaylist(id, entry.dissId);
            Profile creator = entry.creator == null
                    ? Profile.ANONYMOUS
                    : new Profile(Objects.requireNonNullElse(entry.creator.name, ""),
                    Objects.requireNonNullElse(entry.creator.avatarUrl, ""), 0L);
            playlists.add(new Playlist(id, Objects.requireNonNullElse(entry.name, ""),
                    -1, "", Objects.requireNonNullElse(entry.imageUrl, ""),
                    creator, Privacy.PUBLIC, List.of(), PusherInfo.EMPTY,
                    ApiProvider.QQ, entry.dissId));
        }
        return playlists;
    }

    @Override
    public List<Album> getPlayersUserSubscribedAlbums(UUID playerUUID) {
        if (playerUUID == null) {
            return List.of();
        }
        String cookie = cookieFor(playerUUID);
        String body = getText(userLibraryPath(QqServerApiMeta.USER_COLLECTED_ALBUMS, cookie), cookie);
        List<Album> albums = new ArrayList<>();
        for (QqAlbumListItem entry : entriesFromUserBody(body, QqAlbumListItem.class)) {
            if (entry == null || entry.albumMid == null || entry.albumMid.isEmpty()) {
                continue;
            }
            long id = numericId(entry.albumId, entry.albumMid);
            trackAlbum(id, entry.albumMid);
            String cover = entry.coverUrl != null && !entry.coverUrl.isEmpty()
                    ? entry.coverUrl
                    : songCoverUrl(entry.albumMid);
            albums.add(new Album(id, Objects.requireNonNullElse(entry.albumName, ""),
                    cover, new ArrayList<>(), new ArrayList<>(),
                    PusherInfo.EMPTY, ApiProvider.QQ, entry.albumMid));
        }
        return albums;
    }

    @Override
    public List<Artist> getPlayersUserSubscribedArtists(UUID playerUUID) {
        if (playerUUID == null) {
            return List.of();
        }
        String cookie = cookieFor(playerUUID);
        String body = getText(userLibraryPath(QqServerApiMeta.USER_FOLLOW_SINGERS, cookie), cookie);
        List<Artist> artists = new ArrayList<>();
        for (QqSingerItem entry : entriesFromUserBody(body, QqSingerItem.class)) {
            if (entry == null || entry.mid == null || entry.mid.isEmpty()) {
                continue;
            }
            long id = numericId(entry.id, entry.mid);
            trackArtist(id, entry.mid);
            String avatar = entry.pic != null && !entry.pic.isEmpty()
                    ? entry.pic
                    : singerAvatarUrl(entry.mid);
            artists.add(new Artist(id, Objects.requireNonNullElse(entry.name, ""), avatar,
                    0, 0, "", new ArrayList<>(), 0, ApiProvider.QQ, entry.mid));
        }
        return artists;
    }

    /**
     * Every QQ user-library endpoint requires the account's numeric {@code uin} as a query
     * parameter next to the login cookie; without it the server answers
     * {@code 400 {"error":"缺少 uin 参数"}} and the whole library comes back empty.
     */
    private String userLibraryPath(String path, String cookie) {
        Long uin = QqLoginApiService.uinOf(cookie);
        return uin == null ? path : query(path, "uin", Long.toString(uin));
    }

    /**
     * QQ's user-library endpoints wrap their payload in a shape that differs per endpoint and is
     * nested at varying depth ({@code data.list}, {@code data.playlists},
     * {@code data.albumlist}, …), and answer {@code {"error":…}} when the account is not
     * addressable. The first array found under a known list key wins; elements that cannot be
     * mapped are skipped.
     */
    private <T> List<T> entriesFromUserBody(@Nullable String body, Class<T> elementType) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{\"error\"") || trimmed.startsWith("{\"response\":\"")
                || trimmed.equals("null")) {
            return List.of();
        }
        try {
            var root = gson.fromJson(trimmed, com.google.gson.JsonObject.class);
            if (root == null) {
                return List.of();
            }
            com.google.gson.JsonArray array = findListArray(root, 0);
            return array == null ? List.of() : parseArray(array, elementType);
        } catch (Exception e) {
            logger.warn("Failed to parse QQ user library response: {}", e.toString());
            return List.of();
        }
    }

    private static final List<String> USER_LIST_FIELDS = List.of(
            "list", "playlists", "albumlist", "albumList", "singerlist", "singerList",
            "songlist", "songList", "v_playlist", "vecPlaylist", "vecAlbum", "vecSinger",
            "data");

    /**
     * Depth-first search for the first array stored under one of {@link #USER_LIST_FIELDS}.
     * Matching by name (instead of taking any array) keeps unrelated sibling arrays such as
     * {@code nums} or {@code mymusic} out of the result.
     */
    @Nullable
    private static com.google.gson.JsonArray findListArray(com.google.gson.JsonElement element, int depth) {
        if (element == null || depth > 6) {
            return null;
        }
        if (element.isJsonObject()) {
            com.google.gson.JsonObject object = element.getAsJsonObject();
            for (String field : USER_LIST_FIELDS) {
                var candidate = object.get(field);
                if (candidate != null && candidate.isJsonArray()) {
                    return candidate.getAsJsonArray();
                }
            }
            for (Map.Entry<String, com.google.gson.JsonElement> entry : object.entrySet()) {
                var found = findListArray(entry.getValue(), depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (element.isJsonArray()) {
            for (var item : element.getAsJsonArray()) {
                var found = findListArray(item, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private <T> List<T> parseArray(com.google.gson.JsonArray array, Class<T> elementType) {
        List<T> result = new ArrayList<>(array.size());
        for (var item : array) {
            try {
                T parsed = gson.fromJson(item, elementType);
                if (parsed != null) {
                    result.add(parsed);
                }
            } catch (Exception ignored) {
                // skip malformed entries
            }
        }
        return result;
    }

    /**
     * Exposed for diagnostics: resolves a numeric id back to its QQ MID when known.
     */
    @Nullable
    public String resolveSongMid(long id) {
        return songMidCache.getIfPresent(id);
    }
}
