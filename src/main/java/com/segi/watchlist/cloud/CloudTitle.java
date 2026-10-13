package com.segi.watchlist.cloud;

import java.math.BigDecimal;
import java.time.Instant;

public record CloudTitle(long tmdbId, String mediaType, String title,
                         String posterPath, String backdropPath, String releaseDate,
                         BigDecimal voteAverage, Integer userRating, Instant occurredAt) {
    public String documentId() { return mediaType + "_" + tmdbId; }
}
