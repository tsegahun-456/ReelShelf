#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ROOT="${XDG_STATE_HOME:-${HOME}/.local/state}/movie-watchlist"
LOG_FILE="${STATE_ROOT}/launcher.log"
mkdir -p "$STATE_ROOT"
exec >>"$LOG_FILE" 2>&1

notify_user() {
  local title="$1"
  local message="$2"
  local urgency="${3:-normal}"
  if command -v notify-send >/dev/null 2>&1; then
    notify-send --urgency="$urgency" "$title" "$message" || true
  elif command -v zenity >/dev/null 2>&1; then
    zenity --info --title="$title" --text="$message" 2>/dev/null || true
  else
    printf '%s: %s\n' "$title" "$message" >&2
  fi
}

fail() {
  notify_user "Movie Watchlist could not start" "$1. Details: $LOG_FILE" critical
  exit 1
}

cd "$APP_DIR"

if ! command -v docker >/dev/null 2>&1; then
  fail "Docker is not installed. Install Docker Desktop or Docker Engine, then try again"
fi

if ! docker info >/dev/null 2>&1; then
  fail "Docker is not running or this account cannot access it. Start Docker, then try again"
fi

if [[ ! -f .env ]]; then
  if [[ -f .env.example ]]; then
    cp .env.example .env
    fail "A local .env file was created from .env.example. Set DATABASE_PASSWORD and MEDIA_HOST_ROOT in $APP_DIR/.env, then click the icon again"
  fi
  fail "The local .env file is missing"
fi

APP_PORT="$(sed -n 's/^APP_PORT=//p' .env | head -n 1 | tr -d "'\" \r")"
APP_PORT="${APP_PORT:-8080}"
if [[ ! "$APP_PORT" =~ ^[0-9]{1,5}$ ]] || (( APP_PORT < 1 || APP_PORT > 65535 )); then
  fail "APP_PORT in .env must be a valid port number"
fi

APP_URL="http://127.0.0.1:${APP_PORT}"
printf 'Starting Movie Watchlist at %s\n' "$APP_URL"

# Reuse the installed image for fast launches. A fresh install builds the app
# automatically because Compose has a Dockerfile-backed build configuration.
if docker compose images -q app 2>/dev/null | grep -q .; then
  docker compose up -d
else
  docker compose up -d --build
fi

for attempt in {1..180}; do
  if curl --silent --show-error --fail "$APP_URL/actuator/health" >/dev/null 2>&1; then
    if command -v xdg-open >/dev/null 2>&1; then
      xdg-open "$APP_URL" >/dev/null 2>&1 || fail "The app started, but the browser could not be opened"
    else
      fail "The app started at $APP_URL, but xdg-open is unavailable. Open that address in a browser"
    fi
    exit 0
  fi
  sleep 1
done

fail "The app did not become ready within 180 seconds"
