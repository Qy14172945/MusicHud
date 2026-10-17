package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.interfaces.CommonRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.Codecs;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.ILoginApiService;
import indi.etern.musichud.utils.ServerDataPacketVThreadExecutor;

/**
 * Abandons the in-flight QR login for {@code provider}. Each backend polls its own QR session, so
 * the cancellation must name the same provider the QR was requested from.
 */
public record CancelQRLoginRequest(ApiProvider provider) implements C2SPayload {
    public static final ByteBufCodec<CancelQRLoginRequest> CODEC = ByteBufCodec.composite(
            Codecs.ofEnum(ApiProvider.class),
            CancelQRLoginRequest::provider,
            CancelQRLoginRequest::new
    );

    @Override
    public ApiProvider provider() {
        return provider == null ? ApiProvider.NCM : provider;
    }

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    CancelQRLoginRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((cancelQRLoginRequest, player) ->
                            ILoginApiService.getInstance(cancelQRLoginRequest.provider()).cancelQRLoginByPlayer(player))
            );
        }
    }
}
