package com.segi.watchlist.library;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudyNoteRepository extends JpaRepository<StudyNote, Long> {
    List<StudyNote> findByMediaIdOrderByAtSecondsAscIdAsc(Long mediaId);
    Optional<StudyNote> findByIdAndMediaId(Long id, Long mediaId);
}
