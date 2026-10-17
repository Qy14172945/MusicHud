package indi.etern.musichud.beans.login;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.interfaces.ClientConfig;
import indi.etern.musichud.network.ByteBufCodec;
import indi.etern.musichud.network.Codecs;
import indi.etern.musichud.platform.Environment;
import indi.etern.musichud.server.api.ApiProvider;
import org.apache.logging.log4j.Logger;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * A stored login cookie together with the backend it belongs to.
 * <p>
 * The provider is part of the cookie because the two backends issue completely different cookies
 * for the same player: a NetEase cookie is meaningless to QQ Music and vice versa. Without it, a
 * cookie saved after a QQ login would be replayed against NetEase on the next connect (and fail),
 * and the server could not tell which login service to validate it with.
 */
public record LoginCookieInfo(LoginType type, String rawCookie, ZonedDateTime generateTime, ApiProvider provider) {
    private static final Logger logger = MusicHud.getLogger(LoginCookieInfo.class);
    public static final ByteBufCodec<LoginCookieInfo> STREAM_CODEC =
            ByteBufCodec.composite(
                    LoginType.PACKET_CODEC,
                    LoginCookieInfo::type,
                    Codecs.STRING_UTF8,
                    LoginCookieInfo::rawCookie,
                    Codecs.ZONED_DATE_TIME,
                    LoginCookieInfo::generateTime,
                    Codecs.ofEnum(ApiProvider.class),
                    LoginCookieInfo::provider,
                    LoginCookieInfo::new
            );
    public static final LoginCookieInfo UNLOGGED = new LoginCookieInfo(
            LoginType.UNLOGGED,
            "",
            ZonedDateTime.of(114514, 1, 9, 1, 9, 8, 10, ZoneId.systemDefault()),
            ApiProvider.NCM
    );

    /**
     * Convenience constructor for the NetEase-only call sites and for cookies persisted before the
     * provider field existed.
     */
    public LoginCookieInfo(LoginType type, String rawCookie, ZonedDateTime generateTime) {
        this(type, rawCookie, generateTime, ApiProvider.NCM);
    }

    /**
     * Never null, so callers can route a cookie without a second null check. Cookies written by
     * older builds deserialize without the field and are treated as NetEase, which is what they
     * were.
     */
    @Override
    public ApiProvider provider() {
        return provider == null ? ApiProvider.NCM : provider;
    }
    private static ClientConfig clientConfig;
    static {
        if (MusicHud.getCurrentEnvironment().getSide() == Environment.Side.CLIENT) {
            try {
                clientConfig = ClientConfig.getInstance();
            } catch (UnsupportedOperationException e) {
                clientConfig = null;
            }
        }
    }

    public static LoginCookieInfo clientCurrentCookie() {
        Environment.Side side = MusicHud.getCurrentEnvironment().getSide();
        if (side == Environment.Side.CLIENT) {
            if (clientConfig == null) {
                throw new IllegalStateException("\"clientConfig\" is null in client");
            }
            try {
                LoginCookieInfo loginCookieInfo = clientConfig.getClientCookie();
                if (loginCookieInfo == null) {
                    return UNLOGGED;
                }
                return loginCookieInfo;
            } catch (RuntimeException e) {
                return UNLOGGED;
            }
        } else {
            throw new IllegalStateException("Cannot invoke \"LoginCookieInfo.getClientCookie\" in server");
        }
    }

    public static void setClientCookie(LoginCookieInfo loginCookieInfo) {
        try {
            clientConfig.setClientCookie(loginCookieInfo);
            clientConfig.save();
            logger.info("Login cookie saved");
        } catch (RuntimeException e) {
            logger.error("Exception occurred when serializing login cookie and save", e);
        }
    }

    /**
     * Every cookie the client currently holds, one per backend.
     * <p>
     * Logging in happens per backend, and so does re-logging in on connect: the client replays
     * <em>all</em> of these, which is what keeps a NetEase session and a QQ session alive at the
     * same time. The old single-slot storage dropped whichever one was saved first.
     */
    public static Map<ApiProvider, LoginCookieInfo> clientCurrentCookies() {
        if (MusicHud.getCurrentEnvironment().getSide() != Environment.Side.CLIENT) {
            throw new IllegalStateException("Cannot invoke \"LoginCookieInfo.clientCurrentCookies\" in server");
        }
        if (clientConfig == null) {
            throw new IllegalStateException("\"clientConfig\" is null in client");
        }
        try {
            Map<ApiProvider, LoginCookieInfo> cookies = clientConfig.getClientCookies();
            return cookies == null ? Map.of() : cookies;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Drops every stored cookie. "Log out" in the UI means every backend. */
    public static void clearClientCookies() {
        if (clientConfig == null) {
            return;
        }
        try {
            for (ApiProvider provider : ApiProvider.values()) {
                clientConfig.removeClientCookie(provider);
            }
            // Clear the pre-per-provider slot too, or the next launch would resurrect the login
            // through the legacy migration path in getClientCookies().
            clientConfig.setClientCookie(UNLOGGED);
            clientConfig.save();
        } catch (RuntimeException e) {
            logger.error("Exception occurred when clearing login cookies", e);
        }
    }

    /**
     * Saves this cookie into its own backend's slot. The other backends keep their sessions.
     */
    public void setToClientCookie() {
        if (clientConfig == null) {
            return;
        }
        try {
            clientConfig.setClientCookie(provider(), this);
            clientConfig.save();
            logger.info("Login cookie saved for {}", provider());
        } catch (RuntimeException e) {
            logger.error("Exception occurred when serializing login cookie and save", e);
        }
    }
}
