#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_ROOT="${XDG_STATE_HOME:-${HOME}/.local/state}/movie-watchlist"
LOG_FILE="${STATE_ROOT}/launcher.log"
mkdir -p "$STATE_ROOT"
exec >>"$LOG_FILE" 2>&1

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  if command -v notify-send >/dev/null 2>&1; then
    notify-send --urgency=critical "Movie Watchlist update failed" "Start Docker and try again. Details: $LOG_FILE"
  fi
  exit 1
fi

cd "$APP_DIR"
if [[ ! -f .env ]]; then
  if command -v notify-send >/dev/null 2>&1; then
    notify-send --urgency=critical "Movie Watchlist update failed" "The .env file is missing. Details: $LOG_FILE"
  fi
  exit 1
fi

docker compose up -d --build
if command -v notify-send >/dev/null 2>&1; then
  notify-send "Movie Watchlist" "Update complete. Your local database volume was kept."
fi
