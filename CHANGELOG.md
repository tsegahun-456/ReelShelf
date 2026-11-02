# ReelShelf change log

## 0.3.0

- Added private timestamped study notes and jump-back bookmarks to local video playback.
- Added a scan review page for live scan progress, unreadable paths, possible duplicate files, similar series names, and uncertain poster references.
- Added manual poster selection with explicit confirmation. Automatic matching now leaves ambiguous names such as “Dark” and “Dark Shadows” unresolved; existing manual links are preserved.
- Added series grouping aliases that users can merge or undo from review. No files are renamed or moved. A parser-version migration refreshes saved scan metadata on the next successful scan while retaining title overrides, poster references, media paths, and playback data.
- Added selectable embedded audio tracks. The player uses browser-native switching when available and a cached FFmpeg playback copy as a fallback; the source video is unchanged.
- Added per-video audio, subtitle, subtitle-size, and aspect-ratio preferences.
- Added named movie/series collections with year, genre, and local-file availability filters. Collections stay separate from the Firebase-synced **My list**.
- Updated the portable Linux and Windows packages with the same application features and updater. FFmpeg/FFprobe remain optional host tools for embedded audio/subtitle features.

### Upgrade notes

- PostgreSQL installations apply Flyway migrations V9–V11 automatically.
- Portable H2 databases add the new tables and metadata fields in place; local data remains in its user data folder.
- The first successful scan after upgrade re-reads file metadata using the updated parser. Unavailable roots are not treated as deleted media.
- Previous tagged releases remain available. Use the `v0.3.0` release assets for the updated Linux and Windows portable apps.
- The installed app on the developer PC is intentionally left unchanged for updater testing.
