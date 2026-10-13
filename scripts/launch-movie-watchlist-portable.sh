#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$SCRIPT_DIR/app-image" ]]; then
  BUNDLE_DIR="$SCRIPT_DIR"
else
  BUNDLE_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
fi
APP_IMAGE="$BUNDLE_DIR/app-image"
JAVA="$APP_IMAGE/lib/runtime/bin/java"
STATE_ROOT="${XDG_STATE_HOME:-${HOME}/.local/state}/movie-watchlist"
DATA_ROOT="${XDG_DATA_HOME:-${HOME}/.local/share}/movie-watchlist"
CONFIG_ROOT="${XDG_CONFIG_HOME:-${HOME}/.config}/movie-watchlist"
CONFIG_FILE="$CONFIG_ROOT/movie-watchlist.env"
PID_FILE="$STATE_ROOT/portable.pid"
LOG_FILE="$STATE_ROOT/portable.log"

notify_user() {
  if command -v notify-send >/dev/null 2>&1; then
    notify-send "Movie Watchlist" "$1" || true
  elif command -v zenity >/dev/null 2>&1; then
    zenity --info --title="Movie Watchlist" --text="$1" 2>/dev/null || true
  else
    printf 'Movie Watchlist: %s\n' "$1" >&2
  fi
}

if [[ ! -x "$JAVA" ]]; then
  notify_user "The bundled Java runtime is missing. Re-extract the portable app folder."
  exit 1
fi

if [[ ! -f "$CONFIG_FILE" ]]; then
  mkdir -p "$CONFIG_ROOT"
  cp "$BUNDLE_DIR/movie-watchlist.env.example" "$CONFIG_FILE"
fi

# This is the user's own local config file, created from the bundled example.
set -a
# shellcheck disable=SC1090
source "$CONFIG_FILE"
set +a

PORT="${PORT:-8080}"
if [[ ! "$PORT" =~ ^[0-9]{1,5}$ ]] || (( PORT < 1 || PORT > 65535 )); then
  notify_user "PORT in $CONFIG_FILE must be a number from 1 to 65535."
  exit 1
fi

export MOVIE_WATCHLIST_DB="${MOVIE_WATCHLIST_DB:-$DATA_ROOT/database/movie-watchlist}"
mkdir -p "$(dirname -- "$MOVIE_WATCHLIST_DB")" "$STATE_ROOT"
APP_URL="http://127.0.0.1:$PORT"

is_listening() {
  if command -v curl >/dev/null 2>&1; then
    curl --silent --show-error --fail --max-time 1 "$APP_URL/actuator/health" >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1
  fi
}

open_app() {
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$APP_URL" >/dev/null 2>&1 || true
  else
    notify_user "The app is running at $APP_URL. Open that address in your browser."
  fi
}

if is_listening; then
  open_app
  exit 0
fi

mapfile -t APP_JARS < <(find "$APP_IMAGE/lib/app" -maxdepth 1 -type f -name 'movie-watchlist-web-*.jar' ! -name '*.original' -print)
if (( ${#APP_JARS[@]} != 1 )); then
  notify_user "The app JAR is missing from the bundle. Re-extract the portable app folder."
  exit 1
fi

nohup "$JAVA" -Dspring.profiles.active=portable -Dfile.encoding=UTF-8 \
  -jar "${APP_JARS[0]}" "--server.port=$PORT" >>"$LOG_FILE" 2>&1 </dev/null &
APP_PID=$!
printf '%s\n' "$APP_PID" > "$PID_FILE"

for _ in {1..90}; do
  if is_listening; then
    open_app
    exit 0
  fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    rm -f "$PID_FILE"
    notify_user "The app stopped during startup. Check $LOG_FILE for details."
    exit 1
  fi
  sleep 1
done

notify_user "The app is taking too long to start. Check $LOG_FILE for details."
exit 1
