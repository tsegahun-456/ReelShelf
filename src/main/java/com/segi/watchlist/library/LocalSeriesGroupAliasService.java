package com.segi.watchlist.library;

import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalSeriesGroupAliasService {
    private final LocalSeriesGroupAliasRepository aliases;
    private volatile Map<String, String> aliasMap;

    public LocalSeriesGroupAliasService(LocalSeriesGroupAliasRepository aliases) { this.aliases = aliases; }

    public String canonicalTitle(String title) {
        if (title == null || title.isBlank()) return title;
        String resolved = title.trim();
        Map<String, String> mapping = aliasMap();
        Set<String> visited = new HashSet<>();
        while (visited.add(aliasKey(resolved))) {
            String next = mapping.get(aliasKey(resolved));
            if (next == null || next.isBlank() || visited.contains(aliasKey(next))) break;
            resolved = next.trim();
        }
        return resolved;
    }

    private Map<String, String> aliasMap() {
        Map<String, String> cached = aliasMap;
        if (cached != null) return cached;
        synchronized (this) {
            if (aliasMap == null) {
                aliasMap = aliases.findAll().stream().collect(Collectors.toUnmodifiableMap(
                        LocalSeriesGroupAlias::getAliasKey, LocalSeriesGroupAlias::getCanonicalTitle, (left, right) -> right));
            }
            return aliasMap;
        }
    }

    public List<LocalSeriesGroupAlias> all() { return aliases.findAllByOrderByCanonicalTitleAscAliasTitleAsc(); }

    @Transactional
    public void merge(String aliasTitle, String canonicalTitle) {
        String alias = clean(aliasTitle);
        String canonical = clean(canonicalTitle);
        if (alias.isBlank() || canonical.isBlank() || alias.length() > 300 || canonical.length() > 300)
            throw new IllegalArgumentException("Series titles must be 1–300 characters.");
        canonical = canonicalTitle(canonical);
        if (aliasKey(alias).equals(aliasKey(canonical))) throw new IllegalArgumentException("Choose two different series titles.");
        if (aliasKey(canonicalTitle(canonical)).equals(aliasKey(alias)))
            throw new IllegalArgumentException("That merge would create a series grouping cycle.");
        aliases.save(new LocalSeriesGroupAlias(aliasKey(alias), alias, canonical));
        aliasMap = null;
    }

    public void unmerge(String aliasTitle) {
        if (aliasTitle != null && !aliasTitle.isBlank()) {
            aliases.deleteById(aliasKey(aliasTitle));
            aliasMap = null;
        }
    }

    public static String aliasKey(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static String clean(String title) { return title == null ? "" : title.trim().replaceAll("\\s+", " "); }
}
