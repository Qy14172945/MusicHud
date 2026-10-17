package indi.etern.musichud.interfaces;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.api.AutoConnectServerFilterType;
import indi.etern.musichud.beans.login.LoginCookieInfo;
import indi.etern.musichud.beans.music.Quality;
import indi.etern.musichud.client.config.ProfileConfigData;
import indi.etern.musichud.client.ui.hud.metadata.HorizontalAlign;
import indi.etern.musichud.client.ui.hud.metadata.VerticalAlign;
import indi.etern.musichud.platform.Environment;
import indi.etern.musichud.server.api.ApiProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public interface ClientConfig {
    static ClientConfig getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<ClientConfig> supplier = platform.getClientConfigSupplier();
        if (supplier != null) {
            ClientConfig clientConfig = supplier.get();
            if (clientConfig != null) {
                return clientConfig;
            }
        }
        throw new UnsupportedOperationException();
    }

    boolean getEnable();

    void setEnable(boolean enable);

    boolean getShowTranslatedCnLyrics();

    void setShowTranslatedCnLyrics(boolean showTranslatedCnLyrics);

    boolean getDisableVanillaMusic();

    void setDisableVanillaMusic(boolean disableVanillaMusic);

    boolean getHideHudWhenNotPlaying();

    void setHideHudWhenNotPlaying(boolean hideHudWhenNotPlaying);

    boolean getEnableHud();

    void setEnableHud(boolean enableHud);

    Quality getPrimaryChosenQuality();

    void setPrimaryChosenQuality(Quality primaryChosenQuality);

    /**
     * The music provider the player browses and searches with (NetEase or QQ Music).
     * <p>
     * The server only serves providers it advertises in its {@code ConnectResponse}; a selection
     * the connected server cannot serve is answered by the server's fallback rather than by an
     * error.
     */
    ApiProvider getSelectedApiProvider();

    void setSelectedApiProvider(ApiProvider selectedApiProvider);

    VerticalAlign getHudVerticalPosition();

    void setHudVerticalPosition(VerticalAlign hudVerticalPosition);

    boolean getMixWithVanillaSoundVolume();

    void setMixWithVanillaSoundVolume(boolean mixWithVanillaSoundVolume);

    boolean getMuted();

    void setMuted(boolean muted);

    int getSoundVolume();

    void setSoundVolume(int soundVolume);

    void forceSetSoundVolume(int soundVolume);

    int getSoundVolumeInterval();

    void setSoundVolumeInterval(int soundVolumeInterval);

    HorizontalAlign getHudHorizontalPosition();

    void setHudHorizontalPosition(HorizontalAlign hudHorizontalPosition);

    int getHudOffsetX();

    void setHudOffsetX(int hudOffsetX);

    int getHudOffsetY();

    void setHudOffsetY(int hudOffsetY);

    int getHudWidth();

    void setHudWidth(int hudWidth);

    int getHudHeight();

    void setHudHeight(int hudHeight);

    int getHudCornerRadius();

    void setHudCornerRadius(int hudCornerRadius);

    LoginCookieInfo getClientCookie();

    void setClientCookie(LoginCookieInfo clientCookie);

    /**
     * Every stored login cookie, keyed by the backend that issued it.
     * <p>
     * One slot per provider is what makes a dual login possible: a NetEase cookie is meaningless to
     * QQ Music and vice versa, so storing them in a single slot (the previous behaviour) meant
     * logging in to the second backend silently discarded the first one's session.
     */
    Map<ApiProvider, LoginCookieInfo> getClientCookies();

    /** Stores one backend's cookie, replacing only that backend's slot. */
    void setClientCookie(ApiProvider provider, LoginCookieInfo clientCookie);

    /** Drops one backend's cookie, leaving the other backends logged in. */
    void removeClientCookie(ApiProvider provider);

    /**
     * Whether the search page merges the results of every available backend, ranked by relevance,
     * instead of asking the single selected one.
     */
    boolean getMergeSearchProviders();

    void setMergeSearchProviders(boolean mergeSearchProviders);

    ProfileConfigData getClientAccountConfig();

    void setClientAccountConfig(ProfileConfigData clientAccountConfig);

    boolean getEnabledInIntegratedServer();

    void setEnabledInIntegratedServer(boolean enabledInIntegratedServer);

    boolean getEnableAutoConnect();

    void setEnableAutoConnect(boolean autoConnect);

    boolean getEnableIsolatedMode();

    void setEnableIsolatedMode(boolean autoConnect);

    AutoConnectServerFilterType getConnectServerFilterType();

    void setConnectServerFilterType(AutoConnectServerFilterType autoConnectServerFilterType);

    List<String> getBlackList();

    void setBlackList(List<String> blackList);

    List<String> getWhiteList();

    void setWhiteList(List<String> whiteList);

    void save();

    boolean isConfigured();

    void setConfigured(boolean configured);

    double getMainScreenAdditionalBackgroundDarken();

    void setMainScreenAdditionalBackgroundDarken(double additionalBackgroundDarken);

    double getHudBackgroundMixAlpha();

    void setHudBackgroundMixAlpha(double hudBackgroundMixAlpha);

    boolean getEnableMarqueeText();

    void setEnableMarqueeText(boolean aBoolean);
}
