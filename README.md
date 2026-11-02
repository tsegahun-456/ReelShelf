# ReelShelf

A Spring Boot movie and TV discovery, watchlist, and local media library.

- Responsive dark and red discovery pages with trending, popular, new release, top rated, upcoming movie, and TV series shelves.
- Movie and TV search/discovery with genre, year, and minimum-rating filters.
- Movie and TV detail pages with trailers, cast, genres, runtime, and TV season metadata.
- Server-side TMDB requests. The API key stays in the Spring server environment.
- Email/password accounts that can use Firebase Authentication when configured; local PostgreSQL login remains available before Firebase setup.
- A movie and TV watchlist plus watched history and one-to-five-star ratings, synchronized through per-user Firestore documents when online and cached in PostgreSQL on each PC.
- Home-page recommendations based on the signed-in user's recently saved titles.
- A profile overview with account details and collection counts.
- An installable PWA shell with a useful offline page; local movie playback and the cached library stay on this PC. Cross-PC watchlist and watched-history changes need an internet connection.
- A local media library with separate movie and series shelves, conservative TMDB poster matching, and series grouped into season folders with ordered episodes. Poster images are stored in PostgreSQL for offline use and removed when their last local video disappears. The library scans configured folders, streams local video, and supports external and embedded captions plus selectable embedded audio tracks.
- A separate **Online** page reserved for an external streaming provider. It currently shows a clear unconfigured state; provider search and playback require the provider's API documentation and compatible playback endpoints. The local library stays independent.
- A dedicated cinema-style local player with per-video audio/subtitle/size/aspect preferences, timestamped study notes and bookmarks, seeking, remembered resume position, keyboard shortcuts, fullscreen, theater mode, picture-in-picture, and optional next-episode autoplay.
- A scan review page with scan progress, unreadable paths, possible duplicate files, candidate series-group merges, and manual TMDB result selection. No uncertain metadata correction is applied automatically.
- Named collections with year, genre, and local-file availability filters; separate from the Firebase-synced **My list**.
- Flyway-managed schema creation and Docker Compose packaging.

The Flutter app remains unchanged and is the behavior reference while features are ported.

## Download a portable build

Download the Linux or Windows portable app and its SHA-256 checksum from the [latest ReelShelf release](https://github.com/tsegahun-456/ReelShelf/releases/latest). Each computer keeps its video library, database, and settings in that computer's user folders; replacing the app bundle does not remove them.

Both portable packages include a graphical updater. Linux users can open **Update ReelShelf** from the desktop app menu after running the bundled desktop-launcher setup once. Windows users can double-click `update-reelshelf.bat`. The updater displays download progress, saves paused downloads outside the app folder so they can resume after a PC restart, verifies the release checksum, and installs only when requested.

## Documentation

Comprehensive engineering documentation is available in the [`docs/`](docs/) directory:

- [**Microsoft Word Document (.docx)**](docs/Movie_Watchlist_Web_SRS_Comprehensive.docx): Complete 80+ page formatted Word document with all 18 embedded visual diagrams, formal tables, and IEEE specification.
- [**Comprehensive Academic SRS & SDD Master Specification (Markdown)**](docs/SRS_COMPREHENSIVE.md): Complete 5-chapter institutional and enterprise specification adhering to the Dire Dawa University Department of Software Engineering standard (Chapters 1–5, Cost of Inaction, 9 Sequence Diagrams, 3 DFD Levels, Class Diagrams, Activity Diagrams, ERD, and RTM Matrix).
- [**Software Requirements Specification (SRS)**](docs/SRS.md): IEEE 830 / ISO 29148 compliant requirements, functional breakdown, and acceptance criteria.
- [**Architecture & Technical Design (SDD)**](docs/ARCHITECTURE.md): Subsystems, security boundary layers, and Mermaid sequence workflows (streaming, subtitle transcoding, cloud sync).
- [**Database & Data Model Specification**](docs/DATABASE.md): Entity-Relationship Diagram (ERD), Flyway schema migration history (V1–V11), and Cloud Firestore data schema.
- [**0.3.0 release notes**](CHANGELOG.md): feature summary and migration notes.
- [**Deployment & Operations Guide**](docs/DEPLOYMENT_GUIDE.md): Docker Compose multi-container setup, environment variable matrix, and troubleshooting runbooks.
- [**Firebase Setup Guide**](docs/FIREBASE_SETUP.md): Cloud authentication and Firestore rule configuration.
- [**Portable Linux Guide**](docs/PORTABLE_LINUX.md): Running offline with embedded H2.

## Run with Docker Compose

1. Copy `.env.example` to `.env`.
2. Set `DATABASE_PASSWORD` to a local password.
3. Set `TMDB_API_KEY` to your TMDB API key to enable live listings and search.
4. Run `docker compose up --build` from this directory.
5. Open [http://localhost:8080](http://localhost:8080).

PostgreSQL data is stored in the `movie_watchlist_data` Docker volume. Compose binds the web and database ports to localhost for local use. Do not reuse the example password for a deployed service.

## Firebase account and list sync

See [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md) for the Firebase Console steps. Firebase is used only for email/password sign-in and the watchlist/watched metadata. Video files, local paths, subtitles, and playback state remain on the PC. The app uses user ID tokens for Firestore requests, so Firestore Security Rules still restrict each user to their own documents. No service-account JSON or private key is needed.

After the Console setup, put the Firebase web app's `apiKey` in `FIREBASE_WEB_API_KEY` and the project `projectId` in `FIREBASE_PROJECT_ID` in `.env`, then run `scripts/rebuild-movie-watchlist.sh`. The first successful sign-in links an existing local account and merges its saved lists with the cloud lists. Later list pages refresh the local cache from Firestore; if Firebase is temporarily offline, cached lists remain viewable and list changes wait until Firebase is reachable.

To install the Linux app-menu icon again after moving the project, run `scripts/install-desktop-launcher.sh` once. After that, search for **Movie Watchlist** in the desktop app menu; it starts the local Docker app and opens the browser when the app is ready.

## Run the Java app directly

The Maven Wrapper downloads the declared Maven distribution the first time it runs. Start PostgreSQL with `docker compose up database -d`, export the same database settings from `.env` to your shell, then run:

```sh
./mvnw spring-boot:run
```

The app listens on port `8080` by default. `PORT`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `TMDB_API_KEY` can be supplied through environment variables.

For a local browser preview on port `8081`, load the local database settings from `.env` and start the app with the `preview` Spring profile:

```sh
set -a; . ./.env; set +a
./mvnw package
java -Dspring.profiles.active=preview -jar target/movie-watchlist-web-0.3.0.jar --server.port=8081
```

The preview uses PostgreSQL and keeps accounts and lists when the app restarts. Configure `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for your local database in `.env`.

The preview scans the folders listed in Settings (seeded once from `MEDIA_ROOTS`) every five minutes by default (`MEDIA_SCAN_INTERVAL_MS`), or instantly when you click **Scan now**. It ignores the Music, recycle-bin, and Windows system folders. New files appear in **My media** without copying video into PostgreSQL. TMDB posters and descriptions use conservative automatic matches; uncertain choices can be reviewed and selected in **My media → Scan review**. Scan review also reports unreadable paths, possible duplicate files, and similar series names; it never deletes or merges media automatically. A parser-version update re-reads scan metadata on the next successful scan while retaining paths, title overrides, TMDB links, and playback data.

Movies and series have separate shelves; opening a series shows season folders and sorted episodes. The player remembers playback position and per-video audio track, subtitle track and size, and aspect ratio. It offers speed, seek, keyboard, picture-in-picture, fullscreen/theater, next-episode controls, and timestamped private notes/bookmarks. Audio track selection is native where a browser supports it; otherwise FFmpeg makes a cached compatible playback copy without modifying the source video. Embedded subtitle tracks are discovered with `ffprobe` and converted to WebVTT with `ffmpeg`; matching `.srt` and `.vtt` sidecars remain available as well. Image-based DVD/PGS subtitles are listed but cannot be shown as selectable browser captions. Collections add named movie/series lists with year, genre, and local-file filters; they stay in the local app database, apart from the Firebase-synced **My list**. The separate **Online** page stays independent from local files until a streaming provider is configured. The local library preview is viewable without sign-in and binds to localhost only; account-backed watchlists and named collections require sign-in.

Docker Compose installs FFmpeg in the application container and mounts `${MEDIA_HOST_ROOT}` read-only at `/media/library`; change that environment value if the partition is mounted elsewhere. If running the Spring app directly on the host instead of Docker, install FFmpeg and ensure both `ffmpeg` and `ffprobe` are on the app's `PATH` (or set `FFMPEG_COMMAND` and `FFPROBE_COMMAND`). On portable builds FFmpeg is an optional host dependency for embedded audio/subtitle inspection and conversion; without it, browser-default audio and sidecar subtitles still work.

## Developer and AI contributor guide

Use this section when changing ReelShelf so source changes, local data, and portable releases stay consistent.

### Where changes belong

- `src/main/java/com/segi/watchlist/`: Spring MVC controllers, services, entities, repositories, and integrations.
- `src/main/resources/templates/`: Thymeleaf pages and fragments, including the independent `/online` landing page; `src/main/resources/static/`: CSS, JavaScript, icons, and the PWA service worker.
- `src/main/resources/db/migration/`: versioned Flyway SQL migrations. Add a new migration for schema changes; do not rewrite migrations already used by existing databases.
- `scripts/` and `portable-windows/`: portable app packaging, launch, update, and desktop integration. Keep Linux and Windows behavior aligned.
- `docs/`: architecture, deployment, database, Firebase, and portable usage guides. Update the relevant guide when a feature changes how the app is configured, used, or packaged.

Read [Architecture & Technical Design](docs/ARCHITECTURE.md), [Database & Data Model](docs/DATABASE.md), and the relevant deployment guide before changing those areas. The portable app uses its own H2 database and user config; Docker/development commonly uses PostgreSQL. Do not assume that changing one deployment profile changes the other.

### Safe change workflow

1. Inspect the existing implementation and relevant documentation; make a focused change in the source of truth instead of editing generated files under `target/` or extracted/archive bundles.
2. Check whether the change affects local media, user settings, authentication, persisted watchlists, database schemas, or both portable platforms. Keep each PC's media paths and local database local.
3. For schema changes, add the next Flyway migration and check compatibility with both PostgreSQL and portable H2. Preserve existing library entries, playback state, settings, and account data; never use a destructive reset as an upgrade strategy.
4. Update relevant README/docs and user-facing setup instructions. Keep secrets, personal config, local databases, logs, and media out of source control and release archives.
5. Build and run the narrowest relevant checks, then exercise the affected flow. When packaging is part of the change, build and review both portable packages rather than copying a JAR over an existing archive.

### Build and release portable packages

The release version is declared in `pom.xml`; the package scripts use it to name the application JAR. With JDK 21 and the required packaging tools installed, build from the repository root:

```bash
./scripts/package-linux-portable.sh
./scripts/package-windows-portable.sh
```

The scripts create archives and SHA-256 files under `target/portable-linux/` and `target/portable-windows/`. Linux packaging requires Linux x86-64 with `jpackage`; Windows packaging can be produced from Linux and includes a portable Windows Java runtime. Never include a user's media, database, local settings, Firebase credentials, or TMDB key in a bundle.

GitHub Actions runs [.github/workflows/release.yml](.github/workflows/release.yml) when a `v*` tag is pushed. For an approved release, update the version and release notes, build and inspect both archives, commit the intended source changes, then push the version tag (for example, `v0.3.0`). The workflow publishes both archives and checksums to the GitHub release. Do not move a tag or publish a release until the tagged source and package contents have been reviewed.

## Current scope

The web app is an early port, not a complete replacement for every Flutter feature. Notification preferences and reminders, account recovery email, Amharic localization, and richer account-security flows remain follow-on work. Registration and sign-in currently use email and password. Native JavaFX installers can be considered after the responsive web experience reaches feature parity.
