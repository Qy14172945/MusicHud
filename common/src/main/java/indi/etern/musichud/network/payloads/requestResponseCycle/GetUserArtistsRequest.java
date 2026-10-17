package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.music.Artist;
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
 * Asks for the player's own followed artists. See {@link GetUserPlaylistRequest} for why the
 * backend comes from the player's session instead of configuration.
 */
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class GetUserArtistsRequest implements C2SPayload {
    public static final GetUserArtistsRequest REQUEST = new GetUserArtistsRequest();
    public static final ByteBufCodec<GetUserArtistsRequest> CODEC = ByteBufCodec.unit(REQUEST);

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    GetUserArtistsRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((getUserPlaylistRequest, player) -> {
                        List<Artist> playersUserArtists = new ArrayList<>();
                        for (ApiProvider provider : ApiProvider.providersLoggedInFor(player.getUUID())) {
                            try {
                                playersUserArtists.addAll(
                                        IMusicApiService.getInstance(provider)
                                                .getPlayersUserSubscribedArtists(player.getUUID()));
                            } catch (Exception e) {
                                MusicHud.getLogger(GetUserArtistsRequest.class)
                                        .warn("Could not load {} artists for {}", provider, player.getUUID(), e);
                            }
                        }
                        IServerNetworkService.getInstance().sendToPlayer(player, new GetUserArtistsResponse(playersUserArtists));
                    })
            );
        }
    }
}
