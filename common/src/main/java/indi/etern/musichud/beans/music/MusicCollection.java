package indi.etern.musichud.beans.music;

import indi.etern.musichud.server.api.ApiProvider;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public interface MusicCollection {
    long getId();
    String getName();
    String getNameI18nKey();
    String getImageThumbnailUrl(int size);
    PusherInfo getPusherInfo();
    Collection<MusicDetail> getMusicDetails();
    CompletableFuture<Collection<MusicDetail>> loadMusicDetails(boolean ignoreCache);
    MusicCollection copyWithPusherInfo(PusherInfo pusherInfo);

    /**
     * Which music API this collection came from.
     *
     * <p>Defaults to {@link ApiProvider#NCM} because a collection that never opted in can only have
     * been produced by the NetEase path. {@link Album} and {@link Playlist} both carry a real
     * provider field and override this, so the UI can label a collection with the platform it is
     * actually served from — which matters because the two providers are usable simultaneously and
     * a playlist's play URLs only resolve against its own provider.
     */
    default ApiProvider getProvider() {
        return ApiProvider.NCM;
    }

    boolean equalsLoose(Object obj);
}