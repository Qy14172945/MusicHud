package indi.etern.musichud.network.payloads.pushMessages.c2s;

import indi.etern.musichud.interfaces.CommonRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.INetworkRegister;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.ILoginApiService;
import indi.etern.musichud.utils.ServerDataPacketVThreadExecutor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Logs the player out of every backend.
 * <p>
 * There is no provider field on purpose: a player can hold a NetEase session and a QQ session at
 * the same time, and "log out" from the UI means both. Each service only drops its own player
 * entry, so logging out of a backend the player never used is harmless.
 */
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class LogoutMessage implements C2SPayload {
    public static final LogoutMessage MESSAGE = new LogoutMessage();
    public static final ByteBufCodec<LogoutMessage> CODEC = ByteBufCodec.unit(MESSAGE);

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    LogoutMessage.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((message, player) -> {
                        for (ApiProvider provider : ApiProvider.availableProviders()) {
                            ILoginApiService.getInstance(provider).logout(player);
                        }
                    })
            );
        }
    }
}
