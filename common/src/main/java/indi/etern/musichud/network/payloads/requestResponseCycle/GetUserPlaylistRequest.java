package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.beans.music.Playlist;
import indi.etern.musichud.MusicHud;
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
 * Asks for the player's own subscribed playlists.
 * <p>
 * The backend is resolved from the player's session rather than from configuration: subscriptions
 * live in a specific account, so they can only be read from the service that player logged in to.
 */
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class GetUserPlaylistRequest implements C2SPayload {
    public static final GetUserPlaylistRequest REQUEST = new GetUserPlaylistRequest();
    public static final ByteBufCodec<GetUserPlaylistRequest> CODEC = ByteBufCodec.unit(REQUEST);

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    GetUserPlaylistRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((getUserPlaylistRequest, player) -> {
                        List<Playlist> playersUserPlaylists = new ArrayList<>();
                        // Merged across backends: with a dual login the player expects the account
                        // page to show both libraries, and a backend that fails (QQ's
                        // follow-singers endpoint is broken upstream) must not hide the other one.
                        for (ApiProvider provider : ApiProvider.providersLoggedInFor(player.getUUID())) {
                            try {
                                playersUserPlaylists.addAll(
                                        IMusicApiService.getInstance(provider)
                                                .getPlayersUserSubscribedPlaylists(player.getUUID()));
                            } catch (Exception e) {
                                MusicHud.getLogger(GetUserPlaylistRequest.class)
                                        .warn("Could not load {} playlists for {}", provider, player.getUUID(), e);
                            }
                        }
                        IServerNetworkService.getInstance().sendToPlayer(player, new GetUserPlaylistResponse(playersUserPlaylists));
                    })
            );
        }
    }
}
