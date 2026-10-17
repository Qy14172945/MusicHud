package indi.etern.musichud.server.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import indi.etern.musichud.beans.music.MusicDetail;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Server-side map from a MusicHud numeric entity id to the provider that minted it, plus the
 * provider's own identifier for that entity (QQ's MID strings).
 * <p>
 * <b>Why this exists.</b> The shared bean model and the whole request/response protocol key
 * entities by a numeric {@code long id} (see {@code MusicDetail.id}, {@code GetMusicResourceRequest},
 * the response routers). Only search results carry the full bean back to the client, so a later
 * lookup by bare id has no way to tell whether it belongs to NetEase or QQ. Providers therefore
 * register every id they hand out here, and the long-only request handlers resolve the owning
 * provider from this map instead of hard-coding {@link ApiProvider#NCM}.
 * <p>
 * A bounded cache of the fully-built {@link MusicDetail} for each id is kept as well. QQ cannot
 * re-fetch a track from a bare numeric id ({@code /getSongInfo} is broken upstream and its lyric
 * and play endpoints reject numeric ids), so for servers fronting QQ the remembered detail is the
 * only way a later play request can be answered.
 * <p>
 * The id-to-provider map is never evicted: entries are tiny, and a stale entry only means an id
 * that no longer resolves is still attributed to the right provider (which then reports
 * "not found" rather than silently querying the wrong service).
 */
public final class MusicIdRegistry {
    private static final Map<Long, Ref> REGISTRY = new ConcurrentHashMap<>();
    private static final Cache<Long, MusicDetail> DETAILS = CacheBuilder.newBuilder()
            .expireAfterAccess(30, TimeUnit.MINUTES)
            .maximumSize(2000)
            .build();

    private MusicIdRegistry() {
    }

    /**
     * The owning provider and that provider's own identifier for an entity. {@code externalId} is
     * empty for providers that address entities numerically (NetEase).
     */
    public record Ref(@NotNull ApiProvider provider, @NotNull String externalId) {
    }

    /**
     * Records that {@code id} belongs to {@code provider}. {@code externalId} may be empty when the
     * provider addresses the entity by number only.
     */
    public static void register(long id, @NotNull ApiProvider provider, @Nullable String externalId) {
        if (id == 0 || id == -1) {
            // 0/-1 are sentinel ids (Profile.ANONYMOUS, Playlist.EMPTY, ...) that many entities
            // share; registering them would misattribute unrelated lookups.
            return;
        }
        REGISTRY.put(id, new Ref(provider, externalId == null ? "" : externalId));
    }

    /**
     * Records the built bean alongside its id so a later bare-id request can be answered without
     * asking the provider to re-resolve it.
     */
    public static void registerDetail(@Nullable MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail == MusicDetail.NONE) {
            return;
        }
        long id = musicDetail.getId();
        if (id == 0 || id == -1) {
            return;
        }
        register(id, musicDetail.getProvider(), musicDetail.getExternalId());
        DETAILS.put(id, musicDetail);
    }

    /**
     * The last built bean for {@code id}, or {@code null} when it is unknown or has expired.
     */
    @Nullable
    public static MusicDetail detailOf(long id) {
        return DETAILS.getIfPresent(id);
    }

    /**
     * Resolves a numeric id, or {@code null} when the id was never handed out by a provider in
     * this server session (e.g. an idle play source persisted from a previous run).
     */
    @Nullable
    public static Ref lookup(long id) {
        return REGISTRY.get(id);
    }

    /**
     * The provider owning {@code id}, defaulting to NetEase — the historical behaviour of every
     * pre-existing call site — when the id is unknown.
     */
    @NotNull
    public static ApiProvider providerOf(long id) {
        Ref ref = REGISTRY.get(id);
        return ref == null ? ApiProvider.NCM : ref.provider();
    }

    /**
     * The owning provider's own identifier for {@code id}, or an empty string when unknown or when
     * the provider addresses the entity numerically.
     */
    @NotNull
    public static String externalIdOf(long id) {
        Ref ref = REGISTRY.get(id);
        return ref == null ? "" : ref.externalId();
    }

    /**
     * Convenience for resolving provider and external id in one lookup.
     */
    @NotNull
    public static ApiProvider providerOr(@Nullable ApiProvider candidate, long id) {
        return candidate != null ? candidate : providerOf(id);
    }
}
