# Linux portable bundle (no Docker)

This Linux x86-64 package runs the Movie Watchlist Spring app with its own bundled Java runtime and an H2 file database. It does not need Docker, PostgreSQL, Maven, or a separately installed Java runtime. The existing Docker/PostgreSQL scripts continue to work for the development setup.

## Build the bundle

On Linux x86-64 with JDK 21 and internet access for Maven dependencies:

```bash
./scripts/package-linux-portable.sh
```

The script writes a ready-to-extract folder and `.tar.gz` archive under `target/portable-linux/`.

## Install and start it

1. Extract `Movie-Watchlist-Linux-Portable.tar.gz` to a folder you can keep, such as `~/Applications/Movie-Watchlist`.
2. In a terminal, enter that folder and run `./scripts/install-portable-desktop-launcher.sh` once.
3. Search for **Movie Watchlist** in the desktop app menu. The first launch opens the app at `http://127.0.0.1:8080`.
4. To stop it, run `./scripts/stop-movie-watchlist-portable.sh` from the extracted folder.

On first launch the app creates `~/.config/movie-watchlist/movie-watchlist.env`. The `MEDIA_ROOTS` value there seeds the scan list the very first time the app starts. After that, use the in-app **Settings** page to add or remove folders: it can auto-detect `Videos`, `Downloads`, and mounted drives, offers *Scan everything on this PC*, and has a *Scan now* button — no restart or config edit needed. The database and logs stay under `~/.local/share/movie-watchlist/` and `~/.local/state/movie-watchlist/`, so removing/replacing the app bundle does not remove that data.

Tick **Keep me signed in** on the sign-in page to remain signed in across restarts (the sign-in key is stored encrypted on that PC only); **Sign out** removes it.

Set `TMDB_API_KEY`, `FIREBASE_WEB_API_KEY`, and `FIREBASE_PROJECT_ID` in that config file if the friend needs online catalog lookup and Firebase sign-in/sync. These settings are optional for launching and using the local library. A restart applies config changes.

Embedded subtitle tracks need `ffmpeg` and `ffprobe` available on the Linux computer. External `.srt` and `.vtt` sidecar subtitles continue to work without them. TMDB search and Firebase sync need internet; local video playback and the local database do not.

## Important

* The archive is built for Linux x86-64. It includes its Java runtime but not media files, TMDB credentials, Firebase credentials, or FFmpeg.
* Each computer scans and plays only paths configured on that computer. The video files are not copied into the app bundle.
* The portable H2 database starts empty. It is separate from any existing PostgreSQL database and does not alter or migrate that database.
