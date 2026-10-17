package indi.etern.musichud.server.api;

import indi.etern.musichud.beans.api.SearchType;
import indi.etern.musichud.beans.music.*;
import indi.etern.musichud.server.api.impl.ncm.MusicApiService;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

public interface IMusicApiService {
    static IMusicApiService getInstance(ApiProvider apiProvider) {
        return switch (Objects.requireNonNull(apiProvider)) {
            case NCM -> MusicApiService.getInstance();
            case QQ -> indi.etern.musichud.server.api.impl.qq.QqMusicApiService.getInstance();
        };
    }

    Playlist getPlaylistDetail(long id, @Nullable UUID player);

    List<Album> searchAlbums(String keywords, int offset);

    List<Artist> searchArtists(String keywords, int offset);

    List<MusicDetail> searchMusic(String keywords, int offset);

    List<Playlist> searchPlaylists(String keywords, int offset);

    <T> T search(String keywords, int offset, int limit, SearchType searchType, Function<String, T> transformer);

    List<MusicDetail> getMusicDetailByIds(List<Long> ids, UUID playerUUID);

    Album getAlbumInfoDetail(long id, UUID playerUUID);

    Artist getArtistDetail(long id, UUID playerUUID);

    List<MusicDetail> getArtistMoreMusic(long id, int offset, UUID playerUUID);

    MusicResourceInfo getResourceInfo(MusicDetail musicDetail, Quality quality, UUID playerUUID);

    List<Playlist> getPlayersUserSubscribedPlaylists(UUID playerUUID);

    List<Album> getPlayersUserSubscribedAlbums(UUID playerUUID);

    List<Artist> getPlayersUserSubscribedArtists(UUID playerUUID);

    LyricInfo getLyricInfo(MusicDetail musicDetail);

    /*
     * --- Provider-native identifier (externalId) overloads -------------------------------------
     * NetEase addresses everything by numeric id, so its implementations inherit the delegating
     * defaults below. QQ Music addresses songs/albums/artists/playlists by MID strings and its
     * endpoints reject the numeric id, so QqMusicApiService overrides these.
     */

    default Album getAlbumInfoDetail(long id, String externalId, UUID playerUUID) {
        return getAlbumInfoDetail(id, playerUUID);
    }

    default Artist getArtistDetail(long id, String externalId, UUID playerUUID) {
        return getArtistDetail(id, playerUUID);
    }

    default List<MusicDetail> getArtistMoreMusic(long id, String externalId, int offset, UUID playerUUID) {
        return getArtistMoreMusic(id, offset, playerUUID);
    }

    default Playlist getPlaylistDetail(long id, String externalId, @Nullable UUID player) {
        return getPlaylistDetail(id, player);
    }
}