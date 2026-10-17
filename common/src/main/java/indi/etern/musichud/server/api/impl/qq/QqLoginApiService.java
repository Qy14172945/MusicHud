package indi.etern.musichud.server.api.impl.qq;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.Version;
import indi.etern.musichud.beans.login.LoginCookieInfo;
import indi.etern.musichud.beans.login.LoginType;
import indi.etern.musichud.beans.music.PusherInfo;
import indi.etern.musichud.beans.user.Profile;
import indi.etern.musichud.beans.user.VipType;
import indi.etern.musichud.network.IPlayerClient;
import indi.etern.musichud.network.IServerNetworkService;
import indi.etern.musichud.network.payloads.pushMessages.s2c.LoginResultMessage;
import indi.etern.musichud.network.payloads.requestResponseCycle.ConnectResponse;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.ILoginApiService;
import indi.etern.musichud.server.api.MusicPlayerServerService;
import indi.etern.musichud.utils.JsonUtil;
import indi.etern.musichud.utils.http.ApiClient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ Music implementation of {@link ILoginApiService}, backed by the standalone
 * {@code @sansenjian/qq-music-api} Node.js process.
 * <p>
 * <b>What QQ supports.</b> QR scan login only ({@code /getQQLoginQr} +
 * {@code POST /checkQQLoginQr}). QQ has no phone/email/password endpoints at all, no anonymous
 * cookie and no cookie refresh, so those methods either fall back to an already-stored cookie or
 * report a failure to the player.
 * <p>
 * <b>Why the QR session lives here.</b> {@code /checkQQLoginQr} needs both the {@code qrsig} and
 * the {@code ptqrtoken} that {@code /getQQLoginQr} minted for the same QR image, so each player's
 * in-flight QR pair is tracked in {@link #pendingQrSessions} while the polling virtual thread
 * runs.
 */
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class QqLoginApiService implements ILoginApiService {
    private static final Logger logger = MusicHud.getLogger(QqLoginApiService.class);
    private static final IServerNetworkService serverNetworkService = IServerNetworkService.getInstance();

    /**
     * {@code uin=o0123456789} in a QQ cookie; the leading {@code o} is a WeChat/QQ prefix, not a
     * digit, so it is stripped before the value is used as a numeric user id.
     */
    private static final Pattern UIN_PATTERN = Pattern.compile("(?:^|;\\s*)uin=(?:o)?(\\d+)");

    private static volatile QqLoginApiService qqLoginApiService;

    private final Map<IPlayerClient, Runnable> pollingMap = new HashMap<>();
    private final Map<IPlayerClient, QrSession> pendingQrSessions = new ConcurrentHashMap<>();
    @Getter
    private final Map<UUID, PlayerLoginInfo> playerInfoMap = new HashMap<>();
    @Getter
    private final Set<Consumer<Collection<PlayerLoginInfo>>> loginStateChangeListeners = new HashSet<>();

    private final Gson gson = JsonUtil.gson;

    public static QqLoginApiService getInstance() {
        if (qqLoginApiService == null) {
            synchronized (QqLoginApiService.class) {
                if (qqLoginApiService == null) {
                    qqLoginApiService = new QqLoginApiService();
                }
            }
        }
        return qqLoginApiService;
    }

    // ------------------------------------------------------------------ transport

    @Nullable
    private String getText(String pathAndQuery, @Nullable String cookie) {
        try {
            return ApiClient.get(QqServerApiMeta.text(pathAndQuery), cookie, false);
        } catch (Exception e) {
            logger.warn("QQ Music request failed: {} ({})", pathAndQuery, e.toString());
            return null;
        }
    }

    @Nullable
    private String postText(String path, Object body, @Nullable String cookie) {
        try {
            JsonObject requestBody = body instanceof JsonObject jsonObject
                    ? jsonObject
                    : gson.toJsonTree(body).getAsJsonObject();
            return ApiClient.post(QqServerApiMeta.text(path), requestBody, cookie, false);
        } catch (Exception e) {
            logger.warn("QQ Music request failed: {} ({})", path, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ cookie helpers

    /**
     * Extracts the numeric QQ account id from a raw cookie, or {@code null} when absent.
     * Public because the QQ user-library endpoints require {@code uin} as a query parameter.
     */
    @Nullable
    public static Long uinOf(@Nullable String rawCookie) {
        if (rawCookie == null || rawCookie.isEmpty()) {
            return null;
        }
        Matcher matcher = UIN_PATTERN.matcher(rawCookie);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            // QQ uins can exceed Long.MAX_VALUE; without a numeric id the profile stays anonymous.
            return null;
        }
    }

    private static void sendSuccessLoginResultTo(IPlayerClient player, LoginCookieInfo loginCookieInfo,
                                                 Profile profile) {
        serverNetworkService.sendToPlayer(player, new LoginResultMessage(true, "", loginCookieInfo, profile));
        MusicPlayerServerService.getInstance().sendUpdateAllIdlePlaySourcesMessageTo(
                Collections.singleton(getInstance().getLoginInfoByPlayerUUID(player.getUUID())));
    }

    private static void sendLoginFailResult(IPlayerClient player, String message) {
        serverNetworkService.sendToPlayer(player,
                new LoginResultMessage(false, message, LoginCookieInfo.UNLOGGED, Profile.ANONYMOUS));
    }

    // ------------------------------------------------------------------ anonymous / unlogged

    /**
     * QQ offers no anonymous cookie: every playable URL requires a real logged-in account.
     * Returns an empty string so callers can fall through to an unauthenticated request.
     */
    @Override
    public String getAnonymousCookie() {
        return "";
    }

    /**
     * QQ has no anonymous VIP cookie pool; the supplied fallback is returned unchanged.
     */
    @Override
    public String randomVipCookieOrElse(Supplier<String> defaultCookieSupplier) {
        return defaultCookieSupplier == null ? "" : defaultCookieSupplier.get();
    }

    @Override
    public void joinUnlogged(IPlayerClient player) {
        playerInfoMap.put(player.getUUID(), PlayerLoginInfo.of(player, LoginCookieInfo.UNLOGGED));
        loginStateChangeListeners.forEach(listener -> listener.accept(playerInfoMap.values()));
        MusicPlayerServerService.getInstance().sendUpdateAllIdlePlaySourcesMessageTo(
                Collections.singleton(getLoginInfoByPlayerUUID(player.getUUID())));
    }

    @Override
    public void logout(IPlayerClient player) {
        Runnable remove = pollingMap.remove(player);
        pendingQrSessions.remove(player);
        playerInfoMap.remove(player.getUUID());
        loginStateChangeListeners.forEach(listener -> listener.accept(playerInfoMap.values()));
        if (remove != null) {
            logger.debug("QQ QR polling stopped as player {} quit", player.getName());
        }
        MusicPlayerServerService.getInstance().removeAllIdlePlaySource(getPusherInfo(player));
    }

    /**
     * QQ cannot log in without a scan; the player is reported a failure so the UI can prompt for
     * a QR scan instead.
     */
    @Override
    public void loginAsAnonymous(IPlayerClient player, boolean sendFail) {
        if (sendFail) {
            sendLoginFailResult(player, MusicHud.MOD_ID + ".text.login.qqRequiresQr");
        }
    }

    /**
     * QQ exposes no cookie refresh endpoint, so the stored cookie is revalidated by fetching the
     * profile with it and the outcome is pushed back to the player.
     */
    @Override
    public void refreshAndSend(IPlayerClient player, LoginCookieInfo loginCookieInfo) {
        try {
            Profile profile = loadUserProfile(player, loginCookieInfo);
            serverNetworkService.sendToPlayer(player,
                    new LoginResultMessage(true, "", loginCookieInfo, profile));
        } catch (Exception e) {
            logger.warn("QQ cookie revalidation failed for player {}", player.getName(), e);
            sendLoginFailResult(player, loginCookieInfo.rawCookie() != null && !loginCookieInfo.rawCookie().isEmpty()
                    ? MusicHud.MOD_ID + ".text.login.cookieExpired"
                    : MusicHud.MOD_ID + ".text.login.qqRequiresQr");
        }
    }

    @Override
    public PusherInfo getPusherInfo(IPlayerClient player) {
        PlayerLoginInfo loginInfo = playerInfoMap.get(player.getUUID());
        if (loginInfo == null) {
            return PusherInfo.EMPTY;
        }
        return new PusherInfo(player.getUUID(), player.getName());
    }

    // ------------------------------------------------------------------ QR login

    /**
     * The {@code qrsig}/{@code ptqrtoken} pair that {@code /checkQQLoginQr} requires for one
     * in-flight QR image.
     */
    private record QrSession(String qrSig, long ptqrToken) {
    }

    @Override
    public QRLoginData startQRLoginByPlayer(IPlayerClient player) {
        logger.debug("Start QQ QR login for player: {}", player.getName());
        String body = getText(QqServerApiMeta.QQ_LOGIN_QR, null);
        if (body == null) {
            sendLoginFailResult(player, MusicHud.MOD_ID + ".error.apiServer");
            throw new IllegalStateException("QQ Music login QR request failed");
        }
        QqQrResponse qr;
        try {
            qr = gson.fromJson(body, QqQrResponse.class);
        } catch (Exception e) {
            sendLoginFailResult(player, MusicHud.MOD_ID + ".error.apiServer");
            throw new IllegalStateException("Failed to parse QQ Music login QR response", e);
        }
        if (qr == null || qr.qrSig == null || qr.qrSig.isEmpty()) {
            sendLoginFailResult(player, MusicHud.MOD_ID + ".error.apiServer");
            throw new IllegalStateException("QQ Music login QR response carried no qrsig");
        }
        // The QR image is delivered as a ready-to-embed data URL; the client renders it verbatim.
        pendingQrSessions.put(player, new QrSession(qr.qrSig, qr.ptqrToken));
        startQRPollingVThread(player, qr.qrSig, qr.ptqrToken);
        // Data is nested inside QRLoginData; there is no separate scan URL for QQ.
        return new QRLoginData(0, new QRLoginData.Data("", Objects.requireNonNullElse(qr.img, "")));
    }

    private void startQRPollingVThread(IPlayerClient player, String qrSig, long ptqrToken) {
        var ref = new Object() {
            Runnable runnable = null;
        };
        ref.runnable = () -> {
            Thread.currentThread().setName("MHWorker-QqPolling-V" + Thread.currentThread().hashCode());
            try {
                logger.info("Start QQ QR login polling v-thread for player: {}", player.getName());
                QqQrCheckResult result = null;
                do {
                    Thread.sleep(Duration.of(5, ChronoUnit.SECONDS));

                    if (pollingMap.get(player) != ref.runnable) {
                        logger.debug("QQ QR polling v-thread stopped for player {}", player.getName());
                        return;
                    }

                    String body = postText(QqServerApiMeta.CHECK_QQ_LOGIN_QR,
                            new QqQrCheckRequest(qrSig, ptqrToken), null);
                    if (body == null) {
                        continue;
                    }
                    try {
                        result = gson.fromJson(body, QqQrCheckResult.class);
                    } catch (Exception e) {
                        logger.debug("Unparsable QQ QR check response for {}: {}",
                                player.getName(), e.toString());
                        continue;
                    }
                    if (result == null) {
                        continue;
                    }
                    logger.debug("QQ QR polling for {} got: isOk={} refresh={} message={}",
                            player.getName(), result.isOk, result.refresh, result.message);

                    if (result.isOk) {
                        String cookie = result.session != null ? result.session.cookie : null;
                        if (cookie == null || cookie.isEmpty()) {
                            sendLoginFailResult(player, MusicHud.MOD_ID + ".text.unknownError");
                            return;
                        }
                        LoginCookieInfo loginCookieInfo =
                                new LoginCookieInfo(LoginType.QR_CODE, cookie, ZonedDateTime.now(),
                                        ApiProvider.QQ);
                        Profile profile = loadUserProfile(player, loginCookieInfo);
                        sendSuccessLoginResultTo(player, loginCookieInfo, profile);
                        return;
                    }
                    if (result.refresh) {
                        // The QR image expired; the player has to request a new one.
                        serverNetworkService.sendToPlayer(player,
                                new LoginResultMessage(false,
                                        MusicHud.MOD_ID + ".text.login.qrExpired",
                                        LoginCookieInfo.UNLOGGED,
                                        Profile.ANONYMOUS));
                        logger.debug("QQ QR code expired for player: {}", player.getName());
                        return;
                    }
                } while (true);
            } catch (InterruptedException e) {
                logger.warn("Thread ({}) interrupted while polling for QQ QR login status",
                        Thread.currentThread().getName(), e);
            } catch (Exception e) {
                logger.error("QQ QR login polling failed for player {}", player.getName(), e);
                sendLoginFailResult(player, e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ":" + e.getMessage() : ""));
            } finally {
                pendingQrSessions.remove(player);
                logger.info("QQ QR polling v-thread finished for player {}", player.getName());
            }
        };
        pollingMap.put(player, ref.runnable);
        MusicHud.EXECUTOR.execute(ref.runnable);
    }

    @Override
    public void cancelQRLoginByPlayer(IPlayerClient player) {
        pollingMap.remove(player);
        pendingQrSessions.remove(player);
    }

    // ------------------------------------------------------------------ profile

    /**
     * Loads the QQ profile for a cookie. QQ returns the account behind the cookie from
     * {@code /user/getUserDetail}; when that call is unavailable the cookie's own {@code uin} is
     * used so the player still gets a stable identity.
     */
    @Override
    public Profile loadUserProfile(IPlayerClient player, LoginCookieInfo loginCookieInfo) {
        if (loginCookieInfo.type() == LoginType.ANONYMOUS) {
            return Profile.ANONYMOUS;
        }
        Long uin = uinOf(loginCookieInfo.rawCookie());
        Profile profile = null;
        if (uin != null) {
            String body = getText(QqServerApiMeta.USER_DETAIL + "?uin=" + uin,
                    loginCookieInfo.rawCookie());
            profile = parseProfile(body, uin);
        }
        if (profile == null) {
            if (uin == null) {
                throw new IllegalStateException("QQ cookie carried no usable uin");
            }
            profile = new Profile(String.valueOf(uin), "", uin);
        }
        PlayerLoginInfo playerLoginInfo = PlayerLoginInfo.of(player, loginCookieInfo);
        playerLoginInfo.appendProfile(profile);
        playerInfoMap.put(player.getUUID(), playerLoginInfo);
        loginStateChangeListeners.forEach(listener -> listener.accept(playerInfoMap.values()));
        return profile;
    }

    /**
     * QQ user payloads nest differently per endpoint; nickname/avatar are looked up across the
     * documented shapes and the numeric {@code uin} is used as a last resort.
     */
    @Nullable
    private Profile parseProfile(@Nullable String body, long uin) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null) {
                return null;
            }
            // /user/getUserDetail answers {"response":{"code":0,"msg":"",
            // "data":{"creator":{"nick":…,"headpic":…}}}}, i.e. the identity sits three levels
            // deep and under no fixed key, so descend until an object that actually carries a
            // nickname or an avatar is found.
            JsonObject node = findProfileNode(root, 0);
            if (node == null) {
                return null;
            }
            String nickname = firstString(node, "nick", "nickname", "name", "userName");
            String avatar = firstString(node, "headpic", "headurl", "headUrl", "avatarUrl", "avatar", "pic");
            Long id = firstLong(node, "uin", "userid", "userId", "encrypt_uin");
            if (nickname == null && avatar == null) {
                return null;
            }
            // creator.uin is 0 in some payloads, so a non-positive id is discarded in favour of
            // the uin parsed out of the login cookie.
            long resolvedId = id != null && id > 0 ? id : uin;
            Profile profile = new Profile(Objects.requireNonNullElse(nickname, String.valueOf(uin)),
                    Objects.requireNonNullElse(avatar, ""), resolvedId);
            profile.setVipType(parseVipType(node));
            return profile;
        } catch (Exception e) {
            logger.warn("Failed to parse QQ user detail response: {}", e.toString());
            return null;
        }
    }

    /**
     * QQ encodes the account tier as {@code vip}/{@code svip} flags or numeric {@code vipType}
     * codes; anything unrecognised stays {@link VipType#NORMAL}.
     */
    private static VipType parseVipType(JsonObject node) {
        for (String field : List.of("vipType", "vip_type", "viptype", "vip")) {
            var element = node.get(field);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.getAsJsonPrimitive().isNumber()) {
                int code = element.getAsInt();
                if (code > 0) {
                    return code >= 20 ? VipType.SVIP : VipType.VIP;
                }
            } else if (element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean()) {
                return VipType.VIP;
            }
        }
        for (String field : List.of("isVip", "is_vip", "isSvip", "is_svip", "green", "green_vip")) {
            var element = node.get(field);
            if (element != null && element.isJsonPrimitive() && element.getAsBoolean()) {
                return field.toLowerCase(Locale.ROOT).contains("svip") ? VipType.SVIP : VipType.VIP;
            }
        }
        return VipType.NORMAL;
    }

    /**
     * Depth-first search for the object that carries the account identity. The marker keys are
     * deliberately limited to nickname/avatar fields: generic keys such as {@code name} or
     * {@code pic} appear on many sibling objects and would match the wrong node.
     */
    @Nullable
    private static JsonObject findProfileNode(JsonObject node, int depth) {
        if (node == null || depth > 5) {
            return null;
        }
        if (firstString(node, "nick", "nickname", "headpic", "headurl", "headUrl") != null) {
            return node;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry : node.entrySet()) {
            com.google.gson.JsonElement value = entry.getValue();
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject found = findProfileNode(value.getAsJsonObject(), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static JsonObject firstObject(JsonObject root, String... fields) {
        for (String field : fields) {
            var element = root.get(field);
            if (element != null && element.isJsonObject()) {
                JsonObject candidate = element.getAsJsonObject();
                // Prefer the deepest object that actually carries identity fields.
                if (firstString(candidate, "nick", "nickname", "name", "uin", "headurl") != null) {
                    return candidate;
                }
                for (String nested : List.of("data", "resp", "creator", "user", "profile", "info")) {
                    var inner = candidate.get(nested);
                    if (inner != null && inner.isJsonObject()) {
                        return inner.getAsJsonObject();
                    }
                }
                return candidate;
            }
        }
        return null;
    }

    @Nullable
    private static String firstString(JsonObject node, String... fields) {
        for (String field : fields) {
            var element = node.get(field);
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Long firstLong(JsonObject node, String... fields) {
        for (String field : fields) {
            var element = node.get(field);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            try {
                if (element.getAsJsonPrimitive().isNumber()) {
                    return element.getAsLong();
                }
                String value = element.getAsString();
                if (!value.isEmpty()) {
                    return Long.parseLong(value);
                }
            } catch (Exception ignored) {
                // non-numeric identifier — keep looking
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ not supported by QQ

    /**
     * QQ's API surface has no validation-code endpoint; the player is told to scan a QR instead.
     */
    @Override
    public void requestValidationCodeFor(int regionCode, long phone, IPlayerClient player) {
        sendLoginFailResult(player, MusicHud.MOD_ID + ".text.login.qqRequiresQr");
    }

    @Override
    public void loginWithPhoneAndCode(int regionCode, long phone, int code, IPlayerClient player) {
        sendLoginFailResult(player, MusicHud.MOD_ID + ".text.login.qqRequiresQr");
    }

    @Override
    public void loginWithPhoneAndPassword(long phone, String md5password, IPlayerClient player) {
        sendLoginFailResult(player, MusicHud.MOD_ID + ".text.login.qqRequiresQr");
    }

    @Override
    public void loginWithEmailAndPassword(String email, String md5password, IPlayerClient player) {
        sendLoginFailResult(player, MusicHud.MOD_ID + ".text.login.qqRequiresQr");
    }

    // ------------------------------------------------------------------ lookup / lifecycle

    @Override
    public PlayerLoginInfo getLoginInfoByPlayerUUID(UUID playerUUID) {
        if (playerUUID == null) {
            return null;
        }
        return playerInfoMap.get(playerUUID);
    }

    @Override
    public String getRawCookieOrElse(UUID playerUUID, Supplier<String> supplier) {
        if (playerUUID != null) {
            PlayerLoginInfo loginInfo = playerInfoMap.get(playerUUID);
            if (loginInfo != null) {
                return loginInfo.getLoginCookieInfo().rawCookie();
            }
        }
        return supplier != null ? supplier.get() : "";
    }

    /**
     * QQ needs a scan before any cookie exists, so the login is completed eagerly and revalidated
     * lazily; nothing is refreshed here.
     */
    @Override
    public void loginWithCookie(LoginCookieInfo loginCookieInfo, boolean tryToRefresh, IPlayerClient player) {
        if (loginCookieInfo.type() == LoginType.ANONYMOUS) {
            joinUnlogged(player);
            return;
        }
        try {
            Profile profile = loadUserProfile(player, loginCookieInfo);
            serverNetworkService.sendToPlayer(player,
                    new LoginResultMessage(true, "", loginCookieInfo, profile));
        } catch (Exception e) {
            logger.warn("QQ cookie login failed for player {}", player.getName(), e);
            serverNetworkService.sendToPlayer(player,
                    new LoginResultMessage(false, "", loginCookieInfo, Profile.ANONYMOUS));
        }
        MusicPlayerServerService.getInstance().sendUpdateAllIdlePlaySourcesMessageTo(
                Collections.singleton(getLoginInfoByPlayerUUID(player.getUUID())));
    }

    @Override
    public void disconnectToAll() {
        serverNetworkService.sendToPlayerInfos(playerInfoMap.values(),
                new ConnectResponse(false, Version.current, ApiProvider.availableProviders()));
    }

    @Override
    public void reconnectAll() {
        serverNetworkService.sendToPlayerInfos(playerInfoMap.values(),
                new ConnectResponse(true, Version.current, ApiProvider.availableProviders()));
    }

    // ------------------------------------------------------------------ DTOs

    private static final class QqQrResponse {
        @SerializedName("img")
        String img;
        @SerializedName("ptqrtoken")
        long ptqrToken;
        @SerializedName("qrsig")
        String qrSig;
    }

    private record QqQrCheckRequest(String qrsig, long ptqrtoken) {
    }

    private static final class QqQrCheckResult {
        @SerializedName("isOk")
        boolean isOk;
        @SerializedName("refresh")
        boolean refresh;
        @SerializedName("message")
        String message;
        @SerializedName("session")
        QqLoginSession session;
    }

    private static final class QqLoginSession {
        @SerializedName("cookie")
        String cookie;
        @SerializedName("loginUin")
        String loginUin;
        @SerializedName("uin")
        String uin;
    }
}
