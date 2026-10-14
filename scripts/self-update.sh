#!/usr/bin/env bash
# ReelShelf in-place updater.
# Updates the code only: pulls the latest source, rebuilds the jar, and swaps it
# into the installed Linux app. The database, credentials, media folders, and the
# bundled Java runtime are never touched. Flyway migrations run automatically on
# the next startup.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "ReelShelf self-update"
echo "1/4 Pulling the latest code (fast-forward only)..."
git pull --ff-only

echo "2/4 Building the updated jar..."
./mvnw -o -DskipTests package

JAR="$ROOT/target/movie-watchlist-web-0.1.0-SNAPSHOT.jar"
APP_JAR="$HOME/Documents/Codex/Movie-Watchlist-Linux-Installed/app-image/lib/app/movie-watchlist-web-0.1.0-SNAPSHOT.jar"

echo "3/4 Updating the installed Linux app..."
if [ -f "$APP_JAR" ]; then
  BACKUP="$APP_JAR.bak-$(date +%Y%m%d-%H%M%S)"
  cp "$APP_JAR" "$BACKUP"
  cp "$JAR" "$APP_JAR"
  echo "      Installed jar replaced (backup kept at $BACKUP)."
else
  echo "      No installed app found at $APP_JAR — skipped."
fi

echo "4/4 Done. Restart ReelShelf to load the update."
