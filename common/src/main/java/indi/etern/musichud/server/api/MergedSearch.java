package indi.etern.musichud.server.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Merges the same query's results from several music backends into one relevance-ranked list.
 *
 * <p>Search used to be "pick a backend, see only its results". That makes the user switch sources to
 * find one song, and it hides the fact that the two catalogues overlap: the same track often exists
 * on both, and a track missing from one is present on the other. This class answers a single query
 * from every backend, ranks the union, and returns one page of it.
 *
 * <p>Ranking is deliberately simple and explainable, because the two backends expose no comparable
 * relevance score of their own:
 * <ul>
 *   <li>a <b>lexical</b> term compares the query against the item's title — exact, prefix, contains,
 *       and a smaller bonus for a matching artist. That is what actually makes the merge useful:
 *       the same query typed into the two services otherwise ranks by two unrelated scales;</li>
 *   <li>a <b>positional</b> term ({@code 25 / (1 + rank)}) preserves each backend's own opinion —
 *       its first result is its best match — so a backend with a better catalogue for a given query
 *       still wins ties.</li>
 * </ul>
 *
 * <p>Duplicates are collapsed on title+artist, so a song available on both platforms appears once
 * instead of twice. Paging stays consistent across pages because each page re-derives the ranked
 * list from the top of both backends rather than merging two independent page windows.
 */
public final class MergedSearch {
    /**
     * Items the merged feed returns per page, and the page size of every backend's own search API.
     * The client infers "there may be more" from a full page, so both numbers have to agree with
     * {@code IMusicApiService}'s search page size.
     */
    public static final int PAGE_SIZE = 50;

    private MergedSearch() {
    }

    /**
     * @param query       the user's search text
     * @param offset      the offset the client asked for, in the merged list
     * @param providers   the backends to ask, in preference order (ties keep this order)
     * @param fetchPage   fetches one backend's page: {@code (provider, pageOffset) -> items}
     * @param title       the item's display name, used for lexical ranking and de-duplication
     * @param artists     the item's artist/creator text, used for ranking and de-duplication
     * @return the requested page of the merged, ranked, de-duplicated list; never null
     */
    public static <T> List<T> merge(String query,
                                    int offset,
                                    List<ApiProvider> providers,
                                    BiFunction<ApiProvider, Integer, List<T>> fetchPage,
                                    Function<T, String> title,
                                    Function<T, String> artists) {
        int needed = Math.max(0, offset) + PAGE_SIZE;
        List<Scored<T>> scored = new ArrayList<>();
        for (ApiProvider provider : providers) {
            // Each backend is read from its own first page up to the end of the requested window,
            // which is what makes deep pages rank consistently. A backend that fails simply
            // contributes nothing instead of failing the whole search.
            int rank = 0;
            for (int pageOffset = 0; pageOffset < needed; pageOffset += PAGE_SIZE) {
                List<T> page;
                try {
                    page = fetchPage.apply(provider, pageOffset);
                } catch (Exception e) {
                    break;
                }
                if (page == null || page.isEmpty()) {
                    break;
                }
                for (T item : page) {
                    if (item != null) {
                        String itemTitle = safe(title.apply(item));
                        String itemArtists = safe(artists.apply(item));
                        scored.add(new Scored<>(item,
                                relevance(query, itemTitle, itemArtists, rank),
                                normalize(itemTitle) + '\u0000' + normalize(itemArtists)));
                    }
                    rank++;
                }
                if (page.size() < PAGE_SIZE) {
                    // A short page means that backend has no more results for this query.
                    break;
                }
            }
        }

        scored.sort(Comparator.comparingDouble(Scored<T>::score).reversed());

        Map<String, T> unique = new LinkedHashMap<>();
        for (Scored<T> entry : scored) {
            unique.putIfAbsent(entry.key(), entry.item());
        }
        List<T> merged = List.copyOf(unique.values());

        int from = Math.max(0, offset);
        if (from >= merged.size()) {
            return List.of();
        }
        return List.copyOf(merged.subList(from, Math.min(merged.size(), from + PAGE_SIZE)));
    }

    private static double relevance(String query, String name, String artists, int rank) {
        String normalizedQuery = normalize(query);
        String normalizedName = normalize(name);
        double score = 0;
        if (!normalizedQuery.isEmpty()) {
            if (normalizedName.equals(normalizedQuery)) {
                score += 100;
            } else if (normalizedName.startsWith(normalizedQuery)) {
                score += 70;
            } else if (normalizedName.contains(normalizedQuery)) {
                score += 50;
            } else {
                score += 5;
            }
            if (!normalize(artists).contains(normalizedQuery)) {
                // no artist bonus
            } else {
                score += 30;
            }
        }
        return score + 25.0 / (1.0 + rank);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private record Scored<T>(T item, double score, String key) {
    }
}
