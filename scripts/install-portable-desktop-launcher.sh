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
UPDATER_LAUNCHER="$APPLICATIONS_DIR/reelshelf-update.desktop"
mkdir -p "$APPLICATIONS_DIR"

{
  printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=ReelShelf'
  printf 'Comment=%s\n' 'Browse and play movies stored on this computer'
  printf 'Exec="%s/scripts/launch-movie-watchlist-portable.sh"\n' "$BUNDLE_DIR"
  printf '%s\n' 'Icon=video-x-generic' 'Terminal=false' 'StartupNotify=true' 'Categories=AudioVideo;Video;'
} > "$LAUNCHER"
{
  printf '%s\n' '[Desktop Entry]' 'Type=Application' 'Name=Update ReelShelf'
  printf 'Comment=%s\n' 'Download, pause, resume, and install ReelShelf updates'
  printf 'Exec="%s/scripts/update-reelshelf-portable.sh"\n' "$BUNDLE_DIR"
  printf '%s\n' 'Icon=system-software-update' 'Terminal=false' 'StartupNotify=true' 'Categories=System;'
} > "$UPDATER_LAUNCHER"
chmod 0755 "$LAUNCHER" "$UPDATER_LAUNCHER" "$BUNDLE_DIR/scripts/launch-movie-watchlist-portable.sh" \
  "$BUNDLE_DIR/scripts/stop-movie-watchlist-portable.sh" "$BUNDLE_DIR/scripts/update-reelshelf-portable.sh"

if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database "$APPLICATIONS_DIR" >/dev/null 2>&1 || true
fi

printf 'Installed app-menu launchers:\n  %s\n  %s\n' "$LAUNCHER" "$UPDATER_LAUNCHER"
