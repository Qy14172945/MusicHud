package indi.etern.musichud.server.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.api.IdlePlaySource;
import indi.etern.musichud.beans.login.LoginCookieInfo;
import indi.etern.musichud.beans.login.LoginType;
import indi.etern.musichud.beans.music.*;
import indi.etern.musichud.beans.user.Profile;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.interfaces.ServerConfig;
import indi.etern.musichud.interfaces.ServerRegister;
import indi.etern.musichud.network.IPlayerClient;
import indi.etern.musichud.network.IServerNetworkService;
import indi.etern.musichud.network.payloads.pushMessages.s2c.RefreshMusicQueueMessage;
import indi.etern.musichud.network.payloads.pushMessages.s2c.SwitchMusicMessage;
import indi.etern.musichud.network.payloads.pushMessages.s2c.SyncCurrentPlayingMessage;
import indi.etern.musichud.network.payloads.pushMessages.s2c.UpdateAllIdlePlaySourcesMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MusicPlayerServerService {
    private static final ServerConfig serverConfig = ServerConfig.getInstance();
    private static volatile MusicPlayerServerService instance;
    final Map<PusherInfo, Set<IdlePlaySource>> idlePlaySources = new ConcurrentHashMap<>();
    /**
     * Default backend, used for call sites that legitimately have no music entity to inspect.
     * Anything that is about to fetch data <i>for</i> a specific track must go through
     * {@link #apiFor(MusicDetail)} instead, so QQ tracks are served by the QQ backend.
     */
    private final IMusicApiService musicApiService = IMusicApiService.getInstance(ApiProvider.NCM);
    private final CurrentVoteInfo currentVoteInfo = new CurrentVoteInfo();
    private final Logger logger = MusicHud.getLogger(MusicPlayerServerService.class);
    private final Cache<CacheKey, MusicResourceInfo> musicResourceInfoCache = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(20)
            .build();
    private final IServerNetworkService serverNetworkService = IServerNetworkService.getInstance();
    private static final long DEBOUNCE_DELAY_MILLIS = 500;
    private final AtomicInteger debounceToken = new AtomicInteger(0);
    @Getter
    ArrayDeque<MusicDetail> musicQueue = new ArrayDeque<>();
    boolean continuable;
    @Getter
    private volatile MusicDetail currentMusicDetail = MusicDetail.NONE;
    @Getter
    private MusicDetail nextIdleMusicDetail = MusicDetail.NONE;
    private MusicDetail preloadMusicDetail = MusicDetail.NONE;
    @Getter
    private volatile ZonedDateTime nowPlayingStartTime = ZonedDateTime.of(LocalDateTime.MIN, ZoneId.systemDefault());
    private volatile Thread pusherThread;
    private volatile boolean pusherThreadRunning = false;
    private boolean haveSentMusic = false;
    private final Runnable musicPusher = new Runnable() {

        @Override
        public void run() {
            Thread thread = Thread.currentThread();
            thread.setName("MHWorker-Music-Data-Pusher");
            pusherThread = thread;
            pusherThreadRunning = true;
            String message = "";
            while (MusicPlayerServerService.this.continuable) {
                MusicDetail switchedToPlay = null;
                try {
                    Map<UUID, ILoginApiService.PlayerLoginInfo> loginedPlayerInfoMap = allPlayerLoginInfos();
                    if (musicQueue.isEmpty()) {
                        Optional<MusicDetail> optionalMusicDetail = getRandomMusicFromIdleSources();
                        if (optionalMusicDetail.isEmpty()) {
                            break;
                        } else {
                            MusicDetail musicDetail = optionalMusicDetail.get();
                            if (preloadMusicDetail == null || preloadMusicDetail.equals(MusicDetail.NONE)) {
                                preloadMusicDetail = musicDetail;
                                Optional<MusicDetail> optionalMusicDetail1 = getRandomMusicFromIdleSources();
                                if (optionalMusicDetail1.isPresent()) {
                                    switchedToPlay = preloadMusicDetail;
                                    nextIdleMusicDetail = optionalMusicDetail1.get();
                                    preloadMusicDetail = nextIdleMusicDetail;
                                } else {
                                    switchedToPlay = musicDetail;
                                    preloadMusicDetail = MusicDetail.NONE;
                                }
                            } else {
                                switchedToPlay = preloadMusicDetail;
                                preloadMusicDetail = musicDetail;
                            }
                            PusherInfo pusherInfo = switchedToPlay.getPusherInfo();
                            if (pusherInfo != null &&
                                    loginedPlayerInfoMap.keySet().stream().noneMatch(
                                            uuid -> uuid.equals(pusherInfo.getPlayerUUID())
                                    )
                            ) {
                                continue;
                            }
                        }
                    } else {
                        switchedToPlay = musicQueue.remove();
                        serverNetworkService.sendToPlayerInfos(loginedPlayerInfoMap.values(),
                                new RefreshMusicQueueMessage(musicQueue));
                    }

                    nextIdleMusicDetail = preloadMusicDetail != null ? preloadMusicDetail : MusicDetail.NONE;

                    IMusicApiService trackApi = apiFor(switchedToPlay);
                    MusicResourceInfo resourceInfo = trackApi.getResourceInfo(switchedToPlay, Quality.STANDARD, switchedToPlay.getPusherInfo().getPlayerUUID());
                    if (resourceInfo.equals(MusicResourceInfo.NONE)) {
                        continue;
                    }
                    if (switchedToPlay.getLyricInfo() == null || switchedToPlay.getLyricInfo().equals(LyricInfo.NONE)) {
                        switchedToPlay.setLyricInfo(trackApi.getLyricInfo(switchedToPlay));
                    }
                    serverNetworkService.sendToPlayerInfos(
                            loginedPlayerInfoMap.values(),
                            new SwitchMusicMessage(switchedToPlay, nextIdleMusicDetail, message)
                    );
                    message = "";
                    currentVoteInfo.resetTo(switchedToPlay);
                    haveSentMusic = true;
                    currentMusicDetail = switchedToPlay;
                    nowPlayingStartTime = ZonedDateTime.now();
                    logger.info("Switched to music: {} (ID: {})", switchedToPlay.getName(), switchedToPlay.getId());
                    try {
                        int musicIntervalMillis = 1000;
                        //noinspection BusyWait
                        Thread.sleep(switchedToPlay.getDurationMillis() + musicIntervalMillis);
                    } catch (InterruptedException ignored) {//When force switch
                        logger.info("Skip current, switch to nextIdle");
                        message = MusicHud.MOD_ID + ".text.votePassed";
                    }
                } catch (Exception e) {
                    logger.error("Failed to push music: {} (id: {})",
                            switchedToPlay != null ? switchedToPlay.getName() : "null",
                            switchedToPlay != null ? switchedToPlay.getId() : "-1",
                            e);
                    try {
                        //noinspection BusyWait
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            logger.info("Music Pusher stopped");
            pusherThread = null;
            pusherThreadRunning = false;
            MusicPlayerServerService.this.stopSendingMusic();
        }

        private Optional<MusicDetail> getRandomMusicFromIdleSources() {
            if (idlePlaySources.isEmpty()) {
                return Optional.empty();
            }

            List<Map.Entry<PusherInfo, Set<IdlePlaySource>>> entryList =
                    new ArrayList<>(idlePlaySources.entrySet());

            if (entryList.isEmpty()) {
                return Optional.empty();
            }

            Map.Entry<PusherInfo, Set<IdlePlaySource>> randomEntry =
                    entryList.get(MusicHud.RANDOM.nextInt(entryList.size()));

            PusherInfo pusherInfo = randomEntry.getKey();
            Set<IdlePlaySource> idlePlaySource = randomEntry.getValue();

            List<MusicDetail> allTracks = idlePlaySource.stream()
                    .flatMap(playSource -> playSource.getMusicCollection().getMusicDetails().stream())
                    .toList();

            if (allTracks.isEmpty()) {
                return Optional.empty();
            }

            MusicDetail randomTrack = allTracks.get(MusicHud.RANDOM.nextInt(allTracks.size()));
            if (randomTrack.getExtraInfo() == null) {
                MusicDetail detail = resolveDetail(randomTrack.getId(), randomTrack, null);
                if (detail == null) {
                    return Optional.empty();
                }
                if (detail.getId() != randomTrack.getId()) {
                    return Optional.empty();
                }
                randomTrack.setExtraInfo(detail.getExtraInfo());
            }

            if (findLoginInfo(pusherInfo.getPlayerUUID()) != null) {
                randomTrack.setPusherInfo(pusherInfo);
            } else {
                randomTrack.setPusherInfo(PusherInfo.EMPTY);
            }
            return Optional.of(randomTrack);
        }
    };

    public MusicPlayerServerService() {
        updateContinuable(hasAnyLoginStateListener());
    }

    /**
     * Whether any provider has a login-state listener attached. Providers keep separate listener
     * sets, so a single hard-coded one would miss listeners attached to the other backend.
     * <p>
     * Iterates every provider rather than {@link ApiProvider#availableProviders()}: this answers a
     * question about the listener registry, and must agree with the registration-time attachment
     * below even when it runs before the platform config is bound.
     */
    private static boolean hasAnyLoginStateListener() {
        for (ApiProvider provider : ApiProvider.values()) {
            if (!ILoginApiService.getInstance(provider).getLoginStateChangeListeners().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static MusicPlayerServerService getInstance() {
        if (instance == null) {
            synchronized (MusicPlayerServerService.class) {
                if (instance == null) {
                    instance = new MusicPlayerServerService();
                }
            }
        }
        return instance;
    }

    private void updateContinuable(boolean continuable) {
        this.continuable = continuable;
        if (continuable) {
            startMusicPusher();
        }
    }

    private void startMusicPusher() {
        if (!pusherThreadRunning) {
            synchronized (MusicPlayerServerService.class) {
                if (!pusherThreadRunning) {
                    pusherThreadRunning = true;
                    MusicHud.EXECUTOR.execute(musicPusher);
                }
            }
        }
    }

    private void stopSendingMusic() {
        this.continuable = false;
        if (pusherThread != null) {
            pusherThread.interrupt();
        }
        currentMusicDetail = MusicDetail.NONE;
        if (haveSentMusic) {
            haveSentMusic = false;
            serverNetworkService.sendToPlayerInfos(
                    allPlayerLoginInfos().values(),
                    new SwitchMusicMessage(MusicDetail.NONE, MusicDetail.NONE, "")
            );
            currentVoteInfo.resetTo(MusicDetail.NONE);
        }
    }

    public void sendSyncPlayingStatusToPlayer(IPlayerClient player) {
        serverNetworkService.sendToPlayer(player,
                new RefreshMusicQueueMessage(musicQueue));
        sendUpdateAllIdlePlaySourcesMessageTo(Collections.singleton(findLoginInfo(player.getUUID())));
        if (currentMusicDetail != MusicDetail.NONE) {
            haveSentMusic = true;
            serverNetworkService.sendToPlayer(player,
                    new SyncCurrentPlayingMessage(currentMusicDetail, nextIdleMusicDetail, nowPlayingStartTime));
        }
    }

    public void sendUpdateAllIdlePlaySourcesMessageTo(Collection<ILoginApiService.PlayerLoginInfo> playerLoginInfos) {
        List<Playlist> publicPlaylists = new ArrayList<>();
        List<Playlist> privatePlaylists = new ArrayList<>();
        List<Album> albums = new ArrayList<>();
        for (IdlePlaySource playSource : idlePlaySources.values().stream().flatMap(Collection::stream).toList()) {
            MusicCollection musicCollection = playSource.getMusicCollection();
            PusherInfo pusherInfo = playSource.getPusherInfo();
            if (musicCollection instanceof Playlist playlist) {
                if (playlist.getPrivacy() == Privacy.PUBLIC) {
                    publicPlaylists.add(playlist.copyWithPusherInfo(pusherInfo));
                } else {
                    privatePlaylists.add(playlist.copyWithPusherInfo(pusherInfo));
                }
            } else if (musicCollection instanceof Album album) {
                albums.add(album.copyWithPusherInfo(pusherInfo));
            } else {
                throw new IllegalArgumentException("Invalid music collection type");
            }
        }

        for (ILoginApiService.PlayerLoginInfo playerLoginInfo : playerLoginInfos) {
            if (playerLoginInfo != null) {
                Profile playerProfile = playerLoginInfo.getProfile();
                List<Playlist> processedPrivatePlaylists = new ArrayList<>();
                for (Playlist playlist : privatePlaylists) {
                    if (playlist.getCreator().equals(playerProfile)) {
                        processedPrivatePlaylists.add(playlist);
                    } else {
                        processedPrivatePlaylists.add(playlist.copyWithSensitiveErased());
                    }
                }
                processedPrivatePlaylists.addAll(publicPlaylists);
                serverNetworkService.sendToPlayer(playerLoginInfo.getPlayer(), new UpdateAllIdlePlaySourcesMessage(processedPrivatePlaylists, albums));
            }
        }
    }

    private void debouncedUpdateAllIdlePlaySources() {
        final int token = debounceToken.incrementAndGet();
        MusicHud.EXECUTOR.execute(() -> {
            try {
                Thread.sleep(DEBOUNCE_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (debounceToken.get() == token) {
                sendUpdateAllIdlePlaySourcesMessageTo(allPlayerLoginInfos().values());
            }
        });
    }

    public void pushMusicToQueue(long musicDetailId, PusherInfo pusherInfo) {
        // The pushed id is the only information available here, so the registry hint (built while
        // the client was browsing) is what tells a QQ track apart from a NetEase one.
        MusicDetail musicDetail = resolveDetail(musicDetailId, MusicIdRegistry.detailOf(musicDetailId), pusherInfo.getPlayerUUID());
        if (musicDetail == null || musicDetail.getId() != musicDetailId) {
            throw new IllegalStateException();
        }
        musicDetail.setPusherInfo(pusherInfo);
        musicQueue.add(musicDetail);
        serverNetworkService.sendToPlayerInfos(allPlayerLoginInfos().values(),
                new RefreshMusicQueueMessage(musicQueue));
        updateContinuable(true);
    }

    public void removeMusicDetailFromQueue(int index, long id, UUID playerUUID) {
        ArrayList<MusicDetail> list = new ArrayList<>(musicQueue);
        try {
            MusicDetail musicDetail = list.get(index);
            try {
                if (musicDetail.getId() == id) {
                    try {
                        removeMusicInternal(index, musicDetail, playerUUID);
                    } catch (IllegalAccessException ignored) {
                        tryPreviousOne(index, id, playerUUID, list);
                    }
                } else {
                    tryPreviousOne(index, id, playerUUID, list);
                }
            } catch (RuntimeException e) {
                trySimplyRemove(musicDetail, playerUUID);
            }
        } catch (IndexOutOfBoundsException ignored) {
            logger.warn("Failed to remove music from queue as index out of bounds");
        }
    }

    @SneakyThrows
    private void tryPreviousOne(int index, long id, UUID playerUUID, ArrayList<MusicDetail> list) {
        if (index > 1) {//in case the queue just pulled
            MusicDetail musicDetail1 = list.get(index - 1);
            if (musicDetail1.getId() == id) {
                removeMusicInternal(index - 1, musicDetail1, playerUUID);
            } else {
                throw new RuntimeException("failed to remove music from queue");
            }
        } else {
            throw new RuntimeException("failed to remove music from queue");
        }
    }

    private void removeMusicInternal(int index, MusicDetail musicDetail, UUID playerUUID) throws IllegalAccessException {
        if (musicDetail.getPusherInfo().getPlayerUUID().equals(playerUUID)) {
            AtomicInteger index1 = new AtomicInteger(0);
            musicQueue.removeIf(musicDetail1 -> index == index1.getAndIncrement() && musicDetail.equals(musicDetail1));
            serverNetworkService.sendToPlayerInfos(allPlayerLoginInfos().values(),
                    new RefreshMusicQueueMessage(musicQueue));
        } else {
            throw new IllegalAccessException();
        }
    }

    private void trySimplyRemove(MusicDetail musicDetail, UUID playerUUID) {
        if (musicDetail.getPusherInfo().getPlayerUUID().equals(playerUUID)) {
            musicQueue.remove(musicDetail);
            serverNetworkService.sendToPlayerInfos(allPlayerLoginInfos().values(),
                    new RefreshMusicQueueMessage(musicQueue));
        }
    }

    public void addIdlePlaySource(long id, Class<?> type, PusherInfo pusherInfo) {
        Set<IdlePlaySource> musicCollections = idlePlaySources.getOrDefault(pusherInfo, new HashSet<>());
        IdlePlaySource idlePlaySource = new IdlePlaySource(id, type);
        idlePlaySource.setPusherInfo(pusherInfo);
        idlePlaySource.serverLoadMusicCollection(pusherInfo.getPlayerUUID());
        musicCollections.add(idlePlaySource);
        idlePlaySources.put(pusherInfo, musicCollections);
        updateContinuable(true);
        debouncedUpdateAllIdlePlaySources();
    }

    public void removeIdlePlaySource(long id, Class<?> musicCollectionClass, PusherInfo pusherInfo) {
        Set<IdlePlaySource> musicCollections = idlePlaySources.get(pusherInfo);
        if (musicCollections != null) {
            IdlePlaySource idlePlaySource = new IdlePlaySource(id, musicCollectionClass);
            idlePlaySource.setPusherInfo(pusherInfo);
            musicCollections.remove(idlePlaySource);
            if (musicCollections.isEmpty()) {
                idlePlaySources.remove(pusherInfo);
            }
            debouncedUpdateAllIdlePlaySources();
        }
    }

    public void voteSkipCurrent(long id, UUID playerUUID) {
        currentVoteInfo.vote(id, playerUUID);
    }

    public MusicResourceInfo getMusicResourceInfo(long id, Quality quality, String retryFor, UUID playerUUID) {
        try {
            // QQ cannot resolve a numeric id on its own (/getSongInfo is broken upstream and its
            // lyric/play endpoints reject numeric ids), so the provider that minted the id — and its
            // own identifier for the track — both come from the server-side id registry.
            ApiProvider provider = MusicIdRegistry.providerOf(id);
            MusicDetail knownDetail = MusicIdRegistry.detailOf(id);
            List<MusicDetail> musicDetails;
            if (knownDetail != null && knownDetail.getProvider() == provider) {
                musicDetails = List.of(knownDetail);
            } else {
                musicDetails = IMusicApiService.getInstance(provider).getMusicDetailByIds(List.of(id), null);
            }
            if (musicDetails.size() == 1) {
                MusicDetail musicDetail = musicDetails.getFirst();
                try {
                    logger.debug("Try to load music resource info from cache with id: {}", id);
                    MusicResourceInfo musicResourceInfo = musicResourceInfoCache.get(new CacheKey(id, quality),
                            () -> {
                                logger.debug("Cache not found with id: {}, loading", id);
                                return getMusicResourceInfoWithoutCache(quality, musicDetail, playerUUID);
                            });
                    if (musicResourceInfo.getUrl().equals(retryFor)) {
                        logger.debug("Reload music resource info due to client retry for url \"{}\"", retryFor);
                        musicResourceInfo = getMusicResourceInfoWithoutCache(quality, musicDetail, playerUUID);
                        musicResourceInfoCache.put(new CacheKey(id, quality), musicResourceInfo);
                    }
                    return musicResourceInfo;
                } catch (Exception e) {
                    logger.error("Failed to get resource info for music: {}", musicDetail.getName(), e);
                    return MusicResourceInfo.NONE;
                }
            } else if (musicDetails.size() > 1) {
                throw new IllegalStateException();
            } else {
                return MusicResourceInfo.NONE;
            }
        } catch (Exception e) {
            return MusicResourceInfo.NONE;
        }
    }

    private @NotNull MusicResourceInfo getMusicResourceInfoWithoutCache(Quality quality, MusicDetail musicDetail, UUID playerUUID) {
        MusicResourceInfo resourceInfo = apiFor(musicDetail).getResourceInfo(musicDetail, quality, playerUUID);
        if (resourceInfo != null && !resourceInfo.equals(MusicResourceInfo.NONE)) {
            return resourceInfo;
        } else {
            throw new RuntimeException("Failed to get resource info for music: " + musicDetail.getName() + " (ID: " + musicDetail.getId() + ")");
        }
    }

    /**
     * Picks the backend that owns {@code musicDetail}.
     * <p>
     * The bean's own {@link ApiProvider} is authoritative when it has one (search results and
     * album/playlist tracks carry it across the network). Playlists built locally from a bare id
     * — e.g. an idle play source persisted from an earlier session — fall back to the server-side
     * {@link MusicIdRegistry}, and NetEase is the final default so pre-existing behaviour is kept.
     */
    private IMusicApiService apiFor(@Nullable MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail == MusicDetail.NONE) {
            return musicApiService;
        }
        ApiProvider provider = musicDetail.getProvider();
        if (provider == null) {
            provider = MusicIdRegistry.providerOf(musicDetail.getId());
        }
        return IMusicApiService.getInstance(provider);
    }

    /**
     * Resolves a bare numeric id back to a playable {@link MusicDetail}, or {@code null} when no
     * provider can answer for it.
     * <p>
     * The server-side registry is consulted first because QQ cannot re-fetch a track from a numeric
     * id at all ({@code /getSongInfo} is broken upstream and its lyric and play endpoints reject
     * numeric ids) — the remembered bean is the only way a later request can be served. When the
     * registry has nothing, the owning provider is asked the ordinary way, which is how NetEase
     * answers and how QQ recovers tracks it has already seen in this session.
     *
     * @param hint a bean that is already known for this id (may carry the provider and MID), or
     *             {@code null} when only the id is available
     * @param playerUUID the player the fetch is on behalf of, so their (possibly VIP) cookie is
     *                   used and gated tracks resolve. Pass {@code null} only where no player is
     *                   responsible for the lookup, e.g. picking a random idle track.
     */
    @Nullable
    private MusicDetail resolveDetail(long id, @Nullable MusicDetail hint, @Nullable UUID playerUUID) {
        MusicDetail known = MusicIdRegistry.detailOf(id);
        if (known != null) {
            return known;
        }
        ApiProvider provider = hint != null && hint.getProvider() != null
                ? hint.getProvider()
                : MusicIdRegistry.providerOf(id);
        List<MusicDetail> resolved = IMusicApiService.getInstance(provider).getMusicDetailByIds(List.of(id), playerUUID);
        return resolved.size() == 1 ? resolved.getFirst() : null;
    }

    /**
     * Finds the provider through which this player is logged in, or {@code null} when none knows
     * them. Providers keep separate login state, so checking a single hard-coded one would make a
     * player who only logged in to the other look unauthenticated.
     */
    @Nullable
    private static ILoginApiService.PlayerLoginInfo findLoginInfo(UUID playerUUID) {
        if (playerUUID == null) {
            return null;
        }
        for (ApiProvider provider : ApiProvider.availableProviders()) {
            ILoginApiService.PlayerLoginInfo loginInfo =
                    ILoginApiService.getInstance(provider).getLoginInfoByPlayerUUID(playerUUID);
            if (loginInfo != null) {
                return loginInfo;
            }
        }
        return null;
    }

    /**
     * Every logged-in player across all providers, keyed by UUID. Used by the broadcast paths,
     * which must reach QQ-only players as well as NetEase ones.
     * <p>
     * Every connecting player is inserted into <em>each</em> provider's map as
     * {@link LoginType#UNLOGGED}, so one UUID normally appears several times and a plain
     * {@code putAll} would let a later provider's blank entry clobber a real login. A genuinely
     * logged-in entry therefore wins; on a tie the first provider wins, which keeps NetEase
     * precedence and matches {@link #findLoginInfo}.
     */
    private static Map<UUID, ILoginApiService.PlayerLoginInfo> allPlayerLoginInfos() {
        Map<UUID, ILoginApiService.PlayerLoginInfo> merged = new LinkedHashMap<>();
        for (ApiProvider provider : ApiProvider.availableProviders()) {
            for (Map.Entry<UUID, ILoginApiService.PlayerLoginInfo> entry
                    : ILoginApiService.getInstance(provider).getPlayerInfoMap().entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), MusicPlayerServerService::preferLoggedIn);
            }
        }
        return merged;
    }

    private static ILoginApiService.PlayerLoginInfo preferLoggedIn(
            ILoginApiService.PlayerLoginInfo first, ILoginApiService.PlayerLoginInfo second) {
        return isLoggedIn(first) || !isLoggedIn(second) ? first : second;
    }

    private static boolean isLoggedIn(ILoginApiService.PlayerLoginInfo loginInfo) {
        LoginCookieInfo cookieInfo = loginInfo.getLoginCookieInfo();
        return cookieInfo != null && cookieInfo.type() != LoginType.UNLOGGED;
    }

    public void removeAllIdlePlaySource(PusherInfo pusherInfo) {
        idlePlaySources.remove(pusherInfo);
        debouncedUpdateAllIdlePlaySources();
    }

    public void reset() {
        debounceToken.incrementAndGet();
        musicQueue.clear();
        idlePlaySources.clear();
        stopSendingMusic();
        haveSentMusic = false;
        nextIdleMusicDetail = MusicDetail.NONE;
        preloadMusicDetail = MusicDetail.NONE;
    }

    private record CacheKey(long musicId, Quality quality) {
    }

    @RegisterMark
    public static class Register implements ServerRegister {
        @Override
        public void register() {
            // Attach to every provider, not just the currently advertised ones: the QQ API address
            // is operator-configurable, so a provider can become available after this runs, and a
            // listener attached late would miss the login that enabled it.
            for (ApiProvider provider : ApiProvider.values()) {
                ILoginApiService.getInstance(provider).getLoginStateChangeListeners().add((set) -> {
                    if (instance != null) {
                        instance.updateContinuable(!set.isEmpty());
                    }
                });
            }
        }
    }

    @Getter
    @Setter
    private class CurrentVoteInfo {
        final Set<UUID> votedPlayers = new HashSet<>();
        MusicDetail musicDetail;
        float voteRate;

        public void vote(long id, UUID playerUUID) {
            if (!votedPlayers.contains(playerUUID) && musicDetail.getId() == id) {
                votedPlayers.add(playerUUID);
                voteRate += 1.0f / allPlayerLoginInfos().size();
                if (musicDetail.getPusherInfo().getPlayerUUID().equals(playerUUID)) {
                    voteRate += (float) serverConfig.getPusherVoteAdditionalRate();
                    logger.info("Pusher player \"{}\" voted for skip current music {}:{}", playerUUID, id, musicDetail.getName());
                } else {
                    logger.info("Player \"{}\" voted for skip current music {}:{}", playerUUID, id, musicDetail.getName());
                }
                voteRate = Math.clamp(voteRate, 0.0f, 1.0f);
                if (voteRate >= 0.5) {
                    logger.info("Try to skip current music as voting rate reach: {} >= 0.5", voteRate);
                    if (pusherThread != null) {
                        pusherThread.interrupt();
                    }
                    resetTo(MusicDetail.NONE);
                }
            }
        }

        public void resetTo(MusicDetail musicDetail) {
            this.musicDetail = musicDetail;
            voteRate = 0;
            votedPlayers.clear();
        }
    }
}
