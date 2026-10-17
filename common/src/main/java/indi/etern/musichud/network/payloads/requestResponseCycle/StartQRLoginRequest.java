package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.interfaces.CommonRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.Codecs;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.IServerNetworkService;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.ILoginApiService;
import indi.etern.musichud.utils.ServerDataPacketVThreadExecutor;

/**
 * Asks the server to mint a QR login code for {@code provider}.
 * <p>
 * The provider has to travel with the request: each backend runs its own login flow (NetEase
 * returns a key then a QR image, QQ returns a {@code qrsig}/{@code ptqrtoken} pair), so the server
 * cannot infer which one the player is looking at.
 */
public record StartQRLoginRequest(ApiProvider provider) implements C2SPayload {
    public static final ByteBufCodec<StartQRLoginRequest> CODEC = ByteBufCodec.composite(
            Codecs.ofEnum(ApiProvider.class),
            StartQRLoginRequest::provider,
            StartQRLoginRequest::new
    );

    @Override
    public ApiProvider provider() {
        return provider == null ? ApiProvider.NCM : provider;
    }

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    StartQRLoginRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((startQRLoginRequest, player) -> {
                        var qrLoginInfo = ILoginApiService.getInstance(startQRLoginRequest.provider())
                                .startQRLoginByPlayer(player);
                        IServerNetworkService.getInstance().sendToPlayer(player, new StartQRLoginResponse(qrLoginInfo.data().qrimg()));
                    })
            );
        }
    }
}
