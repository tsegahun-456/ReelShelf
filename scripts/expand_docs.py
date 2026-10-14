#!/usr/bin/env python3
import os

doc_path = '/home/tsega/Documents/java-web/docs/SRS_COMPREHENSIVE.md'
with open(doc_path, 'r', encoding='utf-8') as f:
    text = f.read()

# 1. Tabular Use Case Specifications to add in Section 4.2
use_case_tables = """
### 4.2.1 Detailed Tabular Use Case Specifications

#### UC01: User Registration, Local Authentication & Firebase Federation
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC01** |
| **Use Case Name** | User Registration, Authentication & Identity Federation |
| **Primary Actor** | Unauthenticated Visitor / Registered User |
| **Secondary Actors** | Firebase Identity Toolkit API, Local Relational Database Engine |
| **Description** | Enables a visitor to register a new account or authenticate an existing session using local BCrypt password verification or federated Firebase Authentication with persistent session tokens. |
| **Preconditions** | System is online and reachable via HTTP/HTTPS port 8080 (or 8081). Local database is initialized with Flyway V1-V7 schema. |
| **Trigger** | User navigates to `/login` or `/signup` and submits the authentication form. |
| **Main Success Scenario** | 1. User accesses the `/login` view.<br>2. User enters valid email address and password, optionally checking "Keep me signed in".<br>3. Security filter chain intercepts request and routes to `FirebaseAuthenticationProvider`.<br>4. Provider queries Firebase Identity Toolkit via REST API with user credentials.<br>5. Firebase validates credentials and returns `idToken`, `refreshToken`, and `localId`.<br>6. Provider queries local `app_user` table for matching email.<br>7. System links `firebase_uid` to `app_user` record (Flyway V6).<br>8. `FirebaseCredentialService` encrypts `refreshToken` using AES-GCM-256 (`TokenCipher`) and persists in `firebase_credential`.<br>9. If Remember-Me is checked, `persistent_logins` generates series and token hashes.<br>10. System establishes authenticated `SecurityContext` and redirects user to `/`. |
| **Alternative Flows** | **A1: Firebase Unconfigured or Offline:**<br>1. In step 4, if Firebase properties are absent or network is unreachable, provider falls back to local database authentication.<br>2. System queries `app_user` for email and compares password hash via `BCryptPasswordEncoder`.<br>3. If match succeeds, local session is established without cloud sync capabilities.<br>**A2: Account Registration (`/signup`):**<br>1. User provides email, display name, and password (>= 8 chars).<br>2. System verifies email uniqueness in `app_user`.<br>3. Password is encrypted with BCrypt (cost factor 10) and record inserted into `app_user`.<br>4. If Firebase is active, account is automatically provisioned in Firebase Auth. |
| **Exception Flows** | **E1: Duplicate Email:** System rejects registration with HTTP 409 Conflict and displays "An account with this email already exists".<br>**E2: Invalid Credentials:** System displays "Invalid email or password" without revealing which parameter was incorrect.<br>**E3: Compromised Remember-Me Cookie:** If token in cookie does not match database series, system purges all remember-me tokens for the user and requires password login. |
| **Postconditions** | Authenticated user session established with role `ROLE_USER`. Session cookies set with `HttpOnly` and `SameSite=Lax`. |
| **Priority** | High (Must-Have) |

#### UC02: TMDB Catalog Exploration, Multi-Filtering & Detail Retrieval
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC02** |
| **Use Case Name** | TMDB Catalog Exploration, Search & Multi-Parametric Filtering |
| **Primary Actor** | Registered User / Guest Viewer |
| **Secondary Actors** | The Movie Database (TMDB) v3 API Server |
| **Description** | Enables users to browse curated catalog carousels, perform multi-parametric searches with genre, release year, and minimum rating filters, and inspect comprehensive media metadata including cast and trailers. |
| **Preconditions** | Valid `TMDB_API_KEY` configured in application environment. External network access to `api.themoviedb.org` available. |
| **Trigger** | User visits home page (`/`) or submits a query on `/search`. |
| **Main Success Scenario** | 1. User navigates to `/search` or home view.<br>2. `CatalogController` invokes `MovieCatalogService`.<br>3. Service requests trending and curated rails from TMDB v3 REST API endpoints.<br>4. TMDB returns JSON payloads containing titles, release dates, poster paths, and vote averages.<br>5. Service maps payloads into domain records (`MovieCard`, `MediaRail`).<br>6. View renders carousels with dark-and-red cinema aesthetic.<br>7. User inputs search string "Interstellar", selects Genre "Sci-Fi (878)", Year "2014", and Min Rating "8.0".<br>8. Controller invokes `search()` with filtered query parameters.<br>9. TMDB returns matching results; service applies post-filtering and sorts by popularity.<br>10. User clicks on title card; system displays `/movie/{id}` with synopsis, runtime, cast, and embedded YouTube trailer. |
| **Alternative Flows** | **A1: TMDB API Key Missing or Service Down:**<br>1. System catches `TmdbNotConfiguredException` or `RestClientException`.<br>2. System displays informative warning banner: "TMDB catalog service is temporarily unavailable. Local media library and offline watchlists remain fully operational."<br>3. Discovery rails are gracefully omitted; search redirects to local library search. |
| **Exception Flows** | **E1: Malformed Search Parameters:** System sanitizes query inputs and ignores non-numeric year/genre parameters without throwing HTTP 500.<br>**E2: Invalid TMDB ID:** Detail endpoint returns HTTP 404 with custom error page. |
| **Postconditions** | Catalog results presented with image assets loaded from TMDB CDN or fallback placeholders. |
| **Priority** | High (Must-Have) |

#### UC03: Personal Watchlist & Watched Rating Management
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC03** |
| **Use Case Name** | Personal Watchlist & Watched Rating Management |
| **Primary Actor** | Authenticated User |
| **Secondary Actors** | Local Relational Database, Firebase Media Sync Engine |
| **Description** | Allows an authenticated user to save titles to a prospective Watchlist, log watched titles with 1-to-5 star ratings, update ratings, and trigger background cloud synchronization. |
| **Preconditions** | User must be logged in (`SecurityContext` contains valid `Account` principal). |
| **Trigger** | User clicks "Add to Watchlist", "Mark as Watched", or selects star rating on title modal. |
| **Main Success Scenario** | 1. User clicks "Mark as Watched" on a title card.<br>2. System opens rating modal permitting 1 to 5 star selection.<br>3. User selects 5 stars and submits form (POST to `/watched/add`).<br>4. `WatchedController` invokes `WatchedService.recordWatched()`.<br>5. Service checks for existing entry in `watched_entry`.<br>6. Service persists record with `user_id`, `tmdb_id`, `media_type`, `user_rating=5`, and `watched_at=CURRENT_TIMESTAMP`.<br>7. If title currently exists in `watchlist_entry`, system automatically removes it to prevent list conflict.<br>8. Service dispatches asynchronous sync event to `FirebaseMediaSyncService`.<br>9. System returns HTTP 200 JSON confirmation and updates button state to "Watched (5 Stars)". |
| **Alternative Flows** | **A1: Removing from Watchlist:** User clicks "Remove"; system executes DELETE in `watchlist_entry` and reconciles deletion in Cloud Firestore.<br>**A2: Updating Star Rating:** User re-rates a watched title; system executes UPDATE on `watched_entry.user_rating` and syncs timestamp. |
| **Exception Flows** | **E1: Unauthenticated Mutation:** Attempting list modification without active session redirects to `/login`.<br>**E2: Invalid Rating Value:** Rating outside 1-5 range is rejected by database check constraint (`watched_entry_user_rating_check`). |
| **Postconditions** | Relational database updated. Firestore cloud document created or updated. Dynamic recommendations updated on next home page visit. |
| **Priority** | High (Must-Have) |

#### UC04: Zero-Copy Byte-Range Video Streaming
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC04** |
| **Use Case Name** | Zero-Copy HTTP 206 Byte-Range Video Streaming |
| **Primary Actor** | Registered User / Guest Viewer |
| **Secondary Actors** | Host Filesystem Storage Volume, Browser HTML5 Video Engine |
| **Description** | Delivers high-definition local media files directly to web browsers using RFC 7233 partial content range requests without CPU-intensive server-side transcoding. |
| **Preconditions** | Media file exists in indexed `local_media` table and is accessible on host filesystem. User has permission to access library. |
| **Trigger** | User clicks "Play" button on movie or episode card (`/library/{id}/play`). |
| **Main Success Scenario** | 1. User loads cinema player interface.<br>2. HTML5 `<video>` element issues GET request to `/library/{id}/video` with header `Range: bytes=0-`.<br>3. `LibraryController.video()` invokes `scanner.require(id)`.<br>4. Controller retrieves canonical path and passes to `checkedPath()` for traversal security verification.<br>5. Controller resolves total file size on disk.<br>6. Controller packages file slice into Spring `ResourceRegion` with start offset 0 and chunk length.<br>7. Controller returns HTTP 206 Partial Content with headers `Content-Range: bytes 0-chunk/total`, `Accept-Ranges: bytes`, and `Content-Type: video/mp4`.<br>8. Browser buffers initial chunk and begins immediate hardware-accelerated playback.<br>9. User seeks timeline forward to 01:15:30.<br>10. Browser issues new range request: `Range: bytes=1572864000-`.<br>11. Controller instantly seeks file descriptor to byte offset 1572864000 and streams new chunk.<br>12. Playback resumes without buffering or CPU re-encoding spikes. |
| **Alternative Flows** | **A1: Full File Download Request:** If request lacks `Range` header, controller returns HTTP 200 with full file payload and `Accept-Ranges: bytes`. |
| **Exception Flows** | **E1: Path Traversal Attack:** If path contains `../` or escapes configured scan roots, system throws `SecurityException` and returns HTTP 400 Bad Request.<br>**E2: Missing File on Disk:** If file was deleted or drive unmounted, system returns HTTP 404 Not Found. |
| **Postconditions** | Video streamed seamlessly with host CPU utilization remaining under 2%. |
| **Priority** | High (Must-Have) |

#### UC05: Embedded & External Subtitle Transcoding
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC05** |
| **Use Case Name** | Dynamic Subtitle Track Discovery & On-the-Fly WebVTT Transcoding |
| **Primary Actor** | Registered User / Cinema Player |
| **Secondary Actors** | FFmpeg Binary, FFprobe Binary, Host Media Storage |
| **Description** | Discovers embedded text subtitle tracks within MKV/MP4 containers and transcode them in real-time to W3C WebVTT format, alongside matching external `.srt` sidecars. |
| **Preconditions** | Video file contains text subtitle streams or matching external sidecar. FFmpeg and FFprobe binaries are present on system PATH. |
| **Trigger** | Cinema player initializes tracks modal or user selects a subtitle language from player menu. |
| **Main Success Scenario** | 1. Player requests available tracks via `/library/{id}/subtitle/tracks`.<br>2. `LibraryController` queries `EmbeddedSubtitleService.tracks()`.<br>3. Service executes `ffprobe` subprocess with JSON output formatting: `ffprobe -v error -show_entries stream=index,codec_name:stream_tags=language,title -of json <file>`.<br>4. Service parses JSON output, identifying text streams (`subrip`, `ass`, `mov_text`).<br>5. System checks filesystem for matching sidecars (`movie.en.srt`, `movie.vtt`).<br>6. Controller returns JSON array of selectable subtitle tracks.<br>7. User clicks "English (Embedded Track #2)".<br>8. Player sets subtitle track source to `/library/{id}/subtitle/embedded/2`.<br>9. Controller invokes `EmbeddedSubtitleService.streamWebvtt()`.<br>10. Service spawns `ffmpeg` subprocess: `ffmpeg -v error -i <file> -map 0:2 -f webvtt -`.<br>11. Process stdout stream is piped directly into HTTP response body with MIME type `text/vtt;charset=UTF-8`.<br>12. Browser displays synchronized text captions on video canvas. |
| **Alternative Flows** | **A1: External SRT Sidecar Selected:** System reads `.srt` file, replaces comma timecodes with periods (`00:00:00,000` -> `00:00:00.000`), prepends `WEBVTT`, and returns stream. |
| **Exception Flows** | **E1: Bitmap Subtitles (PGS/VOBSUB):** System marks stream as unsupported; notifies user that image-based DVD/Blu-ray subtitles cannot be rendered in HTML5 players.<br>**E2: FFmpeg Process Timeout:** If subprocess fails to emit output within 15 seconds, process is terminated and HTTP 422 returned. |
| **Postconditions** | Text captions synchronized with audio stream without generating temporary files on disk. |
| **Priority** | High (Must-Have) |

#### UC06: Cloud Firestore Bidirectional Synchronization
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC06** |
| **Use Case Name** | Dual-Tier Cloud Firestore Synchronization & Offline Reconciliation |
| **Primary Actor** | Authenticated User |
| **Secondary Actors** | Google Cloud Firestore REST API, Firebase Identity API |
| **Description** | Synchronizes local watchlist and watched entries with Cloud Firestore documents, resolving conflicts using modified timestamps and enabling offline list editing. |
| **Preconditions** | User account is linked to valid `firebase_uid`. Network connectivity to Google Cloud APIs is available. |
| **Trigger** | User logs in, mutates list, or background sync timer fires. |
| **Main Success Scenario** | 1. `FirebaseMediaSyncService` initiates synchronization.<br>2. Service retrieves user's valid Firebase ID token (refreshing via `TokenCipher` if expired).<br>3. Service requests all documents from Firestore path `/users/{uid}/watchlist` and `/users/{uid}/watched`.<br>4. Service fetches all corresponding local entries from PostgreSQL.<br>5. Service executes reconciliation algorithm: compares `updatedAt` / `createdAt` timestamps.<br>6. If remote Firestore record is newer, local database record is upserted.<br>7. If local database record is newer, remote Firestore document is patched via REST API.<br>8. Deletions made on one platform are propagated to the other.<br>9. Sync state timestamp updated in user session. |
| **Alternative Flows** | **A1: Offline Mode (No Internet):** System catches network failure; preserves all local modifications in PostgreSQL. Sets flag `sync_pending=true`. Operates entirely offline without blocking UI. |
| **Exception Flows** | **E1: Firebase Token Expired:** System decrypts stored refresh token and requests new ID token from Firebase Identity Toolkit. If refresh token is revoked, user is prompted to re-enter cloud password. |
| **Postconditions** | Watchlist and watched records identical across local PC and cloud-connected devices. |
| **Priority** | High (Must-Have) |

#### UC07: Filesystem Directory Scanning & Media Indexing
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC07** |
| **Use Case Name** | Filesystem Traversal, Hierarchy Parsing & Media Indexing |
| **Primary Actor** | Media Curator / Background System Scheduler |
| **Secondary Actors** | Host Filesystem, Local Media Repository |
| **Description** | Traverses configured root folders, extracts video files, parses season/episode numbering via regular expressions, and maintains an up-to-date relational catalog. |
| **Preconditions** | Directory paths exist and are readable by application process (`media_scan_root`). |
| **Trigger** | Scheduled background task runs (every 60s) or user clicks "Scan Now" in Settings. |
| **Main Success Scenario** | 1. `MediaScanner` queries `media_scan_root` table for active paths.<br>2. Scanner iterates through paths, invoking `Files.walk()`.<br>3. Scanner filters files matching `.mp4`, `.mkv`, `.avi`, `.webm`, `.mov`.<br>4. For each file, scanner checks if path already exists in `local_media`.<br>5. If existing, scanner verifies file modification time and updates `last_seen_at`.<br>6. If new, scanner evaluates filename against regular expression patterns (`S(?<season>\\d+)E(?<ep>\\d+)`, `Season (?<s>\\d+) Episode (?<e>\\d+)`).<br>7. If TV pattern matches, item classified as `EPISODE` with extracted series, season, and episode numbers.<br>8. If pattern does not match, item classified as `MOVIE` with sanitized display title.<br>9. Record saved to `local_media`.<br>10. Scanner queries `local_media` for records where `last_seen_at < scan_start_time` (deleted files).<br>11. Stale records pruned from database. |
| **Alternative Flows** | **A1: Excluded System Directories:** Scanner encounters `$RECYCLE.BIN`, `System Volume Information`, or `.git`; scanner skips entire branch without processing. |
| **Exception Flows** | **E1: Permission Denied on Directory:** Scanner logs warning and continues traversal of remaining accessible folders without crashing. |
| **Postconditions** | `local_media` table accurately reflects current physical disk storage. |
| **Priority** | High (Must-Have) |

#### UC08: TMDB Fuzzy Title Matching & Local Poster Caching
| Field | Specification Details |
| :--- | :--- |
| **Use Case ID** | **UC08** |
| **Use Case Name** | TMDB Fuzzy Title Matching & Binary Poster Caching |
| **Primary Actor** | System Background Matcher / Media Administrator |
| **Secondary Actors** | The Movie Database (TMDB) API, PostgreSQL Database |
| **Description** | Associates local video files with official TMDB catalog metadata using Levenshtein distance scoring and downloads poster images into local binary storage for offline use. |
| **Preconditions** | Local files indexed in `local_media`. TMDB API key configured and reachable. |
| **Trigger** | New unlinked media discovered during scan or user triggers manual rematch. |
| **Main Success Scenario** | 1. `LocalMediaTmdbMatcher` queries unlinked items from `local_media`.<br>2. Matcher strips release year, resolution tags (1080p, 720p), and codec tags (x264, x265) from title.<br>3. Matcher queries TMDB search endpoint (`/3/search/movie` or `/3/search/tv`).<br>4. TMDB returns candidate cards.<br>5. Matcher computes normalized Levenshtein similarity score against candidates.<br>6. Candidate with highest score >= 0.55 selected as match.<br>7. Matcher attaches `tmdb_id`, `tmdb_title`, `tmdb_overview`, and `tmdb_poster_path` to `local_media`.<br>8. `LocalPosterCacheService` checks if poster is already cached in `local_media_poster_cache`.<br>9. If missing, service downloads poster binary bytes from TMDB CDN.<br>10. Service saves image into `local_media_poster_cache` with MIME type and binary `BYTEA` payload.<br>11. User reloads `/library`; view displays high-resolution poster served locally from PostgreSQL. |
| **Alternative Flows** | **A1: Low Match Score (< 0.55):** System flags item as unlinked; leaves display title intact and permits user manual rematch via UI modal. |
| **Exception Flows** | **E1: CDN Download Fails:** System retains TMDB metadata reference and retries poster download during subsequent scan cycle. |
| **Postconditions** | Metadata attached and poster stored in database, enabling full offline visual rendering. |
| **Priority** | High (Must-Have) |
"""

# Insert use case tables into Section 4.2
if '### 4.2.1 Detailed Tabular Use Case Specifications' not in text:
    text = text.replace('## 4.2 Use Case Modeling & Actor Descriptions', '## 4.2 Use Case Modeling & Actor Descriptions\n' + use_case_tables)

# 2. Add Exhaustive Data Dictionary in Section 4.9
data_dict = """
### 4.9.1 Detailed Data Dictionary & Schema Specification

#### Table 1: `app_user`
Stores unique user accounts for local authentication and links to federated cloud identities.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | No | Auto (Identity) | Primary Key | Unique system identifier for user. | `1001` |
| `email` | `VARCHAR(254)` | No | *None* | Unique Constraint | RFC 5322 valid user email address. | `user@example.com` |
| `display_name`| `VARCHAR(80)` | No | *None* | Check (>= 1 char) | User visible friendly name. | `Tsegahun M.` |
| `password_hash`| `VARCHAR(100)`| No | *None* | BCrypt Format | Salted password hash (cost factor 10). | `$2a$10$e7...` |
| `firebase_uid`| `VARCHAR(128)`| Yes | `NULL` | Unique Partial Index | Linked Firebase Auth UID (Flyway V6). | `W3eK8...` |
| `created_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | *None* | Account creation audit timestamp. | `2026-10-14 10:00:00+03` |

#### Table 2: `watchlist_entry`
Stores titles saved by users to watch in the future.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | No | Auto (Identity) | Primary Key | Unique watchlist entry identifier. | `501` |
| `user_id` | `BIGINT` | No | *None* | FK -> `app_user(id)` | Cascading delete on user deletion. | `1001` |
| `tmdb_id` | `BIGINT` | No | *None* | *None* | External TMDB media identifier. | `27205` |
| `media_type` | `VARCHAR(16)` | No | *None* | Check `('MOVIE', 'TV')` | Categorization discriminator. | `MOVIE` |
| `title` | `VARCHAR(300)` | No | *None* | *None* | Official catalog title. | `Inception` |
| `poster_path` | `VARCHAR(300)` | Yes | `NULL` | *None* | Relative path on TMDB CDN. | `/8IB2e...jpg` |
| `backdrop_path`| `VARCHAR(300)`| Yes | `NULL` | *None* | Relative backdrop image path. | `/s3T1v...jpg` |
| `release_date`| `VARCHAR(32)` | Yes | `NULL` | *None* | Release date in YYYY-MM-DD format. | `2010-07-16` |
| `vote_average`| `NUMERIC(4,2)`| Yes | `NULL` | *None* | TMDB public community score. | `8.36` |
| `created_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | Index `(user_id, created_at)` | Timestamp added to watchlist. | `2026-10-14 11:20:00+03` |
| *Composite* | *Unique Key* | No | *None* | `UNIQUE (user_id, tmdb_id, media_type)` | Prevents duplicate titles per user. | *N/A* |

#### Table 3: `watched_entry`
Stores completed viewing logs and user-assigned quality ratings.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | No | Auto (Identity) | Primary Key | Unique watched record identifier. | `801` |
| `user_id` | `BIGINT` | No | *None* | FK -> `app_user(id)` | Cascading delete on user deletion. | `1001` |
| `tmdb_id` | `BIGINT` | No | *None* | *None* | External TMDB media identifier. | `157336` |
| `media_type` | `VARCHAR(16)` | No | *None* | Check `('MOVIE', 'TV')` | Categorization discriminator. | `MOVIE` |
| `title` | `VARCHAR(300)` | No | *None* | *None* | Official catalog title. | `Interstellar` |
| `poster_path` | `VARCHAR(300)` | Yes | `NULL` | *None* | TMDB CDN poster path. | `/gEU2e...jpg` |
| `backdrop_path`| `VARCHAR(300)`| Yes | `NULL` | *None* | TMDB CDN backdrop path. | `/xJHb...jpg` |
| `release_date`| `VARCHAR(32)` | Yes | `NULL` | *None* | Release date string. | `2014-11-05` |
| `vote_average`| `NUMERIC(4,2)`| Yes | `NULL` | *None* | TMDB public score. | `8.44` |
| `user_rating` | `INTEGER` | Yes | `NULL` | Check `BETWEEN 1 AND 5` | User star rating (1 to 5 stars). | `5` |
| `watched_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | Index `(user_id, watched_at)` | Timestamp marked as watched. | `2026-10-14 12:00:00+03` |
| *Composite* | *Unique Key* | No | *None* | `UNIQUE (user_id, tmdb_id, media_type)` | Enforces single rating record per title. | *N/A* |

#### Table 4: `local_media`
Indexes physical video files discovered on host filesystem storage.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `id` | `BIGINT` | No | Auto (Identity) | Primary Key | Unique local media identifier. | `15` |
| `file_path` | `TEXT` | No | *None* | Unique Constraint | Canonical absolute path on host disk. | `/media/library/Breaking Bad/S01E01.mkv` |
| `file_name` | `VARCHAR(600)`| No | *None* | *None* | File basename with extension. | `Breaking.Bad.S01E01.720p.mkv` |
| `display_title`| `VARCHAR(400)`| No | *None* | Index `(media_kind, display_title)` | Cleaned human-readable display title. | `Breaking Bad S01E01` |
| `media_kind` | `VARCHAR(16)` | No | *None* | Check `('MOVIE', 'EPISODE')` | Shelf discriminator. | `EPISODE` |
| `series_title` | `VARCHAR(300)`| Yes | `NULL` | Index `(series_title, season, ep)` | Parsed series name for TV shows. | `Breaking Bad` |
| `season_number`| `INTEGER` | Yes | `NULL` | *None* | Numerical season (0 for specials). | `1` |
| `episode_number`| `INTEGER` | Yes | `NULL` | *None* | Numerical episode index. | `1` |
| `episode_title`| `VARCHAR(300)`| Yes | `NULL` | *None* | Parsed episode name if present. | `Pilot` |
| `subtitle_path`| `TEXT` | Yes | `NULL` | *None* | Canonical path to matching `.srt`/`.vtt`.| `/media/library/Breaking Bad/S01E01.en.srt` |
| `file_size` | `BIGINT` | No | *None* | *None* | File size in bytes. | `1572864000` |
| `modified_at` | `TIMESTAMPTZ` | No | *None* | *None* | Filesystem modification time. | `2024-05-12 18:30:00+03` |
| `title_override`| `VARCHAR(400)`| Yes | `NULL` | *None* | User manual rename override. | `Pilot Episode` |
| `last_seen_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | *None* | Last verified during scan. | `2026-10-14 13:00:00+03` |
| `tmdb_id` | `BIGINT` | Yes | `NULL` | *None* | Matched TMDB show/movie ID. | `1396` |
| `tmdb_title` | `VARCHAR(300)`| Yes | `NULL` | *None* | Matched official title. | `Breaking Bad` |
| `tmdb_overview`| `TEXT` | Yes | `NULL` | *None* | Synopsis from TMDB. | `A high school chemistry teacher...` |
| `tmdb_poster_path`| `VARCHAR(300)`| Yes | `NULL` | *None* | Matched TMDB poster CDN path. | `/ztkSp...jpg` |

#### Table 5: `local_media_poster_cache`
Stores raw poster image bytes in PostgreSQL for fast, offline image serving.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `tmdb_media_type`| `VARCHAR(16)`| No | *None* | Composite PK | Media type discriminator (`'MOVIE'`, `'TV'`). | `TV` |
| `tmdb_id` | `BIGINT` | No | *None* | Composite PK | TMDB identifier. | `1396` |
| `poster_path` | `VARCHAR(300)`| No | *None* | *None* | Remote TMDB poster relative path. | `/ztkSp...jpg` |
| `content_type` | `VARCHAR(100)`| No | *None* | *None* | Image MIME type. | `image/jpeg` |
| `poster_data` | `BYTEA` | No | *None* | *None* | Raw binary image bytes. | `\\xffd8ffe000104a...` |
| `cached_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | *None* | Cache download timestamp. | `2026-10-14 13:05:00+03` |

#### Table 6: `persistent_logins`
Standard Spring Security remember-me token repository table.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `series` | `VARCHAR(64)` | No | *None* | Primary Key | Unique session series identifier. | `k7Hs9Q...` |
| `username` | `VARCHAR(320)`| No | *None* | Index `username` | Authenticated user email. | `user@example.com` |
| `token` | `VARCHAR(64)` | No | *None* | *None* | Hashed session verification token. | `p9Ln2X...` |
| `last_used` | `TIMESTAMPTZ` | No | *None* | *None* | Timestamp of last auto-login. | `2026-10-14 13:10:00+03` |

#### Table 7: `firebase_credential`
Stores AES-GCM encrypted refresh tokens to maintain background cloud synchronization.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `email` | `VARCHAR(254)`| No | *None* | Primary Key | User email address. | `user@example.com` |
| `firebase_uid` | `VARCHAR(128)`| No | *None* | *None* | Firebase Authentication UID. | `W3eK8...` |
| `display_name` | `VARCHAR(80)` | No | `''` | *None* | Cached user display name. | `Tsegahun M.` |
| `refresh_token_encrypted`| `VARCHAR(1024)`| No | *None* | *None* | AES-256-GCM encrypted token. | `IV:Ciphertext:AuthTag` |
| `created_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | *None* | Initial registration timestamp. | `2026-10-14 10:00:00+03` |
| `updated_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | *None* | Token refresh timestamp. | `2026-10-14 13:10:00+03` |

#### Table 8: `media_scan_root`
User-configurable filesystem directory roots monitored by the background scanner.
| Column Name | Data Type | Nullable | Default | Constraints & Keys | Business Rule / Description | Sample Value |
| :--- | :--- | :---: | :---: | :--- | :--- | :--- |
| `path` | `VARCHAR(1000)`| No | *None* | Primary Key | Canonical directory path on disk. | `/media/tsega/New Volume` |
| `added_at` | `TIMESTAMPTZ` | No | `CURRENT_TIMESTAMP` | *None* | Configuration timestamp. | `2026-10-14 09:30:00+03` |
"""

if '### 4.9.1 Detailed Data Dictionary & Schema Specification' not in text:
    text = text.replace('## 4.9 Logical Database Requirements', '## 4.9 Logical Database Requirements\n' + data_dict)

# 3. Add Comprehensive Test Case Specifications in Section 4.12
test_cases_suite = """
### 4.12 Detailed System Test Case Specifications

| Test Case ID | Test Category | Target Requirement | Preconditions | Test Input Data | Step-by-Step Execution Sequence | Expected Result | Pass/Fail Criteria |
| :---: | :---: | :---: | :--- | :--- | :--- | :--- | :--- |
| **TC-SEC-01** | Security / Auth | **FR01.1** | Clean database; unauthenticated session. | Email: `test@ddu.edu.et`<br>Pass: `SecretPass123!` | 1. Navigate to `/signup`<br>2. Submit valid registration form<br>3. Inspect database `app_user` row | User created; password stored as valid BCrypt hash (`$2a$10$...`); raw password nowhere in DB. | Pass if hash verifies with BCrypt. |
| **TC-SEC-02** | Security / Auth | **FR01.1** | Account `test@ddu.edu.et` already exists. | Email: `test@ddu.edu.et`<br>Pass: `DifferentPass!` | 1. Navigate to `/signup`<br>2. Re-submit form with identical email | System rejects submission; returns HTTP 409; displays duplicate email notification. | Pass if duplicate row is prevented. |
| **TC-SEC-03** | Security / Crypto | **FR01.3** | Account with Firebase sync active. | Valid Firebase refresh token | 1. Perform login with Remember-Me<br>2. Query `firebase_credential.refresh_token_encrypted`<br>3. Attempt decryption with `TokenCipher` | Stored string is formatted ciphertext; decrypted string matches original token. | Pass if plaintext is never stored. |
| **TC-SEC-04** | Security / Pathing | **NFR01** | Media roots set to `/media/library`. | Path: `../../../../etc/passwd` | 1. Execute GET `/library/video` with traversal payload<br>2. Inspect HTTP response code | System detects path escape in `checkedPath()`; throws SecurityException; returns HTTP 400/404. | Pass if zero bytes of unauthorized file leak. |
| **TC-CAT-01** | Functional / API | **FR02.1** | Valid `TMDB_API_KEY` configured. | GET `/` | 1. Navigate to home view `/`<br>2. Inspect carousel DOM elements | Page renders Trending Movies, Popular, Top Rated, and Upcoming rails with image cards. | Pass if cards display titles and poster paths. |
| **TC-CAT-02** | Functional / Search | **FR02.2** | Valid `TMDB_API_KEY` configured. | Query: `Matrix`<br>Genre: `28 (Action)`<br>Year: `1999` | 1. Navigate to `/search`<br>2. Enter query, select genre and year<br>3. Click Search | Results return "The Matrix" (1999); non-matching genres and years filtered out. | Pass if filtered results strictly match. |
| **TC-LIST-01** | Functional / List | **FR03.1** | Authenticated user session. | TMDB ID: `27205`<br>Media: `MOVIE` | 1. Click "Add to Watchlist" on Inception card<br>2. Inspect `watchlist_entry` table<br>3. Navigate to `/watchlist` | Record inserted with `user_id`, `tmdb_id=27205`; title card appears in `/watchlist` view. | Pass if entry persists across restarts. |
| **TC-LIST-02** | Functional / List | **FR03.2** | Title exists in watchlist. | Rating: `5 Stars` | 1. Click "Mark as Watched"<br>2. Select 5 stars<br>3. Submit | Record inserted in `watched_entry`; title automatically removed from `watchlist_entry`. | Pass if moved between lists atomically. |
| **TC-SCAN-01** | Filesystem / Regex | **FR05.1** | Media root contains `Show.S02E05.mkv`. | File scan triggered | 1. Click "Scan Now" in Settings<br>2. Query `local_media` table | `media_kind='EPISODE'`, `series_title='Show'`, `season_number=2`, `episode_number=5`. | Pass if regex extracts numbers correctly. |
| **TC-SCAN-02** | Filesystem / Exclusion| **FR05.1** | Folder `$RECYCLE.BIN` containing video. | File scan triggered | 1. Execute scan<br>2. Query `local_media` | Videos inside `$RECYCLE.BIN` are ignored and not inserted into database. | Pass if excluded folder is bypassed. |
| **TC-MATCH-01**| Functional / Match | **FR05.3** | Local file `Inception.2010.mkv` indexed. | Matcher execution | 1. Matcher executes search on TMDB<br>2. Computes Levenshtein score | Score >= 0.55; `tmdb_id=27205` attached; overview and poster path populated. | Pass if metadata correctly linked. |
| **TC-CACHE-01**| Storage / Cache | **FR05.4** | Matched local media with poster path. | Matcher execution | 1. System downloads poster bytes from CDN<br>2. Query `local_media_poster_cache` | Row exists with `BYTEA` data; GET `/library/posters/media/{id}` returns HTTP 200 `image/jpeg`. | Pass if image serves with network disabled. |
| **TC-STRM-01** | Streaming / RFC 7233 | **FR06.1** | Indexed 1GB video file `test.mp4`. | Header: `Range: bytes=0-1048575` | 1. Request video URL with byte-range header<br>2. Inspect response status and headers | Response returns HTTP 206 Partial Content; `Content-Range: bytes 0-1048575/1073741824`. | Pass if range chunk matches requested bytes. |
| **TC-STRM-02** | Streaming / Seeking | **FR06.1** | Video playing in browser. | Timeline seek to 45:00 | 1. Click timeline at 45:00<br>2. Monitor browser network panel | Browser issues new range request starting at target byte offset; video plays instantly without reload. | Pass if seek latency < 500ms. |
| **TC-SUB-01** | Subtitle / Sidecar | **FR06.2** | `movie.mkv` accompanied by `movie.en.srt`.| GET `/library/{id}/subtitle` | 1. Request subtitle endpoint<br>2. Inspect content body | Response MIME is `text/vtt;charset=UTF-8`; starts with `WEBVTT`; comma timecodes converted to dots. | Pass if valid WebVTT syntax returned. |
| **TC-SUB-02** | Subtitle / Embedded | **FR06.3** | `video.mkv` containing SubRip track 2. | GET `/library/{id}/subtitle/embedded/2`| 1. Request embedded stream<br>2. Inspect process list and stream output | `ffmpeg` subprocess spawned; WebVTT text streamed directly to client; process exits cleanly. | Pass if captions render synchronized in player. |
| **TC-PERF-01** | Performance / CPU | **NFR02** | Dual-core CPU host streaming 1080p MP4. | Active playback stream | 1. Start streaming video in browser<br>2. Measure host CPU usage with `top` | Host CPU utilization remains <= 2%; zero video transcoding threads created. | Pass if CPU load stays negligible. |
| **TC-PORT-01** | Portability / H2 | **NFR04** | PostgreSQL stopped; profile `portable`. | Run `./mvnw spring-boot:run -Pportable` | 1. Start application with portable profile<br>2. Access `http://localhost:8080` | App initializes embedded H2 database (`portable-schema.sql`); library scan and playback function. | Pass if app operates without external DB. |
"""

if '### 4.12 Detailed System Test Case Specifications' not in text:
    text = text.replace('## 4.11 Requirements Traceability Matrix (RTM)', '## 4.11 Requirements Traceability Matrix (RTM)\n' + test_cases_suite)

with open(doc_path, 'w', encoding='utf-8') as f:
    f.write(text)

new_size = os.path.getsize(doc_path)
print(f'Successfully expanded file size: {new_size} bytes ({new_size/1024:.1f} KB)')
