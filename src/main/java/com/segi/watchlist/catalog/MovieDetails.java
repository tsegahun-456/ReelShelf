package com.segi.watchlist.catalog;

import java.math.BigDecimal;
import java.util.List;

public record MovieDetails(
        MovieCard movie,
        int runtime,
        int numberOfSeasons,
        List<String> genres,
        List<SeasonCard> seasons,
        String trailerKey,
        List<CastMember> cast) {

    public record CastMember(String name, String character, String profilePath) { }
}
