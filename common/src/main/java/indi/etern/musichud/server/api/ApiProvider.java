package indi.etern.musichud.server.api;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.login.LoginType;
import indi.etern.musichud.interfaces.ServerConfig;
import indi.etern.musichud.platform.Environment;
import indi.etern.musichud.utils.ClientDistUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public enum ApiProvider {
    /**
     * NetEase Cloud Music (网易云音乐), backed by the NCM API Enhanced server.
     */
    NCM(MusicHud.MOD_ID + ".apiProvider.NCM"),
    /**
     * QQ Music (QQ音乐), backed by the {@code qq-music-api} server.
     */
    QQ(MusicHud.MOD_ID + ".apiProvider.QQ");

    /**
     * Translation key of the provider's display name. Resolved lazily in {@link #toString()} so
     * the enum can be loaded before the language files are.
     */
    private final String displayNameKey;

    ApiProvider(String displayNameKey) {
        this.displayNameKey = displayNameKey;
    }

    /**
     * Providers this server can actually serve.
     * <p>
     * NetEase is always advertised because its API process is managed by MusicHud itself. QQ runs
     * as a separate Node.js process the operator has to start, so it is only advertised once an
     * address for it is configured ({@code qqApiBaseUrl} defaults to {@code http://localhost:3200}).
     * The value is read defensively: this is called while the server is still starting up, when
     * the platform config may not be bound yet.
     */
    public static List<ApiProvider> availableProviders() {
        List<ApiProvider> providers = new ArrayList<>(2);
        providers.add(NCM);
        try {
            String qqBaseUrl = ServerConfig.getInstance().getQqApiBaseUrl();
            if (qqBaseUrl != null && !qqBaseUrl.isBlank()) {
                providers.add(QQ);
            }
        } catch (Exception ignored) {
            // Config layer not ready — advertise only the provider MusicHud manages itself.
        }
        return List.copyOf(providers);
    }

    /**
     * The provider this player is actually logged in to.
     * <p>
     * Personal views (subscribed playlists/albums/artists) are per-account, so they have to be
     * fetched from whichever service holds the player's session — asking the other one would
     * return an empty list for a player who is plainly logged in. Falls back to NetEase when no
     * backend recognises the player, preserving the behaviour of the NetEase-only builds.
     */
    public static ApiProvider providerOfPlayer(UUID playerUUID) {
        return providersLoggedInFor(playerUUID).get(0);
    }

    /**
     * Every provider that holds a real session for this player, in {@link #availableProviders()}
     * order.
     * <p>
     * A player can be logged in to both backends at once, and personal views are per-account — so
     * the account page has to ask <em>each</em> service the player actually logged in to, not just
     * the first one. Falls back to NetEase when no backend recognises the player, preserving the
     * behaviour of the NetEase-only builds (and giving an unauthenticated player the public
     * listing rather than an empty page).
     */
    public static List<ApiProvider> providersLoggedInFor(UUID playerUUID) {
        List<ApiProvider> providers = new ArrayList<>(2);
        if (playerUUID != null) {
            for (ApiProvider provider : availableProviders()) {
                ILoginApiService.PlayerLoginInfo loginInfo;
                try {
                    loginInfo = ILoginApiService.getInstance(provider).getLoginInfoByPlayerUUID(playerUUID);
                } catch (Exception e) {
                    continue;
                }
                if (loginInfo == null || loginInfo.getLoginCookieInfo() == null) {
                    continue;
                }
                LoginType type = loginInfo.getLoginCookieInfo().type();
                // Anonymous is not an account: it carries no subscriptions, and treating it as a
                // session would make the account page claim a login the user never made.
                if (type == LoginType.UNLOGGED || type == LoginType.ANONYMOUS) {
                    continue;
                }
                providers.add(provider);
            }
        }
        return providers.isEmpty() ? List.of(NCM) : List.copyOf(providers);
    }

    /**
     * Display name shown in the config UI, localized like every other user-visible enum in this
     * project (see {@link indi.etern.musichud.beans.music.Quality}). The server side keeps the
     * plain constant name because it has no language files. The enum constant name stays
     * available via {@link #name()} for config storage, packet encoding and
     * {@link #valueOf(String)}.
     */
    @Override
    public String toString() {
        try {
            if (MusicHud.getCurrentEnvironment().getSide() != Environment.Side.CLIENT) {
                return name();
            }
            return ClientDistUtil.getI18n(displayNameKey);
        } catch (Exception e) {
            // Environment or language files not bound yet (early startup, server side, logging).
            return name();
        }
    }
}

