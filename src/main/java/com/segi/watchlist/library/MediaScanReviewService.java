package com.segi.watchlist.library;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Volatile, bounded progress and issue summary for the most recent local scan. */
@Service
public class MediaScanReviewService {
    private static final int MAX_ISSUE_SAMPLES = 250;
    private MutableScan current = new MutableScan();

    public synchronized void begin(List<String> roots) {
        current = new MutableScan();
        current.running = true;
        current.startedAt = Instant.now();
        current.rootsTotal = roots.size();
        current.roots = List.copyOf(roots);
    }

    public synchronized void visitPath() { current.pathsVisited++; }
    public synchronized void foundVideo() { current.videosFound++; }
    public synchronized void addedVideo() { current.added++; }
    public synchronized void updatedVideo() { current.updated++; }
    public synchronized void removedVideos(long count) { current.removed += Math.max(0, count); }
    public synchronized void finishRoot() { current.rootsCompleted = Math.min(current.rootsTotal, current.rootsCompleted + 1); }

    public synchronized void issue(String category, String path, String message) {
        current.issueCount++;
        if (current.issues.size() < MAX_ISSUE_SAMPLES) {
            current.issues.add(new ScanIssue(category, path == null ? "" : path, message));
        }
    }

    public synchronized void finish() {
        current.running = false;
        current.completedAt = Instant.now();
    }

    public synchronized ScanSnapshot snapshot() {
        return new ScanSnapshot(current.running, current.startedAt, current.completedAt,
                current.rootsTotal, current.rootsCompleted, current.pathsVisited, current.videosFound,
                current.added, current.updated, current.removed, current.issueCount,
                List.copyOf(current.issues), current.roots);
    }

    public record ScanIssue(String category, String path, String message) { }
    public record ScanSnapshot(boolean running, Instant startedAt, Instant completedAt,
                               int rootsTotal, int rootsCompleted, long pathsVisited, long videosFound,
                               long added, long updated, long removed, long issueCount,
                               List<ScanIssue> issues, List<String> roots) {
        public int progressPercent() {
            if (rootsTotal == 0) return running ? 0 : 100;
            return Math.min(100, (int) Math.round((double) rootsCompleted * 100 / rootsTotal));
        }
    }

    private static final class MutableScan {
        private boolean running;
        private Instant startedAt;
        private Instant completedAt;
        private int rootsTotal;
        private int rootsCompleted;
        private long pathsVisited;
        private long videosFound;
        private long added;
        private long updated;
        private long removed;
        private long issueCount;
        private List<ScanIssue> issues = new ArrayList<>();
        private List<String> roots = List.of();
    }
}
