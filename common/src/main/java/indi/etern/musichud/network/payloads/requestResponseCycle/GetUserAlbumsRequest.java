package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.music.Album;
import indi.etern.musichud.interfaces.CommonRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.IServerNetworkService;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.IMusicApiService;
import indi.etern.musichud.utils.ServerDataPacketVThreadExecutor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks for the player's own subscribed albums. See {@link GetUserPlaylistRequest} for why the
 * backend comes from the player's session instead of configuration.
 */
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class GetUserAlbumsRequest implements C2SPayload {
    public static final GetUserAlbumsRequest REQUEST = new GetUserAlbumsRequest();
    public static final ByteBufCodec<GetUserAlbumsRequest> CODEC = ByteBufCodec.unit(REQUEST);

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    GetUserAlbumsRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((getUserPlaylistRequest, player) -> {
                        List<Album> playersUserAlbums = new ArrayList<>();
                        for (ApiProvider provider : ApiProvider.providersLoggedInFor(player.getUUID())) {
                            try {
                                playersUserAlbums.addAll(
                                        IMusicApiService.getInstance(provider)
                                                .getPlayersUserSubscribedAlbums(player.getUUID()));
                            } catch (Exception e) {
                                MusicHud.getLogger(GetUserAlbumsRequest.class)
                                        .warn("Could not load {} albums for {}", provider, player.getUUID(), e);
                            }
                        }
                        IServerNetworkService.getInstance().sendToPlayer(player, new GetUserAlbumsResponse(playersUserAlbums));
                    })
            );
        }
    }
}
