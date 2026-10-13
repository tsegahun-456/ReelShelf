package com.segi.watchlist.catalog;

import java.math.BigDecimal;

public record MovieCard(
        long id,
        String mediaType,
        String title,
        String overview,
        String posterPath,
        String backdropPath,
        String releaseDate,
        BigDecimal rating) {

    public String getDetailsPath() {
        return "TV".equals(mediaType) ? "/tv/" + id : "/movie/" + id;
    }
}
