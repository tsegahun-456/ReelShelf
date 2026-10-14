# Movie Watchlist Web

The Java web rebuild lives alongside the completed Flutter app. This first vertical slice contains:

- Responsive dark and red discovery pages with trending, popular, new release, top rated, upcoming movie, and TV series shelves.
- Movie and TV search/discovery with genre, year, and minimum-rating filters.
- Movie and TV detail pages with trailers, cast, genres, runtime, and TV season metadata.
- Server-side TMDB requests. The API key stays in the Spring server environment.
- Email/password accounts that can use Firebase Authentication when configured; local PostgreSQL login remains available before Firebase setup.
- A movie and TV watchlist plus watched history and one-to-five-star ratings, synchronized through per-user Firestore documents when online and cached in PostgreSQL on each PC.
- Home-page recommendations based on the signed-in user's recently saved titles.
- A profile overview with account details and collection counts.
- An installable PWA shell with a useful offline page; local movie playback and the cached library stay on this PC. Cross-PC watchlist and watched-history changes need an internet connection.
- A local media library with separate movie and series shelves, TMDB poster/details matching, and series grouped into season folders with ordered episodes. Poster images are stored in PostgreSQL for offline use and removed when their last local video disappears. The library scans configured folders, streams local video, and supports matching external SRT/VTT captions plus embedded text subtitle tracks in formats FFmpeg can convert to WebVTT.
- A dedicated cinema-style local player with seeking, skip controls, remembered resume position, volume, playback speed, keyboard shortcuts, subtitle-track selection, fullscreen, theater mode, picture-in-picture, and optional next-episode autoplay.
- Flyway-managed schema creation and Docker Compose packaging.

The Flutter app remains unchanged and is the behavior reference while features are ported.

## Documentation

Comprehensive engineering documentation is available in the [`docs/`](docs/) directory:

- [**Microsoft Word Document (.docx)**](docs/Movie_Watchlist_Web_SRS_Comprehensive.docx): Complete 80+ page formatted Word document with all 18 embedded visual diagrams, formal tables, and IEEE specification.
- [**Comprehensive Academic SRS & SDD Master Specification (Markdown)**](docs/SRS_COMPREHENSIVE.md): Complete 5-chapter institutional and enterprise specification adhering to the Dire Dawa University Department of Software Engineering standard (Chapters 1–5, Cost of Inaction, 9 Sequence Diagrams, 3 DFD Levels, Class Diagrams, Activity Diagrams, ERD, and RTM Matrix).
- [**Software Requirements Specification (SRS)**](docs/SRS.md): IEEE 830 / ISO 29148 compliant requirements, functional breakdown, and acceptance criteria.
- [**Architecture & Technical Design (SDD)**](docs/ARCHITECTURE.md): Subsystems, security boundary layers, and Mermaid sequence workflows (streaming, subtitle transcoding, cloud sync).
- [**Database & Data Model Specification**](docs/DATABASE.md): Entity-Relationship Diagram (ERD), Flyway schema migration history (V1–V8), and Cloud Firestore data schema.
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
java -Dspring.profiles.active=preview -jar target/movie-watchlist-web-0.1.0-SNAPSHOT.jar --server.port=8081
```

The preview uses PostgreSQL and keeps accounts and lists when the app restarts. Configure `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for your local database in `.env`.

The preview scans `/media/tsega/New Volume` every minute (or the comma-separated paths in `MEDIA_ROOTS`). It ignores the Music, recycle-bin, and Windows system folders. New files appear in **My media** without copying video into PostgreSQL. TMDB posters and descriptions are matched in the background when the key and internet are available. Movies and series have separate shelves; opening a series shows season folders and sorted episodes. The player remembers playback position and offers speed, seek, keyboard, picture-in-picture, fullscreen/theater, and next-episode controls. It discovers embedded subtitles with `ffprobe` and converts selected text tracks to WebVTT with `ffmpeg`; matching `.srt` and `.vtt` sidecars remain available as well. Image-based DVD/PGS subtitles are listed but cannot be shown as selectable browser captions. Display titles can be corrected in the library. The local library preview is viewable without sign-in and binds to localhost only; account-backed watchlists still require sign-in.

Docker Compose installs FFmpeg in the application container and mounts `${MEDIA_HOST_ROOT}` read-only at `/media/library`; change that environment value if the partition is mounted elsewhere. If running the Spring app directly on the host instead of Docker, install FFmpeg and ensure both `ffmpeg` and `ffprobe` are on the app's `PATH` (or set `FFMPEG_COMMAND` and `FFPROBE_COMMAND`). The player will still show matching sidecar subtitle files if those tools are unavailable.

## Current scope

The web app is an early port, not a complete replacement for every Flutter feature. Notification preferences and reminders, account recovery email, Amharic localization, and richer account-security flows remain follow-on work. Registration and sign-in currently use email and password. Native JavaFX installers can be considered after the responsive web experience reaches feature parity.
