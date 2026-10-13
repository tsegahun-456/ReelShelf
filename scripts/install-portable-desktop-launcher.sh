#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$SCRIPT_DIR/app-image" ]]; then
  BUNDLE_DIR="$SCRIPT_DIR"
else
  BUNDLE_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
fi
APPLICATIONS_DIR="${XDG_DATA_HOME:-${HOME}/.local/share}/applications"
LAUNCHER="$APPLICATIONS_DIR/movie-watchlist-portable.desktop"
mkdir -p "$APPLICATIONS_DIR"

{
  printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Movie Watchlist'
  printf 'Comment=%s\n' 'Browse and play movies stored on this computer'
  printf 'Exec="%s/scripts/launch-movie-watchlist-portable.sh"\n' "$BUNDLE_DIR"
  printf '%s\n' 'Icon=video-x-generic' 'Terminal=false' 'StartupNotify=true' 'Categories=AudioVideo;Video;'
} > "$LAUNCHER"
chmod 0755 "$LAUNCHER" "$BUNDLE_DIR/scripts/launch-movie-watchlist-portable.sh" \
  "$BUNDLE_DIR/scripts/stop-movie-watchlist-portable.sh"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPLICATIONS_DIR" >/dev/null 2>&1 || true
fi

printf 'Installed app-menu launcher: %s\n' "$LAUNCHER"
