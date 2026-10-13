package com.segi.watchlist.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Service
public class MovieCatalogService {
    private final RestClient tmdb;
    private final String apiKey;

    public MovieCatalogService(@Value("${tmdb.base-url}") String baseUrl,
                               @Value("${tmdb.api-key:}") String apiKey) {
        this.tmdb = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    public List<MovieCard> trendingMovies() {
        return mediaList("/trending/movie/day", "MOVIE", Map.of());
    }

    public List<MovieCard> popularMovies() {
        return mediaList("/movie/popular", "MOVIE", Map.of());
    }

    public List<MovieCard> recentMovies() {
        return mediaList("/movie/now_playing", "MOVIE", Map.of());
    }

    public List<MovieCard> topRatedMovies() {
        return mediaList("/movie/top_rated", "MOVIE", Map.of());
    }

    public List<MovieCard> upcomingMovies() {
        return mediaList("/movie/upcoming", "MOVIE", Map.of());
    }

    public List<MovieCard> popularSeries() {
        return mediaList("/tv/popular", "TV", Map.of());
    }

    public List<MovieCard> onTheAirSeries() {
        return mediaList("/tv/on_the_air", "TV", Map.of());
    }

    public List<MovieCard> topRatedSeries() {
        return mediaList("/tv/top_rated", "TV", Map.of());
    }

    public List<MovieGenre> genres(String mediaType) {
        String type = normalizeMediaType(mediaType);
        JsonNode payload = get("/genre/" + pathType(type) + "/list", Map.of());
        List<MovieGenre> genres = new ArrayList<>();
        for (JsonNode genre : payload.path("genres")) {
            genres.add(new MovieGenre(genre.path("id").asLong(), genre.path("name").asText("")));
        }
        return genres;
    }

    public List<MovieCard> search(String query, String mediaType, Long genreId,
                                  Integer year, BigDecimal minimumRating) {
        String type = normalizeMediaType(mediaType);
        String kind = pathType(type);
        boolean hasQuery = query != null && !query.isBlank();
        String endpoint = hasQuery ? "/search/" + kind : "/discover/" + kind;
        var parameters = new java.util.LinkedHashMap<String, String>();
        parameters.put("page", "1");
        if (hasQuery) parameters.put("query", query.trim());
        if (genreId != null) parameters.put("with_genres", genreId.toString());
        if (year != null) {
            parameters.put(type.equals("TV") ? "first_air_date_year" : "primary_release_year",
                    year.toString());
        }
        if (minimumRating != null) parameters.put("vote_average.gte", minimumRating.toPlainString());
        return mediaList(endpoint, type, parameters);
    }

    public List<MovieCard> searchMovies(String query) {
        return search(query, "MOVIE", null, null, null);
    }

    public MovieDetails movieDetails(long id) {
        return details(id, "MOVIE");
    }

    public MovieDetails details(long id, String mediaType) {
        String type = normalizeMediaType(mediaType);
        String kind = pathType(type);
        JsonNode payload = get("/" + kind + "/" + id, Map.of("append_to_response", "videos,credits"));
        if (payload == null || payload.path("id").isMissingNode()) {
            throw new IllegalStateException("TMDB returned an empty title response.");
        }

        MovieCard card = mapMedia(payload, type);
        List<String> genres = new ArrayList<>();
        payload.path("genres").forEach(genre -> genres.add(genre.path("name").asText("")));

        String trailerKey = null;
        for (JsonNode video : payload.path("videos").path("results")) {
            if ("Trailer".equalsIgnoreCase(video.path("type").asText())
                    && "YouTube".equalsIgnoreCase(video.path("site").asText())) {
                trailerKey = video.path("key").asText(null);
                break;
            }
        }

        List<MovieDetails.CastMember> cast = new ArrayList<>();
        for (JsonNode person : payload.path("credits").path("cast")) {
            if (cast.size() == 8) break;
            cast.add(new MovieDetails.CastMember(
                    person.path("name").asText(""),
                    person.path("character").asText(""),
                    person.path("profile_path").asText("")));
        }

        List<SeasonCard> seasons = new ArrayList<>();
        if (type.equals("TV")) {
            for (JsonNode season : payload.path("seasons")) {
                if (season.path("season_number").asInt(-1) < 0) continue;
                seasons.add(new SeasonCard(
                        season.path("season_number").asInt(),
                        season.path("name").asText("Season"),
                        season.path("episode_count").asInt(),
                        season.path("poster_path").asText("")));
            }
        }
        int runtime = type.equals("TV")
                ? payload.path("episode_run_time").path(0).asInt(0)
                : payload.path("runtime").asInt(0);
        return new MovieDetails(card, runtime, payload.path("number_of_seasons").asInt(0),
                genres, seasons, trailerKey, cast);
    }

    public List<MovieCard> recommendations(String mediaType, long id) {
        String type = normalizeMediaType(mediaType);
        return mediaList("/" + pathType(type) + "/" + id + "/recommendations", type, Map.of());
    }

    private List<MovieCard> mediaList(String endpoint, String mediaType, Map<String, String> parameters) {
        JsonNode payload = get(endpoint, parameters);
        List<MovieCard> titles = new ArrayList<>();
        if (payload != null) {
            for (JsonNode item : payload.path("results")) titles.add(mapMedia(item, mediaType));
        }
        return titles;
    }

    private JsonNode get(String endpoint, Map<String, String> parameters) {
        requireApiKey();
        return tmdb.get()
                .uri(uri -> {
                    var builder = uri.path(endpoint).queryParam("api_key", apiKey);
                    parameters.forEach(builder::queryParam);
                    return builder.build();
                })
                .retrieve()
                .body(JsonNode.class);
    }

    private MovieCard mapMedia(JsonNode item, String mediaType) {
        String type = normalizeMediaType(mediaType);
        String titleField = type.equals("TV") ? "name" : "title";
        String dateField = type.equals("TV") ? "first_air_date" : "release_date";
        return new MovieCard(
                item.path("id").asLong(),
                type,
                item.path(titleField).asText("Untitled"),
                item.path("overview").asText(""),
                item.path("poster_path").asText(""),
                item.path("backdrop_path").asText(""),
                item.path(dateField).asText(""),
                BigDecimal.valueOf(item.path("vote_average").asDouble(0)));
    }

    private String normalizeMediaType(String mediaType) {
        return "TV".equalsIgnoreCase(mediaType) || "series".equalsIgnoreCase(mediaType) ? "TV" : "MOVIE";
    }

    private String pathType(String mediaType) {
        return "TV".equals(mediaType) ? "tv" : "movie";
    }

    private void requireApiKey() {
        if (!isConfigured()) throw new TmdbNotConfiguredException();
    }

    public static class TmdbNotConfiguredException extends RuntimeException {
        public TmdbNotConfiguredException() {
            super("TMDB_API_KEY is not configured.");
        }
    }
}
