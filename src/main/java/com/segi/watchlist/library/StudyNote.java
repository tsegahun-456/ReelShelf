package com.segi.watchlist.library;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "media_study_note", indexes = @Index(name = "media_study_note_media_time_idx", columnList = "media_id, at_seconds"))
public class StudyNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id", nullable = false)
    private LocalMedia media;

    @Column(name = "at_seconds", nullable = false)
    private long atSeconds;

    @Column(name = "note_text", nullable = false, length = 2000)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StudyNote() { }

    public StudyNote(LocalMedia media, long atSeconds, String text) {
        this.media = media;
        this.atSeconds = atSeconds;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getAtSeconds() { return atSeconds; }
    public String getText() { return text; }
    public Instant getCreatedAt() { return createdAt; }
    public String getTimestampLabel() {
        long hours = atSeconds / 3600;
        long minutes = (atSeconds % 3600) / 60;
        long seconds = atSeconds % 60;
        return hours > 0 ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }
}
