package com.segi.watchlist.library;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:local-media-poster-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never"
})
class LocalMediaPosterPersistenceTest {
    @Autowired
    private LocalMediaRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void seriesPosterAndExplicitReviewFlagSurviveScannerRefreshAndBackgroundMatcher() {
        LocalMedia first = episode("/media/Some Show/Season 1/Some.Show.S01E01.mkv", 1);
        LocalMedia second = episode("/media/Some Show/Season 1/Some.Show.S01E02.mkv", 2);
        List<LocalMedia> saved = repository.saveAllAndFlush(List.of(first, second));
        List<Long> ids = saved.stream().map(LocalMedia::getId).toList();

        int manuallySelectedMatch = repository.updateTmdbMatchForIds(ids, 731L, "TV", "Some Show",
                "Series overview", "/some-show.jpg", "2020-01-01");
        assertThat(manuallySelectedMatch).isEqualTo(2);

        int scannerUpdated = repository.updateScannedMetadata(first.getFilePath(), first.getFileName(),
                first.getDisplayTitle(), first.getMediaKind(), first.getSeriesTitle(), first.getSeasonNumber(),
                first.getEpisodeNumber(), first.getEpisodeTitle(), first.getSubtitlePath(), 200L,
                Instant.now(), Instant.now(), MediaScanner.PARSER_VERSION);
        assertThat(scannerUpdated).isEqualTo(1);

        int staleAutomaticMatch = repository.updateUnmatchedTmdbForIds(ids, 999L, "TV", "Wrong Show",
                "Wrong overview", "/wrong-show.jpg", "2010-01-01");
        assertThat(staleAutomaticMatch).isZero();

        entityManager.clear();
        List<LocalMedia> reloaded = repository.findAllById(ids);
        assertThat(reloaded).hasSize(2).allSatisfy(item -> {
            assertThat(item.getTmdbId()).isEqualTo(731L);
            assertThat(item.getTmdbTitle()).isEqualTo("Some Show");
            assertThat(item.getTmdbPosterPath()).isEqualTo("/some-show.jpg");
            assertThat(item.isTmdbMatchReviewed()).isTrue();
        });
    }

    @Test
    void substringPosterCandidatesAreNotExactTitleMatches() {
        assertThat(LocalMediaTmdbMatcher.similarity("Dark", "Dark Shadows")).isLessThan(0.92);
    }

    private LocalMedia episode(String path, int number) {
        LocalMedia row = new LocalMedia();
        row.updateFrom(new MediaScanner.MediaFile(path, path.substring(path.lastIndexOf('/') + 1),
                "Some Show · S01E" + String.format("%02d", number), "EPISODE", "Some Show", 1,
                number, null, null, 1_000L, Instant.parse("2024-01-01T00:00:00Z")));
        return row;
    }
}
