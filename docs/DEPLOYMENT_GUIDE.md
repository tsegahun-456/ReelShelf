# Deployment, Operations & Environment Guide
## Movie Watchlist Web Application

**Document Version:** 1.0.0  
**Project:** Movie Watchlist Web (`com.segi.watchlist`)  
**Target Environments:** Docker Compose / Standalone JVM / Portable Linux  
**Date:** October 2026  
**Status:** Approved / Baselined  

---

## 1. Prerequisites & System Requirements

### 1.1 Minimum System Specifications
- **CPU:** Dual-core 64-bit x86_64 or aarch64 processor (basic tier CPU is fully supported).
- **RAM:** Minimum $1.5\text{ GB}$ free RAM ($512\text{ MB}$ for PostgreSQL, $512\text{ MB}$ for JVM, $500\text{ MB}$ OS overhead).
- **Disk Space:** $500\text{ MB}$ for application containers + storage for user media on host.

### 1.2 Software Dependencies
- **Container Mode:** Docker Engine 24.0+ and Docker Compose v2.20+.
- **Host Mode (Without Docker):**
  - OpenJDK 21 LTS or newer (`java -version`).
  - FFmpeg & FFprobe installed and available on system `$PATH` (`ffmpeg -version`, `ffprobe -version`).
  - PostgreSQL 16+ server running locally or accessible via network (unless running `portable` profile).

---

## 2. Configuration & Environment Matrix

The application is configured through environment variables supplied via `.env` (Docker Compose) or shell exports:

| Variable Name | Required | Default Value | Description & Example |
| :--- | :--- | :--- | :--- |
| `DATABASE_PASSWORD` | **Yes (Prod)** | *None* | Root password for PostgreSQL. Must be set securely in `.env`. |
| `DATABASE_URL` | No | `jdbc:postgresql://database:5432/movie_watchlist` | JDBC connection string to the PostgreSQL database. |
| `DATABASE_USERNAME` | No | `moviewatchlist` | PostgreSQL database user. |
| `DB_PORT` | No | `5432` | Host port to bind PostgreSQL container port 5432. |
| `APP_PORT` | No | `8080` | Host port to expose the web application. |
| `TMDB_API_KEY` | No | *Empty* | Your The Movie Database (TMDB) v3 API key. Enables live discovery, trailers, and fuzzy poster matching. |
| `FIREBASE_WEB_API_KEY`| No | *Empty* | Firebase Web Client API Key (from Firebase Console). Enables cloud sign-in. |
| `FIREBASE_PROJECT_ID` | No | *Empty* | Google Cloud / Firebase Project ID (e.g., `my-movie-app`). Enables Firestore sync. |
| `MEDIA_ROOTS` | No | `/media/library` | Comma-separated paths inside the container/host to scan for movies and episodes. |
| `MEDIA_HOST_ROOT` | No | `/media/tsega/New Volume` | Host directory containing video files mounted read-only into `/media/library`. |
| `FFMPEG_COMMAND` | No | `ffmpeg` | Path or command name for the FFmpeg binary used for subtitle transcoding. |
| `FFPROBE_COMMAND` | No | `ffprobe` | Path or command name for the FFprobe binary used for stream discovery. |
| `LIBRARY_PUBLIC_PREVIEW`| No | `false` | When set to `true`, permits browsing and playing the local library without signing in. |
| `SECURITY_REMEMBER_ME_VALIDITY_SECONDS` | No | `2592000` | Remember-me cookie lifetime in seconds (default: 30 days). |

---

## 3. Production Deployment via Docker Compose

Docker Compose is the recommended production deployment method. It packages the Spring Boot application (with built-in FFmpeg/FFprobe) and PostgreSQL 17 in isolated containers.

### 3.1 Initial Setup
1. Navigate to the project root:
   ```bash
   cd ~/Documents/java-web
   ```
2. Copy the sample environment file to `.env`:
   ```bash
   cp movie-watchlist.env.example .env
   ```
3. Edit `.env` and configure your credentials:
   ```bash
   nano .env
   ```
   Set at least:
   ```env
   DATABASE_PASSWORD=your_secure_password_here
   TMDB_API_KEY=your_tmdb_api_key
   MEDIA_HOST_ROOT="/path/to/your/movies_and_shows"
   ```

### 3.2 Launching the Stack
Build the image and launch the containers in detached mode:
```bash
docker compose up --build -d
```

### 3.3 Verifying Deployment
Check container health:
```bash
docker compose ps
```
Both `database` and `app` should report `healthy` or `running`.

Access the application in your browser:
```
http://localhost:8080
```

To view live application logs:
```bash
docker compose logs -f app
```

---

## 4. Direct Host Deployment (Standalone JVM)

If you prefer to run the Spring Boot application directly on your host machine without Docker:

### 4.1 Install System Tools
On Debian / Ubuntu:
```bash
sudo apt update
sudo apt install -y openjdk-21-jdk ffmpeg postgresql
```

### 4.2 Setup Local PostgreSQL Database
```bash
sudo -u postgres psql -c "CREATE USER moviewatchlist WITH PASSWORD 'local_secret_pass';"
sudo -u postgres psql -c "CREATE DATABASE movie_watchlist OWNER moviewatchlist;"
```

### 4.3 Build & Run
Export environment variables and execute using the Maven wrapper:
```bash
export DATABASE_URL="jdbc:postgresql://localhost:5432/movie_watchlist"
export DATABASE_USERNAME="moviewatchlist"
export DATABASE_PASSWORD="local_secret_pass"
export TMDB_API_KEY="your_tmdb_api_key"
export MEDIA_ROOTS="$HOME/Videos,/media/external_drive"

./mvnw spring-boot:run
```

---

## 5. Spring Profiles Reference

The application provides specialized execution profiles tailored for different scenarios:

### 5.1 `default` Profile
- **Storage:** PostgreSQL 16+ with Flyway migrations.
- **Security:** Full authentication enforced. Unauthenticated users can only view discovery and search.
- **Target:** Production server, Docker Compose.

### 5.2 `preview` Profile
- **Command:**
  ```bash
  java -Dspring.profiles.active=preview -jar target/movie-watchlist-web-0.1.0-SNAPSHOT.jar --server.port=8081
  ```
- **Port:** Defaults to `8081`.
- **Behavior:** `library.public-preview=true`. Allows immediate browsing and cinema video playback of local media without requiring user registration or login.
- **Target:** Home media servers, local kiosks, family LAN streaming.

### 5.3 `portable` Profile
- **Command:**
  ```bash
  ./mvnw -Pportable package
  java -Dspring.profiles.active=portable -jar target/movie-watchlist-web-0.1.0-SNAPSHOT.jar
  ```
- **Storage:** Embedded file-based H2 database initialized via `db/portable-schema.sql`. Zero external PostgreSQL server needed.
- **Target:** Offline laptops, USB stick installations, standalone portable media players.

---

## 6. Linux Desktop Launcher Integration

To install the native application shortcut into your desktop environment (GNOME, KDE, XFCE):

1. Execute the launcher installation script:
   ```bash
   bash scripts/install-desktop-launcher.sh
   ```
2. Search for **"Movie Watchlist"** in your application launcher menu.
3. Clicking the icon automatically starts the background Docker container stack and opens `http://localhost:8080` in your default browser.

---

## 7. Troubleshooting & Operational Runbook

### Issue 1: "TMDB is unavailable right now"
- **Cause:** Missing or invalid `TMDB_API_KEY`, or rate limit reached on TMDB endpoints.
- **Resolution:** Verify your key at `https://www.themoviedb.org/settings/api`. Test with `curl`:
  ```bash
  curl "https://api.themoviedb.org/3/movie/popular?api_key=YOUR_KEY"
  ```
  Local media playback and cached watchlists continue to operate even when TMDB is unavailable.

### Issue 2: Subtitle Tracks Missing or Extraction Error
- **Cause:** FFmpeg or FFprobe binary is missing or not executable by the application process.
- **Resolution:**
  - In Docker: The Dockerfile bundles FFmpeg automatically. Ensure container build completed without errors.
  - On Host: Run `which ffmpeg` and `which ffprobe`. Ensure `FFMPEG_COMMAND` points to the valid executable path.
  - Bitmap Subtitles: Note that DVD VOBSUB and Blu-ray PGS subtitles cannot be converted to text WebVTT and will show as unsupported. Use external `.srt` sidecars instead.

### Issue 3: Video Player Shows Black Screen or "Codec Not Supported"
- **Cause:** The media file container contains video streams encoded in legacy codecs (e.g., MPEG-2, DivX, XviD) not natively decoded by the client's web browser.
- **Resolution:** Ensure video files are encoded in standard H.264 (AVC) with AAC/MP3 audio. For MKV files with H.264 video, Chromium and Firefox will stream without issue.

### Issue 4: Database Migration Failure (Flyway Exception)
- **Cause:** Schema modified manually or conflicting checksums.
- **Resolution:**
  - Inspect Flyway schema table: `SELECT * FROM flyway_schema_history;`
  - In development environments, reset the Docker volume:
    ```bash
    docker compose down -v
    docker compose up --build -d
    ```

---

*End of Deployment, Operations & Environment Guide.*
