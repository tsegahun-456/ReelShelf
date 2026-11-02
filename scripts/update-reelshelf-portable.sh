#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -d "$SCRIPT_DIR/app-image" ]]; then
  BUNDLE_DIR="$SCRIPT_DIR"
else
  BUNDLE_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
fi
if [[ -x "$BUNDLE_DIR/updater-runtime/bin/java" ]]; then
  JAVA="$BUNDLE_DIR/updater-runtime/bin/java"
else
  JAVA="$BUNDLE_DIR/app-image/lib/runtime/bin/java"
fi
UPDATER="$BUNDLE_DIR/updater/reelshelf-updater.jar"
CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/reelshelf/updates"

if [[ ! -x "$JAVA" || ! -f "$UPDATER" ]]; then
  if command -v zenity >/dev/null 2>&1; then
    zenity --error --title="ReelShelf updater" --text="The bundled updater or Java runtime is missing. Re-extract the full portable package." 2>/dev/null || true
  else
    notify-send --app-name="ReelShelf" "ReelShelf updater" "The bundled updater or Java runtime is missing." 2>/dev/null || true
  fi
  exit 1
fi

mkdir -p "$CACHE_ROOT"
RUN_COPY="$CACHE_ROOT/updater-run-$$.jar"
cp "$UPDATER" "$RUN_COPY"
cd "$CACHE_ROOT"
exec "$JAVA" -jar "$RUN_COPY" --bundle-dir "$BUNDLE_DIR" --platform linux
