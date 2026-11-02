#!/usr/bin/env bash
# ReelShelf in-place updater. It updates the app code without touching the local
# database, credentials, media folders, or bundled Java runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_DIR="$HOME/Documents/Codex/Movie-Watchlist-Linux-Installed/app-image/lib/app"
STATE_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/movie-watchlist"
PID_FILE="$STATE_ROOT/portable.pid"
CONFIG_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/movie-watchlist/movie-watchlist.env"
PORT=8080
if [[ -r "$CONFIG_FILE" ]]; then
  configured_port="$(sed -n 's/^[[:space:]]*PORT[[:space:]]*=[[:space:]]*//p' "$CONFIG_FILE" | tail -n 1 | tr -d "\"'\r")"
  if [[ "$configured_port" =~ ^[0-9]{1,5}$ ]]; then PORT="$configured_port"; fi
fi

if [[ -r "$PID_FILE" ]]; then
  APP_PID="$(cat "$PID_FILE")"
  if [[ "$APP_PID" =~ ^[0-9]+$ ]] && kill -0 "$APP_PID" 2>/dev/null; then
    printf 'ReelShelf is running. Stop it with the installed scripts/stop-movie-watchlist-portable.sh, then run this updater again.\n' >&2
    exit 1
  fi
fi
if command -v curl >/dev/null 2>&1 && curl --silent --fail --max-time 1 "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
  printf 'ReelShelf is running on port %s. Stop it, then run this updater again.\n' "$PORT" >&2
  exit 1
fi

printf 'ReelShelf self-update\n'
printf '1/4 Pulling the latest code (fast-forward only)...\n'
git pull --ff-only

printf '2/4 Building the updated jar...\n'
./mvnw -o -Dmaven.repo.local="$ROOT/.maven-cache/repository" -DskipTests package

APP_VERSION="$(sed -n '/<\/parent>/,$ { /<version>/{ s/.*<version>\([^<]*\)<\/version>.*/\1/p; q; } }' "$ROOT/pom.xml")"
JAR="$ROOT/target/movie-watchlist-web-${APP_VERSION}.jar"
mapfile -t INSTALLED_JARS < <(find "$APP_DIR" -maxdepth 1 -type f -name 'movie-watchlist-web*.jar' ! -name '*.original' -print 2>/dev/null || true)
APP_JAR="${INSTALLED_JARS[0]:-}"

printf '3/4 Updating the installed Linux app...\n'
if [[ -n "$APP_JAR" && -f "$APP_JAR" ]]; then
  BACKUP="$APP_JAR.bak-$(date +%Y%m%d-%H%M%S)"
  TEMP_JAR="$APP_JAR.new"
  cp "$APP_JAR" "$BACKUP"
  cp "$JAR" "$TEMP_JAR"
  chmod --reference="$APP_JAR" "$TEMP_JAR" 2>/dev/null || true
  mv -f "$TEMP_JAR" "$APP_JAR"
  printf '      Installed jar replaced (backup kept at %s).\n' "$BACKUP"
else
  printf '      No installed ReelShelf JAR was found in %s — skipped.\n' "$APP_DIR"
fi

printf '4/4 Done. Restart ReelShelf to load the update.\n'
