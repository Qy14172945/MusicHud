package indi.etern.musichud.network.payloads.requestResponseCycle;

import indi.etern.musichud.beans.api.SearchType;
import indi.etern.musichud.beans.music.Album;
import indi.etern.musichud.beans.music.Artist;
import indi.etern.musichud.beans.music.MusicDetail;
import indi.etern.musichud.beans.music.Playlist;
import indi.etern.musichud.interfaces.CommonRegister;
import indi.etern.musichud.interfaces.RegisterMark;
import indi.etern.musichud.network.*;
import indi.etern.musichud.network.payloads.C2SPayload;
import indi.etern.musichud.network.payloads.S2CPayload;
import indi.etern.musichud.server.api.ApiProvider;
import indi.etern.musichud.server.api.IMusicApiService;
import indi.etern.musichud.server.api.MergedSearch;
import indi.etern.musichud.utils.ServerDataPacketVThreadExecutor;

import java.util.List;

/**
 * A search issued from the client's search page.
 * <p>
 * {@code provider} is the backend the user picked. It travels with the request because the server
 * cannot guess it: search used to be NetEase-only, but QQ Music answers the same query from an
 * entirely different service, so the choice has to be explicit. A provider the server does not
 * serve falls back to {@link ApiProvider#NCM} instead of failing the search.
 * <p>
 * {@code mergeProviders} asks the server to answer from <em>every</em> served backend at once and
 * return one relevance-ranked list (see {@link MergedSearch}). It is what lets the search page stop
 * depending on the source switch: the user gets the union of both catalogues, and which platform a
 * row came from is shown on the row itself.
 */
public record SearchRequest(String query, SearchType searchType, int offset, ApiProvider provider, boolean mergeProviders) implements C2SPayload {
    public static final ByteBufCodec<SearchRequest> CODEC = ByteBufCodec.composite(
            Codecs.STRING_UTF8,
            SearchRequest::query,
            Codecs.ofEnum(SearchType.class),
            SearchRequest::searchType,
            Codecs.INT,
            SearchRequest::offset,
            Codecs.ofEnum(ApiProvider.class),
            SearchRequest::provider,
            Codecs.BOOL,
            SearchRequest::mergeProviders,
            SearchRequest::new
    );

    /**
     * Non-null provider, defaulting to NetEase. The codec composes the record directly, so a
     * malformed packet can leave the field null.
     */
    @Override
    public ApiProvider provider() {
        return provider == null ? ApiProvider.NCM : provider;
    }

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        @Override
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(SearchRequest.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((message, player) -> {
                        List<ApiProvider> providers = ApiProvider.availableProviders();
                        S2CPayload s2CPayload;
                        if (message.mergeProviders() && providers.size() > 1) {
                            s2CPayload = merged(message, providers);
                        } else {
                            // A server that does not serve the requested provider would answer with
                            // an empty result set; searching NetEase instead keeps the page usable.
                            ApiProvider requested = message.provider();
                            ApiProvider provider = providers.contains(requested) ? requested : ApiProvider.NCM;
                            s2CPayload = forProvider(message, provider);
                        }
                        IServerNetworkService.getInstance().sendToPlayer(player, s2CPayload);
                    })
            );
        }

        private static S2CPayload forProvider(SearchRequest message, ApiProvider provider) {
            IMusicApiService musicApiService = IMusicApiService.getInstance(provider);
            return switch (message.searchType()) {
                case ARTIST -> new SearchArtistsResponse(message.offset(), musicApiService.searchArtists(message.query(), message.offset()));
                case ALBUM -> new SearchAlbumsResponse(message.offset(), musicApiService.searchAlbums(message.query(), message.offset()));
                case MUSIC -> new SearchMusicResponse(message.offset(), musicApiService.searchMusic(message.query(), message.offset()));
                case PLAYLIST -> new SearchPlaylistsResponse(message.offset(), musicApiService.searchPlaylists(message.query(), message.offset()));
                default -> new SearchMusicResponse(message.offset(), musicApiService.searchMusic(message.query(), message.offset()));
            };
        }

        private static S2CPayload merged(SearchRequest message, List<ApiProvider> providers) {
            String query = message.query();
            int offset = message.offset();
            return switch (message.searchType()) {
                case ARTIST -> new SearchArtistsResponse(offset, MergedSearch.merge(query, offset, providers,
                        (provider, pageOffset) -> IMusicApiService.getInstance(provider).searchArtists(query, pageOffset),
                        Artist::getName, artist -> ""));
                case ALBUM -> new SearchAlbumsResponse(offset, MergedSearch.merge(query, offset, providers,
                        (provider, pageOffset) -> IMusicApiService.getInstance(provider).searchAlbums(query, pageOffset),
                        Album::getName, album -> ""));
                case PLAYLIST -> new SearchPlaylistsResponse(offset, MergedSearch.merge(query, offset, providers,
                        (provider, pageOffset) -> IMusicApiService.getInstance(provider).searchPlaylists(query, pageOffset),
                        Playlist::getName, playlist -> ""));
                case MUSIC -> new SearchMusicResponse(offset, MergedSearch.merge(query, offset, providers,
                        (provider, pageOffset) -> IMusicApiService.getInstance(provider).searchMusic(query, pageOffset),
                        MusicDetail::getName, SearchRequest.RegisterImpl::artistNames));
                default -> new SearchMusicResponse(offset, MergedSearch.merge(query, offset, providers,
                        (provider, pageOffset) -> IMusicApiService.getInstance(provider).searchMusic(query, pageOffset),
                        MusicDetail::getName, SearchRequest.RegisterImpl::artistNames));
            };
        }

        private static String artistNames(MusicDetail musicDetail) {
            return artistNames(musicDetail.getArtists());
        }

        private static String artistNames(List<Artist> artists) {
            if (artists == null || artists.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (Artist artist : artists) {
                if (artist == null || artist.getName() == null) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(artist.getName());
            }
            return builder.toString();
        }
    }
}
