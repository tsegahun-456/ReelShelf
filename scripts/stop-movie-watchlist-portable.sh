#!/usr/bin/env bash
set -Eeuo pipefail

STATE_ROOT="${XDG_STATE_HOME:-${HOME}/.local/state}/movie-watchlist"
PID_FILE="$STATE_ROOT/portable.pid"

if [[ ! -f "$PID_FILE" ]]; then
  printf 'Movie Watchlist is not running (no saved process id).\n'
  exit 0
fi

APP_PID="$(cat "$PID_FILE")"
if [[ ! "$APP_PID" =~ ^[0-9]+$ ]]; then
  rm -f "$PID_FILE"
  printf 'Removed an invalid saved process id.\n'
  exit 0
fi

if kill -0 "$APP_PID" 2>/dev/null; then
  kill "$APP_PID"
  for _ in {1..20}; do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      rm -f "$PID_FILE"
      printf 'Movie Watchlist stopped.\n'
      exit 0
    fi
    sleep 1
  done
  kill -KILL "$APP_PID" 2>/dev/null || true
fi
rm -f "$PID_FILE"
printf 'Movie Watchlist stopped.\n'
