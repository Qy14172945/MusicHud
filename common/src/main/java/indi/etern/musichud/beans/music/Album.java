package indi.etern.musichud.beans.music;

import com.google.gson.annotations.SerializedName;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.client.services.MusicService;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.Codecs;
import indi.etern.musichud.server.api.ApiProvider;
import lombok.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@AllArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class Album implements MusicCollection{
    public static final ByteBufCodec<Album> CODEC = ByteBufCodec.composite(
            Codecs.VAR_LONG,//TODO replace with Codecs.LONG in 1.3.0
            Album::getId,
            Codecs.STRING_UTF8,
            Album::getName,
            Codecs.STRING_UTF8,
            Album::getPicUrl,
            Codecs.ofList(() -> MusicDetail.CODEC),
            Album::getMusicDetails,
            Codecs.ofList(() -> Artist.CODEC),
            Album::getArtists,
            PusherInfo.CODEC,
            Album::getPusherInfo,
            Codecs.ofEnum(ApiProvider.class),
            Album::getProvider,
            Codecs.STRING_UTF8,
            Album::getExternalId,
            Album::new
    );
    public static final Album NONE = new Album();
    @Getter
    long id;
    String name = "";
    String picUrl = "";
    @SerializedName("songs")
    @Setter
    List<MusicDetail> musicDetails = new ArrayList<>();
    List<Artist> artists = new ArrayList<>();

    // Not contained in the original API response, set separately
    @Getter
    transient PusherInfo pusherInfo = PusherInfo.EMPTY;
    /**
     * Which music API this album came from. Defaults to {@link ApiProvider#NCM} so NetEase
     * responses keep their existing (numeric {@link #id}) identity.
     */
    @Getter
    transient ApiProvider provider = ApiProvider.NCM;
    /**
     * Provider-native identifier — QQ album MID, empty for NetEase.
     */
    @Getter
    transient String externalId = "";

    public String getThumbnailPicUrl(int size) {
        if (picUrl == null || picUrl.isEmpty()) {
            // QQ entries occasionally arrive without artwork; emitting a bare "?param=…" makes the
            // image loader log "URI is not absolute" for every such row.
            return "";
        }
        return picUrl + (picUrl.indexOf('?') >= 0 ? "&" : "?") + "param=" + size + "y" + size;
    }

    public String getName() {
        return Objects.requireNonNullElse(name, "");
    }

    @Override
    public String getNameI18nKey() {
        return MusicHud.MOD_ID + ".text.album";
    }

    public String getPicUrl() {
        return Objects.requireNonNullElse(picUrl, "");
    }

    @Override
    public List<MusicDetail> getMusicDetails() {
        List<MusicDetail> musicDetails = Objects.requireNonNullElse(this.musicDetails, new ArrayList<>());
        return musicDetails.stream().filter(Objects::nonNull).toList();
    }

    @Override
    public String getImageThumbnailUrl(int size) {
        return getThumbnailPicUrl(size);
    }

    @Override
    public CompletableFuture<Collection<MusicDetail>> loadMusicDetails(boolean ignoreCache) {
        CompletableFuture<Collection<MusicDetail>> future = new CompletableFuture<>();
        MusicService.getInstance().loadAlbumDetail(id, ignoreCache).thenAccept(albumInfo -> future.complete(albumInfo.musicDetails));
        return future;
    }

    public List<Artist> getArtists() {
        return Objects.requireNonNullElse(artists, new ArrayList<>());
    }

    public Album shallowCopyBriefInfo() {
        Album album = new Album();
        album.id = this.id;
        album.name = this.name;
        album.picUrl = this.picUrl;
        album.provider = this.provider;
        album.externalId = this.externalId;
        return album;
    }

    @Override
    public Album copyWithPusherInfo(PusherInfo pusherInfo) {
        Album album = shallowCopyBriefInfo();
        album.pusherInfo = pusherInfo;
        return album;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Album album
                && album.id == id
                && album.name.equals(name)
                && album.picUrl.equals(picUrl)
                && album.pusherInfo.equals(pusherInfo);
    }

    @Override
    public boolean equalsLoose(Object obj) {
        return obj instanceof Album album && id == album.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, picUrl, pusherInfo.getPlayerUUID());
    }
}