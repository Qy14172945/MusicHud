package indi.etern.musichud.client.services;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.UIManager;
import icyllis.modernui.widget.Toast;
import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.api.IdlePlaySource;
import indi.etern.musichud.beans.music.*;
import indi.etern.musichud.client.audio.NowPlayingInfo;
import indi.etern.musichud.client.audio.StreamAudioPlayer;
import indi.etern.musichud.client.config.ProfileConfigData;
import indi.etern.musichud.client.ui.ToastUtil;
import indi.etern.musichud.client.ui.hud.HudRendererManager;
import indi.etern.musichud.client.ui.utils.image.ImageUtils;
import indi.etern.musichud.interfaces.ClientConfig;
import indi.etern.musichud.interfaces.ClientRegister;
import indi.etern.musichud.interfaces.IClientEventService;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.IClientNetworkService;
import indi.etern.musichud.network.payloads.pushMessages.c2s.*;
import indi.etern.musichud.network.payloads.requestResponseCycle.*;
import indi.etern.musichud.throwable.ApiException;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MusicService {
    private static final Logger logger = MusicHud.getLogger(MusicService.class);
    private static final Cache<Long, Playlist> playlistCache = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(20)
            .build();
    private static final Cache<Long, Album> albumCache = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maximumSize(20)
            .build();
    private static final IClientNetworkService clientNetworkService = IClientNetworkService.getInstance();
    private static final ProfileConfigData profileConfigData = ProfileConfigData.getInstance();
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private static volatile MusicService instance;
    @Getter
    private final Set<MusicCollection> localIdlePlaySources = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<MusicCollection>> localIdlePlaySourceAddListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<MusicCollection>> localIdlePlaySourceRemoveListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<MusicCollection>> localIdlePlaySourceChangeListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<MusicCollection> serverIdlePlaySources = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<MusicCollection>> serverIdlePlaySourceAddListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<MusicCollection>> serverIdlePlaySourceRemoveListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<MusicCollection>> serverIdlePlaySourceChangeListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Queue<MusicDetail> musicQueue = new ArrayDeque<>();
    @Getter
    private final Set<Consumer<Queue<MusicDetail>>> musicQueueRefreshListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<Consumer<MusicDetail>> musicQueuePushListeners = ConcurrentHashMap.newKeySet();
    @Getter
    private final Set<BiConsumer<Integer, MusicDetail>> musicQueueRemoveListeners = ConcurrentHashMap.newKeySet();
    private boolean initialSyncReceived = false;
    long lastPressTime = 0;
    @Getter
    private boolean idlePlaySourceLoaded = false;

    public static MusicService getInstance() {
        if (instance == null) {
            synchronized (MusicService.class) {
                if (instance == null) {
                    instance = new MusicService();
                }
            }
        }
        return instance;
    }

    private void loadIdlePlaySourceFromConfig() {
        if (!idlePlaySourceLoaded) {
            idlePlaySourceLoaded = true;
            Set<IdlePlaySource> idlePlaySources = profileConfigData.getIdlePlaySources();
            if (!idlePlaySources.isEmpty()) {
                MusicHud.EXECUTOR.execute(() -> {
                    for (IdlePlaySource idlePlaySource : idlePlaySources) {
                        try {
                            loadIdlePlaySource(idlePlaySource.getType(), idlePlaySource.getId()).thenAcceptAsync(musicCollection -> {
                                clientNetworkService.sendToServer(new AddToIdlePlaySourceMessage(idlePlaySource));
                                getInstance().addToIdlePlaySource(musicCollection);
                            }, MusicHud.EXECUTOR);
                        } catch (Exception e) {
                            logger.error("Failed to load idle play source playlist with idlePlaySource:{}", idlePlaySource, e);
                        }
                    }
                });
            }
        }
    }

    public CompletableFuture<? extends MusicCollection> loadIdlePlaySource(Class<?> type, long id) {
        if (type.equals(Album.class)) {
            return loadAlbumDetail(id, false);
        } else if (type.equals(Playlist.class)) {
            return loadPlaylistDetail(id, false);
        }
        return null;
    }

    public CompletableFuture<Playlist> loadPlaylistDetail(long id, boolean ignoreCache) {
        if (!ignoreCache) {
            Playlist cachedPlaylist = playlistCache.getIfPresent(id);
            if (cachedPlaylist != null) {
                return CompletableFuture.completedFuture(cachedPlaylist);
            }
        }
        CompletableFuture<Playlist> completableFuture = new CompletableFuture<>();
        MusicHud.EXECUTOR.execute(() -> {
            GetPlaylistDetailResponse.setReceiver(id, playlist -> {
                if (playlist != null) {
                    playlistCache.put(id, playlist);
                    completableFuture.complete(playlist);
                }
            });
            clientNetworkService.sendToServer(new GetPlaylistDetailRequest(id));
        });
        return completableFuture.orTimeout(5, TimeUnit.SECONDS);
    }

    public CompletableFuture<Album> loadAlbumDetail(long id, boolean ignoreCache) {
        if (!ignoreCache) {
            Album cachedPlaylist = albumCache.getIfPresent(id);
            if (cachedPlaylist != null) {
                return CompletableFuture.completedFuture(cachedPlaylist);
            }
        }
        CompletableFuture<Album> completableFuture = new CompletableFuture<>();
        MusicHud.EXECUTOR.execute(() -> {
            GetAlbumDetailResponse.setReceiver(id, albumInfo -> {
                albumCache.put(id, albumInfo);
                completableFuture.complete(albumInfo);
            });
            clientNetworkService.sendToServer(new GetAlbumDetailRequest(id));
        });
        return completableFuture.orTimeout(5, TimeUnit.SECONDS);
    }

    public void addToIdlePlaySource(MusicCollection idlePlaySourceCollection) {
        PusherInfo pusherInfo = idlePlaySourceCollection.getPusherInfo();
        MusicCollection collection;
        if (pusherInfo != null && pusherInfo != PusherInfo.EMPTY) {
            collection = idlePlaySourceCollection.copyWithPusherInfo(PusherInfo.EMPTY);
        } else {
            collection = idlePlaySourceCollection;
        }
        IdlePlaySource idlePlaySource = new IdlePlaySource(collection.getId(), collection.getClass());
        if (localIdlePlaySources.stream().noneMatch(s -> s.equalsLoose(collection))) {
            localIdlePlaySources.add(collection);
            localIdlePlaySourceAddListeners.forEach(l -> l.accept(collection));
            localIdlePlaySourceChangeListeners.forEach(l -> l.accept(collection));
            profileConfigData.getIdlePlaySources().add(idlePlaySource);
            profileConfigData.saveToConfig();
        }
        clientNetworkService.sendToServer(new AddToIdlePlaySourceMessage(idlePlaySource));
    }

    public void removeFromIdlePlaySource(MusicCollection collection) {
        localIdlePlaySources.removeIf(c -> c.getId() == collection.getId());
        localIdlePlaySourceRemoveListeners.forEach(l -> l.accept(collection));
        localIdlePlaySourceChangeListeners.forEach(l -> l.accept(collection));
        IdlePlaySource idlePlaySource = new IdlePlaySource(collection.getId(), collection.getClass());
        profileConfigData.getIdlePlaySources().remove(idlePlaySource);
        profileConfigData.saveToConfig();
        clientNetworkService.sendToServer(new RemoveFromIdlePlaySourceMessage(idlePlaySource));
    }

    /**
     * Reconciles the local queue with the server's copy.
     *
     * <p>This used to walk an {@link Iterator} over {@code musicQueue} while that same deque was
     * being mutated by {@code removeIf}. The deque's iterator is fail-fast on structural
     * modification, but {@code removeIf} is not a structural modification it observes as such, so
     * the cursor silently lost its place: after the first mismatch every comparison paired the
     * wrong elements. Dropping two tracks from the head at once (a skip immediately followed by
     * another skip) reconciled {@code [A, B, C]} against {@code [C]} into {@code [B]}. The list then
     * disagreed with the server for the rest of the session, so entries stayed on screen after they
     * had been skipped and a removal looked like it did nothing.
     *
     * <p>The reconcile is now done on an {@link ArrayList} snapshot and only then written back, so
     * no iterator is ever held across a mutation. Removals are collected tail-first because the UI
     * listeners below remove a child view by index: clearing the highest index first keeps every
     * still-pending index valid, whereas going head-first would shift them all down by one.
     */
    public synchronized void refreshQueue(Queue<MusicDetail> queue) {
        List<MusicDetail> incoming = new ArrayList<>(queue);
        List<MusicDetail> working = new ArrayList<>(musicQueue);

        // Everything that still matches the server by id at the same position is untouched. The
        // server's order is authoritative, so rather than trying to match up a middle removal with
        // a specific local element, the whole suffix after the common prefix is rebuilt. That costs
        // a remove+push for tracks the server merely shifted, but it can never desynchronise.
        int common = 0;
        int maxCommon = Math.min(working.size(), incoming.size());
        while (common < maxCommon && working.get(common).getId() == incoming.get(common).getId()) {
            common++;
        }

        List<Integer> removedIndexes = new ArrayList<>();
        List<MusicDetail> removedDetails = new ArrayList<>();
        for (int i = working.size() - 1; i >= common; i--) {
            removedIndexes.add(i);
            removedDetails.add(working.remove(i));
        }

        List<MusicDetail> added = new ArrayList<>(incoming.subList(common, incoming.size()));
        working.addAll(added);

        musicQueue.clear();
        musicQueue.addAll(working);

        // Tail-first, because the UI listeners remove a child view by index: clearing the highest
        // index first keeps every still-pending index valid, whereas going head-first would shift
        // them all down by one.
        for (int i = 0; i < removedIndexes.size(); i++) {
            int removedIndex = removedIndexes.get(i);
            MusicDetail removedDetail = removedDetails.get(i);
            musicQueueRemoveListeners.forEach(l -> l.accept(removedIndex, removedDetail));
        }
        for (MusicDetail addedMusicDetail : added) {
            musicQueuePushListeners.forEach(l -> l.accept(addedMusicDetail));
        }
        // A snapshot, not the live deque: the listeners post to the UI thread, and iterating the
        // shared deque from there would race the server-driven next reconcile.
        Queue<MusicDetail> snapshot = new ArrayDeque<>(musicQueue);
        musicQueueRefreshListeners.forEach(l -> l.accept(snapshot));
    }

    public void sendPushMusicToQueue(MusicDetail musicDetail) {
        clientNetworkService.sendToServer(new ClientPushMusicToQueueMessage(musicDetail.getId()));
    }

    public void sendRemoveMusicFromQueue(int index, MusicDetail musicDetail) {
        clientNetworkService.sendToServer(new ClientRemoveMusicFromQueueMessage(index, musicDetail.getId()));
    }

    /**
     * If a SyncCurrentPlayingMessage has already been processed for this connection,
     * do nothing (the state is already correct). Otherwise, reset all state to NONE.
     * Must be called from EXECUTOR (same threading context as switchMusic) for correct ordering.
     */
    public synchronized boolean checkAndResetInitialSync() {
        if (initialSyncReceived) return false;
        initialSyncReceived = true;
        switchMusic(MusicDetail.NONE, MusicDetail.NONE, null, "");
        idlePlaySourceLoaded = false;
        musicQueue.clear();
        if (HudRendererManager.isLoaded()) {
            HudRendererManager.getInstance().reset();
        }
        return true;
    }

    public synchronized void switchMusic(MusicDetail musicDetail, MusicDetail nextIdleMusicDetail, ZonedDateTime serverStartTime, String message) {
        initialSyncReceived = true;
        if (clientConfig.getEnable()) {
            if (!musicQueue.isEmpty()) {// preload image
                MusicDetail peek = musicQueue.peek();
                ImageUtils.downloadAsync(peek.getAlbum().getThumbnailPicUrl(240));
                HudRendererManager.getInstance().preloadAlbumImage(peek.getAlbum());
            } else if (nextIdleMusicDetail != null && !nextIdleMusicDetail.equals(MusicDetail.NONE)) {
                ImageUtils.downloadAsync(nextIdleMusicDetail.getAlbum().getThumbnailPicUrl(240));
                HudRendererManager.getInstance().preloadAlbumImage(nextIdleMusicDetail.getAlbum());
            }
            if (!message.isEmpty()) {
                MuiModApi.postToUiThread(() -> {
                    //noinspection UnstableApiUsage
                    Context context = UIManager.getInstance().getDecorView().getContext();
                    ToastUtil.show(Toast.makeText(context, message, Toast.LENGTH_SHORT));
                });
            }
            NowPlayingInfo nowPlayingInfo = NowPlayingInfo.getInstance();
            if (!musicDetail.equals(MusicDetail.NONE)) {
                ImageUtils.downloadAsync(musicDetail.getAlbum().getThumbnailPicUrl(240));
                StreamAudioPlayer streamAudioPlayer = StreamAudioPlayer.getInstance();
                nowPlayingInfo.switchMusicInfo(musicDetail, nextIdleMusicDetail);
                streamAudioPlayer.playAsync(musicDetail, serverStartTime)
                        .thenAccept(nowPlayingInfo::startAt)
                        .exceptionally(e -> null);
            } else {
                nowPlayingInfo.switchMusicInfo(musicDetail, nextIdleMusicDetail);
                nowPlayingInfo.startAt(null);
//                nowPlayingInfo.switchMusic(MusicDetail.NONE,MusicDetail.NONE,null);
                StreamAudioPlayer streamAudioPlayer = StreamAudioPlayer.getInstance();
                streamAudioPlayer.stop();
            }
        }
    }

    public CompletableFuture<Artist> loadArtist(long id) {
        CompletableFuture<Artist> future = new CompletableFuture<>();
        GetArtistDetailResponse.setReceiver(id, future::complete);
        clientNetworkService.sendToServer(new GetArtistDetailRequest(id));
        return future;
    }

    public CompletableFuture<List<MusicDetail>> loadArtistMusic(long id, int offset) {
        CompletableFuture<List<MusicDetail>> future = new CompletableFuture<>();
        GetArtistMoreMusicResponse.RequestData requestData = new GetArtistMoreMusicResponse.RequestData(id, offset);
        GetArtistMoreMusicResponse.setReceiver(requestData, future::complete);
        clientNetworkService.sendToServer(new GetArtistMoreMusicRequest(id, offset));
        return future;
    }

    public void voteForSkipCurrent() {
        if (NowPlayingInfo.getInstance().getCurrentlyPlayingMusicDetail() != null) {
            clientNetworkService.sendToServer(new VoteSkipCurrentMusicMessage(NowPlayingInfo.getInstance().getCurrentlyPlayingMusicDetail().getId()));
        }
    }

    public void keyBindsVoteSkipCurrent() {
        MusicDetail currentlyPlayingMusicDetail = NowPlayingInfo.getInstance().getCurrentlyPlayingMusicDetail();
        if (currentlyPlayingMusicDetail != null && currentlyPlayingMusicDetail != MusicDetail.NONE) {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastPressTime <= 3000) {
                lastPressTime = 0;
                voteForSkipCurrent();
                MuiModApi.postToUiThread(() -> {
                    //noinspection UnstableApiUsage
                    Context context = UIManager.getInstance().getDecorView().getContext();
                    ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.voteForSkipConfirmed"), Toast.LENGTH_SHORT));
                });
            } else {
                lastPressTime = currentTimeMillis;
                MuiModApi.postToUiThread(() -> {
                    //noinspection UnstableApiUsage
                    Context context = UIManager.getInstance().getDecorView().getContext();
                    ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.confirmVoteForSkip"), Toast.LENGTH_SHORT));
                });
            }
        }
    }

    public synchronized void updateAllIdlePlaySources(List<Playlist> playlistSources, List<Album> albumSources) {
        Set<MusicCollection> toRemove = new HashSet<>();
        Set<MusicCollection> toAdd = new HashSet<>();
        Set<MusicCollection> serverIdlePlaySources = Set.copyOf(this.serverIdlePlaySources);
        for (MusicCollection musicCollection : serverIdlePlaySources) {
            //noinspection SuspiciousMethodCalls
            if (!playlistSources.contains(musicCollection) && !albumSources.contains(musicCollection)) {
                toRemove.add(musicCollection);
            }
        }
        Player player = Minecraft.getInstance().player;
        for (MusicCollection musicCollection : playlistSources) {
            if (!serverIdlePlaySources.contains(musicCollection) && !(player != null && musicCollection.getPusherInfo().getPlayerUUID().equals(player.getUUID()))) {
                toAdd.add(musicCollection);
            }
        }
        for (MusicCollection musicCollection : albumSources) {
            if (!serverIdlePlaySources.contains(musicCollection) && !(player != null && musicCollection.getPusherInfo().getPlayerUUID().equals(player.getUUID()))) {
                toAdd.add(musicCollection);
            }
        }
        this.serverIdlePlaySources.removeAll(toRemove);
        this.serverIdlePlaySources.addAll(toAdd);
        toRemove.forEach(musicCollection -> {
            serverIdlePlaySourceChangeListeners.forEach((listener) -> listener.accept(musicCollection));
            serverIdlePlaySourceRemoveListeners.forEach((listener) -> listener.accept(musicCollection));
        });
        toAdd.forEach(musicCollection -> {
            serverIdlePlaySourceChangeListeners.forEach((listener) -> listener.accept(musicCollection));
            serverIdlePlaySourceAddListeners.forEach((listener) -> listener.accept(musicCollection));
        });
    }

    public CompletableFuture<List<Playlist>> loadUserPlaylists() {
        CompletableFuture<List<Playlist>> completableFuture = new CompletableFuture<>();
        if (LoginService.getInstance().isLogined()) {
            MusicHud.EXECUTOR.execute(() -> {
                Thread pendingThread = Thread.currentThread();
                GetUserPlaylistResponse.setConsumer(value -> {
                    completableFuture.complete(value);
                    pendingThread.interrupt();
                });
                clientNetworkService.sendToServer(GetUserPlaylistRequest.REQUEST);
                try {
                    Thread.sleep(Duration.of(5, ChronoUnit.SECONDS));
                    completableFuture.completeExceptionally(new ApiException());
                } catch (InterruptedException ignored) {
                }
            });
        } else {
            completableFuture.completeExceptionally(new IllegalStateException("Cannot call AccountService.loadUserPlaylists when logined as anonymous"));
        }
        return completableFuture;
    }

    public CompletableFuture<List<Album>> loadUserAlbums() {
        CompletableFuture<List<Album>> completableFuture = new CompletableFuture<>();
        if (LoginService.getInstance().isLogined()) {
            MusicHud.EXECUTOR.execute(() -> {
                Thread pendingThread = Thread.currentThread();
                GetUserAlbumsResponse.setConsumer(value -> {
                    completableFuture.complete(value);
                    pendingThread.interrupt();
                });
                clientNetworkService.sendToServer(GetUserAlbumsRequest.REQUEST);
                try {
                    Thread.sleep(Duration.of(5, ChronoUnit.SECONDS));
                    completableFuture.completeExceptionally(new ApiException());
                } catch (InterruptedException ignored) {
                }
            });
        } else {
            completableFuture.completeExceptionally(new IllegalStateException("Cannot call AccountService.loadUserAlbums when logined as anonymous"));
        }
        return completableFuture;
    }

    public CompletableFuture<List<Artist>> loadUserArtists() {
        CompletableFuture<List<Artist>> completableFuture = new CompletableFuture<>();
        if (LoginService.getInstance().isLogined()) {
            MusicHud.EXECUTOR.execute(() -> {
                Thread pendingThread = Thread.currentThread();
                GetUserArtistsResponse.setConsumer(value -> {
                    completableFuture.complete(value);
                    pendingThread.interrupt();
                });
                clientNetworkService.sendToServer(GetUserArtistsRequest.REQUEST);
                try {
                    Thread.sleep(Duration.of(5, ChronoUnit.SECONDS));
                    completableFuture.completeExceptionally(new ApiException());
                } catch (InterruptedException ignored) {
                }
            });
        } else {
            completableFuture.completeExceptionally(new IllegalStateException("Cannot call AccountService.loadUserArtists when logined as anonymous"));
        }
        return completableFuture;
    }

    public static void resetCurrentMusicStatus() {
        if (instance != null) {
            instance.switchMusic(MusicDetail.NONE, MusicDetail.NONE, null, "");
            instance.idlePlaySourceLoaded = false;
            instance.musicQueue.clear();
        }
        if (HudRendererManager.isLoaded()) {
            HudRendererManager.getInstance().reset();
        }
    }

    /**
     * Marks the start of a new connection: the next {@link #checkAndResetInitialSync()} call
     * must clear whatever the previous server left behind.
     * <p>
     * {@code initialSyncReceived} used to be set once and never cleared, so only the very first
     * connection of a process ever reset the local state. Every later one - which includes
     * leaving a single-player world and joining a multiplayer server - skipped the reset and
     * replayed the previous session's queue. Clearing it here (rather than in
     * {@link #resetCurrentMusicStatus()}) is deliberate: this runs before the new connection's
     * {@code SyncCurrentPlayingMessage} can arrive, so it cannot race with the sync it is
     * meant to precede.
     */
    public synchronized void beginNewConnection() {
        initialSyncReceived = false;
    }

    @RegisterMark
    public static class RegisterImpl implements ClientRegister {
        @Override
        public void register() {
            LoginService.getInstance().getLoginCompleteListeners().add((loginCookieInfo) -> {
                MusicService.getInstance().loadIdlePlaySourceFromConfig();
            });
            IClientEventService.getInstance().registerClientPlayerQuit((player) -> {
                MusicHud.EXECUTOR.execute(MusicService::resetCurrentMusicStatus);
            });
        }
    }
}