#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="${APP_DIR//\\/\\\\}"
APP_DIR="${APP_DIR//\"/\\\"}"
DATA_ROOT="${XDG_DATA_HOME:-${HOME}/.local/share}"
APPLICATIONS_DIR="$DATA_ROOT/applications"
LAUNCHER="$APPLICATIONS_DIR/movie-watchlist.desktop"
ICON_PATH="$APP_DIR/assets/movie-watchlist.svg"

mkdir -p "$APPLICATIONS_DIR"
{
  printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Movie Watchlist'
  printf 'Comment=%s\n' 'Browse your catalog and play movies stored on this PC'
  printf 'Exec="%s/scripts/launch-movie-watchlist.sh"\n' "$APP_DIR"
  printf 'Icon=%s\n' "$ICON_PATH"
  printf '%s\n' 'Terminal=false' 'StartupNotify=true' 'Categories=AudioVideo;Video;'
} > "$LAUNCHER"
chmod 0755 "$LAUNCHER" "$APP_DIR/scripts/launch-movie-watchlist.sh" "$APP_DIR/scripts/rebuild-movie-watchlist.sh"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPLICATIONS_DIR" >/dev/null 2>&1 || true
fi

if command -v notify-send >/dev/null 2>&1; then
  notify-send "Movie Watchlist" "Launcher installed. Search for Movie Watchlist in your app menu."
else
  printf 'Launcher installed at %s\n' "$LAUNCHER"
fi
