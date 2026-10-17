package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.beans.login.LoginCookieInfo;
import indi.etern.musichud.interfaces.CommonRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.Codecs;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.server.api.ILoginApiService;
import indi.etern.musichud.utils.ServerDataPacketVThreadExecutor;

/**
 * Re-logs a player in with a cookie they already hold.
 * <p>
 * The backend is taken from the cookie itself rather than from configuration: the cookie was
 * issued by one specific service and is only meaningful there, so replaying it anywhere else
 * always fails. {@link LoginCookieInfo#provider()} defaults to NetEase for cookies saved before
 * the provider existed, preserving the old behaviour.
 */
public record CookieLoginRequest(LoginCookieInfo loginCookieInfo, boolean tryRefresh) implements C2SPayload {
    public static final ByteBufCodec<CookieLoginRequest> CODEC =
            ByteBufCodec.composite(
                    LoginCookieInfo.STREAM_CODEC,
                    CookieLoginRequest::loginCookieInfo,
                    Codecs.BOOL,
                    CookieLoginRequest::tryRefresh,
                    CookieLoginRequest::new
            );

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    CookieLoginRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((loginRequest, player) -> {
                        ILoginApiService.getInstance(loginRequest.loginCookieInfo.provider())
                                .loginWithCookie(loginRequest.loginCookieInfo, loginRequest.tryRefresh, player);
                    })
            );
        }

    }
}
