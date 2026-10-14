# Software Requirements Specification (SRS)
## Movie Watchlist Web Application

**Document Version:** 1.0.0  
**Standard:** IEEE 830 / ISO/IEC/IEEE 29148 Compliant  
**Project:** Movie Watchlist Web  
**Target Platform:** Linux / Containerized (Docker) / Cross-Platform JVM  
**Date:** October 2026  
**Status:** Approved / Baselined  

---

## 1. Introduction

### 1.1 Purpose
This Software Requirements Specification (SRS) document provides a formal, comprehensive description of the functional, non-functional, and interface requirements for the **Movie Watchlist Web Application** (`com.segi.watchlist`). It serves as the authoritative technical baseline for developers, system architects, test engineers, and project stakeholders.

### 1.2 Document Conventions
- **Requirement Identifiers:** 
  - `FR-x.y`: Functional Requirements.
  - `NFR-x.y`: Non-Functional Requirements.
  - `EIR-x.y`: External Interface Requirements.
  - `SEC-x.y`: Security Requirements.
- **Priority Keywords:** Requirements are prioritized according to RFC 2119:
  - **SHALL / MUST:** Mandatory requirement.
  - **SHOULD:** Highly recommended requirement.
  - **MAY:** Optional or discretionary requirement.

### 1.3 Intended Audience
- **Software Engineers:** For implementation, refactoring, and code review.
- **System Administrators / DevOps:** For deployment orchestration, container packaging, and media volume binding.
- **Quality Assurance Engineers:** For test plan generation, automated testing, and validation against acceptance criteria.
- **Project Stakeholders:** For feature verification and parity tracking against reference mobile implementations.

### 1.4 Product Scope
Movie Watchlist Web is a high-performance, responsive Spring Boot application designed to provide:
1. **Cloud Catalog Discovery:** Exploration of global movies and TV series powered by the The Movie Database (TMDB) API.
2. **Personal Collection Management:** Persistent user watchlists, watched history logs, and 1–5 star rating systems.
3. **Hybrid Cloud Synchronization:** Dual-tier architecture syncing collection data to Google Cloud Firestore using client-side Firebase Identity tokens, with local PostgreSQL / H2 persistence and offline-first fallback.
4. **Local Media Library Management:** Filesystem scanning of user-defined directories, automatic episode hierarchy grouping (Season $\rightarrow$ Ordered Episodes), fuzzy matching to TMDB metadata, and local poster image caching.
5. **Cinema-Grade Web Video Player:** In-browser media streaming with HTTP 206 Partial Content range requests, automatic on-the-fly FFmpeg/ffprobe subtitle track extraction (WebVTT), external SRT/VTT sidecar synchronization, playback speed tuning, and resume-state retention.
6. **Progressive Web App (PWA):** Service worker-backed offline shell enabling local media playback and cached list browsing without an active internet connection.

### 1.5 References
- IEEE Std 830-1998: *IEEE Recommended Practice for Software Requirements Specifications*.
- ISO/IEC/IEEE 29148:2018: *Systems and software engineering — Life cycle processes — Requirements engineering*.
- RFC 7233: *Hypertext Transfer Protocol (HTTP/1.1): Range Requests*.
- W3C Recommendation: *WebVTT: The Web Video Text Tracks Format*.
- TMDB API Documentation: `https://developer.themoviedb.org/docs`.
- Firebase Authentication & Cloud Firestore REST API Specifications.

---

## 2. Overall Description

### 2.1 Product Perspective
Movie Watchlist Web is the modern web-based companion and evolution of the existing mobile application. It is architected as an autonomous self-hosted service packaged via Docker Compose, yet capable of running as a standalone lightweight desktop jar with embedded H2 (`preview` / `portable` profiles). 

```
+-------------------------------------------------------------------------+
|                              Web Browser                                |
|  Thymeleaf Responsive UI / Cinema Player / PWA Service Worker Cache     |
+------------------------------------+------------------------------------+
                                     | HTTP/1.1 (Port 8080/8081)
+------------------------------------v------------------------------------+
|                   Movie Watchlist Web (Spring Boot 4)                  |
|  Security | Catalog | Library | Watchlist | Watched | Cloud Sync Engine |
+-------+--------------------+---------------------+--------------+-------+
        |                    |                     |              |
        v                    v                     v              v
+---------------+    +---------------+    +----------------+  +-----------+
| PostgreSQL/H2 |    | Local Storage |    | Cloud Services |  |  FFmpeg / |
| Flyway Schema |    | Media Volumes |    | TMDB & Firebase|  |  FFprobe  |
+---------------+    +---------------+    +----------------+  +-----------+
```

### 2.2 Product Functions (High-Level)
1. **User Authentication & Identity Federation:** Local BCrypt password authentication seamlessly bridged with Firebase Authentication identity tokens.
2. **Catalog Browsing & Search:** Multi-criteria exploration (Trending, Popular, Top Rated, Upcoming) with genre, release year, and minimum rating filters.
3. **Watchlist & Rating Tracking:** State tracking (To-Watch, Watched, Star Ratings) persisted locally and cloud-synchronized.
4. **Local Storage Indexing:** Automated, recurring traversal of mounted host folders to discover `.mp4`, `.mkv`, `.avi`, and `.webm` files without database duplication of raw video binaries.
5. **On-the-Fly Subtitle Extraction:** Dynamic extraction of embedded text subtitle tracks (SubRip, ASS, Mov_Text) into standard WebVTT via external `ffmpeg` subprocesses.
6. **Hardware-Agnostic Video Streaming:** Chunked byte-range delivery supporting instant seeking and low memory footprint on basic host processors.

### 2.3 User Classes and Characteristics
- **Standard User:** Browses catalog, maintains personalized watchlists, streams local movies, adjusts player settings. Requires responsive layout on mobile, tablet, and desktop.
- **Media Curator / Home Server Administrator:** Configures scan roots (`media_scan_root`), updates API keys (`TMDB_API_KEY`), triggers manual metadata rescans, overrides title matching errors.
- **Offline / Portable User:** Runs the application locally on laptops or offline PCs without cloud access or Docker dependencies.

### 2.4 Operating Environment
- **Server OS:** Linux (Ubuntu 22.04 LTS / Debian 12 or newer recommended), macOS, or Windows (WSL2 / Docker Desktop).
- **Runtime Environment:** Java Virtual Machine (JDK 21 LTS or newer).
- **Container Engine:** Docker Engine 24+ and Docker Compose v2+.
- **Database:** PostgreSQL 16+ (Standard Production) or H2 Database Engine 2.2+ (Portable/Preview profile).
- **External Binaries:** FFmpeg (version 4.4+) and FFprobe on system `PATH`.
- **Target Client Browsers:** Chromium-based browsers (Chrome, Edge, Brave 115+), Mozilla Firefox (115+), Apple Safari (16+).

### 2.5 Design and Implementation Constraints
1. **Low Host Resource Utilization:** The application must operate smoothly on basic-tier CPUs. Transcoding of raw video streams is prohibited; media streaming must utilize direct byte-range passthrough.
2. **Subprocess Isolation:** FFmpeg and FFprobe executions for subtitle processing must be non-blocking, sandboxed, and time-bounded to prevent zombie processes or thread pool starvation.
3. **No Private Cloud Secrets:** The system must not require private Google Service Account private keys. Firestore cloud operations must strictly rely on short-lived user tokens acquired via client-level Firebase REST APIs.
4. **Network Resilience:** The application must remain fully functional for local media playback and viewing cached lists when internet access is absent.

### 2.6 Assumptions and Dependencies
- TMDB API service availability and valid API key configuration.
- Video files stored on host disks must use codecs natively decodeable by standard HTML5 video elements (e.g., H.264/AVC, H.265/HEVC where supported, VP9, AV1, AAC, MP3).
- Media storage directories have read access permissions granted to the application process or Docker container mount (`/media/library`).

---

## 3. External Interface Requirements

### 3.1 User Interfaces
- **UI-1: Navigation Bar & Responsive Shell:**
  - Dark-and-red cinema aesthetic.
  - Fixed top bar containing: Brand logo, Catalog links (Movies, TV Shows), My Media Library, Watchlist, Watched History, Search bar, and Profile/Auth controls.
  - Mobile bottom navigation drawer for screens $\le 768\text{px}$.
- **UI-2: Catalog Discovery View:**
  - Horizontal carousel rails (`MediaRail`) for Trending, Popular, Top Rated, and Upcoming titles.
  - Interactive filter bar supporting Genre dropdowns, Release Year range, and Minimum Vote Average sliders.
- **UI-3: Detail Modal / Page:**
  - High-resolution backdrop hero banner, title, tagline, synopsis, runtime, age certifications.
  - Cast rail with actor profile images, trailer modal embedding official YouTube previews.
  - Action buttons: "Add to Watchlist", "Mark as Watched", "Rate (1-5 Stars)".
- **UI-4: Local Media Library Interface:**
  - Dedicated shelves separating Feature Movies from TV Series.
  - TV Series view groups episodes hierarchically: Series Header $\rightarrow$ Season Tabs $\rightarrow$ Ordered Episode Grid.
  - Quick actions: Rename display title, Trigger TMDB metadata match, Play video.
- **UI-5: Cinema Player View:**
  - Full-viewport HTML5 video container with custom control overlay.
  - Controls: Play/Pause, Seek bar with hover timestamps, 10-second skip back/forward, Volume slider, Playback rate selector (0.5x to 2.0x), Audio track selector, Subtitle track modal, Theater Mode toggle, Fullscreen toggle, Picture-in-Picture (PiP).
  - Next-episode auto-advance notification card when approaching end-of-file.

### 3.2 Hardware & System Interfaces
- **HWI-1: Host Storage Volumes:** Mount point `/media/library` accessible for reading video files up to $100\text{ GB}$ per single file.
- **HWI-2: CPU Architecture:** x86_64 and aarch64 (ARM64) architecture compatibility.

### 3.3 Software Interfaces
- **SWI-1: The Movie Database (TMDB) API v3:**
  - Endpoints: `/3/trending/{type}/{time_window}`, `/3/movie/{id}`, `/3/tv/{id}`, `/3/search/multi`, `/3/discover/movie`, `/3/discover/tv`.
  - Authentication: Bearer Token or `api_key` query parameter.
- **SWI-2: Firebase Identity Toolkit API:**
  - Endpoint: `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword` and `accounts:signUp`.
  - Exchanges user credentials for Firebase ID Token and Refresh Token.
- **SWI-3: Google Cloud Firestore REST API:**
  - Endpoint: `https://firestore.googleapis.com/v1/projects/{projectId}/databases/(default)/documents/users/{uid}`.
  - Read/write access scoped to authenticated user ID tokens.
- **SWI-4: FFmpeg & FFprobe:**
  - Invoked via `java.lang.ProcessBuilder`.
  - `ffprobe -v error -show_entries stream=index:stream_tags=language,title:codec_name -of json <file>` for track discovery.
  - `ffmpeg -v error -i <file> -map 0:<stream_idx> -f webvtt -` piped directly to HTTP response streams.

### 3.4 Communications Interfaces
- **CI-1: HTTP/1.1 & HTTP/2:** Standard protocol for all client-server communications over TCP ports 8080 (default) or 8081 (preview).
- **CI-2: Byte-Range Streaming:** Compliance with RFC 7233 via `ResourceRegion` supporting `bytes=<start>-<end>` headers and `206 Partial Content` responses.
- **CI-3: WebVTT Subtitle Transport:** MIME type `text/vtt;charset=UTF-8` with dynamic conversion from SubRip (`.srt`) microsecond timecodes (`00:00:00,000` $\rightarrow$ `00:00:00.000`).

---

## 4. System Features & Functional Requirements

### 4.1 Module 1: Authentication & Identity Management
- **FR-1.1: Local Account Creation:**
  - The system SHALL accept user registration with an email address, display name, and password ($\ge 8$ characters).
  - The system SHALL hash passwords using BCrypt with a minimum cost factor of 10 prior to database persistence.
- **FR-1.2: Firebase Identity Federation:**
  - When `FIREBASE_WEB_API_KEY` and `FIREBASE_PROJECT_ID` are configured, the system SHALL authenticate credentials against the Firebase Identity Toolkit.
  - Upon first successful Firebase sign-in, the system SHALL associate the returned `firebase_uid` with the local `app_user` record (`V6` migration).
- **FR-1.3: Persistent Session ("Keep Me Signed In"):**
  - The system SHALL provide a persistent token repository (`persistent_logins`) implementing Spring Security's `JdbcTokenRepositoryImpl` (`V7` migration).
  - The system SHALL encrypt Firebase Refresh Tokens using AES-GCM-256 (`TokenCipher`) and persist them in `firebase_credential`.
  - Upon session expiration, the system SHALL automatically refresh the user's Firebase ID token using the encrypted refresh token without prompting for re-login.
- **FR-1.4: Session Teardown:**
  - On user logout, the system SHALL invalidate the HTTP session, remove `REMEMBERME` cookies, delete database tokens in `persistent_logins`, and purge cached credentials.

### 4.2 Module 2: Media Discovery & TMDB Catalog Integration
- **FR-2.1: Server-Side API Key Encapsulation:**
  - All requests to TMDB SHALL be executed server-side via `MovieCatalogService`. The client browser SHALL NOT have access to raw TMDB API keys.
- **FR-2.2: Curated Shelves:**
  - The system SHALL query, aggregate, and present curated media rails: Trending Movies, Trending TV, Popular Releases, Top Rated, and Upcoming.
- **FR-2.3: Multi-Parameter Search & Filtering:**
  - The search interface SHALL support full-text title queries alongside multi-select genre filters, specific release year, and vote average thresholds.
- **FR-2.4: Title Details Aggregation:**
  - The detail endpoint (`/movie/{id}`, `/tv/{id}`) SHALL fetch and display synopsis, runtime, official trailer key (YouTube ID), genre badges, certifications, and cast filmography.
- **FR-2.5: Resilient Fallback:**
  - If TMDB is unconfigured or unreachable, the system SHALL display clear warning notices while preserving full access to the local media library and offline-cached lists.

### 4.3 Module 3: Watchlist & Watched History Tracking
- **FR-3.1: Watchlist Item Management:**
  - Authenticated users SHALL be able to add or remove any TMDB Movie or TV Show to their personal Watchlist.
  - The system SHALL enforce uniqueness per user, TMDB ID, and media type (`CONSTRAINT watchlist_entry_unique_media`).
- **FR-3.2: Watched History & Rating:**
  - Users SHALL be able to mark items as watched and assign an optional 1-to-5 star rating (`CONSTRAINT watched_entry_user_rating_check`).
  - Marking an item as watched SHALL optionally prompt or allow automated removal from the Watchlist.
- **FR-3.3: Personalized Recommendations:**
  - The home discovery view SHALL generate dynamic recommendations based on the genres and keywords of the user's most recently saved watchlist entries.

### 4.4 Module 4: Dual-Tier Cloud Sync & Offline-First Engine
- **FR-4.1: Firestore Document Mapping:**
  - The system SHALL maintain user lists in Cloud Firestore under `/users/{firebase_uid}/watchlist` and `/users/{firebase_uid}/watched`.
- **FR-4.2: Bi-directional Reconciliation:**
  - On login, the system SHALL fetch remote Firestore documents and perform a two-way merge with the local PostgreSQL database, taking the latest modified timestamps.
- **FR-4.3: Offline-First Operation:**
  - When network access to Firebase is unavailable, all list queries SHALL be served immediately from the local PostgreSQL / H2 database.
  - Mutating operations performed offline SHALL be marked for deferred sync upon network reconnection.

### 4.5 Module 5: Local Media Library Scanner & Metadata Linker
- **FR-5.1: Recursive Directory Traversal:**
  - `MediaScanner` SHALL recursively traverse all paths configured in `media_scan_root` (`V8` migration) or `MEDIA_ROOTS`.
  - The scanner SHALL filter files matching supported video extensions: `.mp4`, `.mkv`, `.avi`, `.webm`, `.mov`, `.m4v`.
  - The scanner SHALL ignore system directories: `$RECYCLE.BIN`, `System Volume Information`, `.git`, `.vscode`, and user-defined exclusions.
- **FR-5.2: Series & Episode Hierarchy Detection:**
  - The scanner SHALL parse episode filenames using regex patterns matching `SxxExx`, `Season x Episode y`, and numbered scene formats.
  - Detected episodes SHALL be cataloged with `series_title`, `season_number`, `episode_number`, and `episode_title`.
- **FR-5.3: Automated TMDB Fuzzy Matching:**
  - `LocalMediaTmdbMatcher` SHALL perform Levenshtein-distance fuzzy matching between local directory/file names and TMDB search results.
  - Matches with confidence score $\ge 0.55$ SHALL automatically populate `tmdb_id`, `tmdb_poster_path`, and `tmdb_overview`.
- **FR-5.4: Local Poster Caching:**
  - TMDB posters associated with local files SHALL be downloaded and cached in the database table `local_media_poster_cache` as binary `BYTEA` data (`V5` migration).
  - Poster endpoints (`/library/posters/media/{id}`) SHALL serve cached images with HTTP `Cache-Control: max-age=86400, private`, enabling full offline poster display.
- **FR-5.5: Database Pruning:**
  - Files removed from host disks SHALL be detected during subsequent scans and pruned from `local_media`.
  - Cached posters with zero remaining references in `local_media` SHALL be automatically purged from `local_media_poster_cache`.

### 4.6 Module 6: Cinema Web Video Player & Subtitle Pipeline
- **FR-6.1: Chunked Byte-Range Streaming:**
  - The `/library/{id}/video` endpoint SHALL support HTTP range requests (`Range: bytes=start-end`), responding with `206 Partial Content` and `Content-Range` headers.
- **FR-6.2: Subtitle Auto-Detection:**
  - The system SHALL locate external sidecar subtitle files sharing the video file's base name (e.g., `movie.en.srt`, `movie.vtt`).
  - Sidecar `.srt` files SHALL be dynamically converted to valid WebVTT format on demand (`/library/{id}/subtitle`).
- **FR-6.3: Embedded Subtitle Extraction:**
  - The `/library/{id}/subtitle/tracks` endpoint SHALL execute `ffprobe` to list all embedded text subtitle streams.
  - The `/library/{id}/subtitle/embedded/{trackIndex}` endpoint SHALL spawn `ffmpeg` to extract and transcode the selected stream to WebVTT on the fly.
  - Incompatible bitmap-based subtitles (DVD VOBSUB, Blu-ray PGS) SHALL be identified as non-renderable in HTML5 players.
- **FR-6.4: Playback State Persistence:**
  - The player SHALL record current playback timestamp in browser `localStorage` periodically (every 5 seconds).
  - Re-opening a video SHALL prompt the user or automatically resume playback from the saved timestamp.
- **FR-6.5: Next Episode Auto-Play:**
  - When playing an episode, the player SHALL determine the subsequent episode in the season order and present an auto-play overlay within the last 30 seconds of playback.

---

## 5. Non-Functional Requirements (NFR)

### 5.1 Performance Requirements
- **NFR-1.1: Video Streaming Latency:** The player must start playback within $1.5\text{ seconds}$ on a local gigabit network connection for a 1080p MP4/MKV stream.
- **NFR-1.2: Subtitle Generation Latency:** Embedded subtitle extraction via FFmpeg must begin streaming WebVTT text within $500\text{ ms}$ of track selection.
- **NFR-1.3: Database Query Speed:** All local library queries (searching, shelf grouping, pagination) must execute in $< 50\text{ ms}$ for libraries up to 10,000 files.

### 5.2 Reliability & Fault Tolerance
- **NFR-2.1: Graceful Degradation:** If external services (TMDB, Firebase) are down, local playback, scanning, and cached browsing must continue uninterrupted.
- **NFR-2.2: Subprocess Containment:** In the event of an FFmpeg timeout or corrupted video header, the spawned process must be forcefully killed after $15\text{ seconds}$ without crashing the Spring application thread.

### 5.3 Security Requirements
- **SEC-1.1: Authentication Security:** Passwords stored using BCrypt. Session cookies configured with `HttpOnly` and `SameSite=Lax`.
- **SEC-1.2: Path Traversal Prevention:** The media streaming and subtitle endpoints must validate that requested file paths strictly reside within configured scan roots, rejecting any relative `../` traversal attempts.
- **SEC-1.3: Credential Cryptography:** Cloud refresh tokens must be encrypted using AES-GCM-256 with keys derived from `security.remember-me.key` or a persistent signing key.
- **SEC-1.4: Cache-Control Header Enforcing:** HTML responses containing user session details must include `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` (`NoStoreHtmlFilter`).

### 5.4 Usability & Accessibility
- **NFR-4.1: Responsive Design:** The UI must be fully functional across mobile viewports ($360\text{px}$ width), tablets, and $4\text{K}$ desktop monitors.
- **NFR-4.2: Keyboard Controls:** The cinema player must support standard keyboard shortcuts: `Space` (Play/Pause), `F` (Fullscreen), `M` (Mute), `Arrow Left/Right` (Seek $\pm 10\text{s}$), `Arrow Up/Down` (Volume $\pm 10\%$).

---

## 6. Verification & Acceptance Criteria Matrix

| Req ID | Requirement Summary | Verification Method | Acceptance Criteria |
| :--- | :--- | :--- | :--- |
| **FR-1.1** | Local Account Registration | Automated Unit & Integration Test | User created in `app_user` with valid BCrypt hash; duplicate email returns 409 conflict. |
| **FR-2.3** | TMDB Search & Filters | Integration Test | Query with genre and year returns filtered `MovieCard` list matching criteria. |
| **FR-4.2** | Offline-to-Cloud Sync | System Test | Watchlist items created offline persist in PostgreSQL and sync to Firestore when internet is restored. |
| **FR-5.1** | Filesystem Media Scan | Integration Test | Video files discovered in `/media/library`; files in `$RECYCLE.BIN` ignored. |
| **FR-6.1** | HTTP Range Video Streaming | Network / Header Inspection | Request with `Range: bytes=0-1024` returns HTTP 206 with `Content-Range: bytes 0-1024/<total>`. |
| **FR-6.3** | Embedded Subtitle Extraction | End-to-End Test | Video with embedded SRT track returns valid `WEBVTT` text on `/library/{id}/subtitle/embedded/0`. |
| **SEC-1.2**| Path Traversal Defense | Security Vulnerability Test | Request with `../../etc/passwd` returns HTTP 400/404; no file leaked. |

---

*End of Software Requirements Specification.*
