package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.beans.music.Playlist;
import indi.etern.musichud.interfaces.CommonRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.Codecs;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.IServerNetworkService;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.IMusicApiService;
import indi.etern.musichud.server.api.MusicIdRegistry;
import indi.etern.musichud.utils.ServerDataPacketVThreadExecutor;

public record GetPlaylistDetailRequest(long id) implements C2SPayload {
    public static final ByteBufCodec<GetPlaylistDetailRequest> CODEC = ByteBufCodec.composite(
            Codecs.VAR_LONG,//TODO replace with Codecs.LONG in 1.3.0
            GetPlaylistDetailRequest::id,
            GetPlaylistDetailRequest::new
    );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    GetPlaylistDetailRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((playlistDetailRequest, player) -> {
                        // The request only carries a numeric id, so the owning provider (and its own
                        // identifier for the entity) is resolved from the server-side id registry.
                        ApiProvider provider = MusicIdRegistry.providerOf(playlistDetailRequest.id);
                        Playlist playlistDetail = IMusicApiService.getInstance(provider)
                                .getPlaylistDetail(playlistDetailRequest.id,
                                        MusicIdRegistry.externalIdOf(playlistDetailRequest.id),
                                        player.getUUID());
                        if (playlistDetail != null) {
                            IServerNetworkService.getInstance().sendToPlayer(player,new GetPlaylistDetailResponse(playlistDetail));
                        }
                    })
            );
        }
    }
}
