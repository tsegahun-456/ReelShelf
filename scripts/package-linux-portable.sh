#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="$ROOT/target/portable-linux"
INPUT_DIR="$BUILD_ROOT/input"
IMAGE_DIR="$BUILD_ROOT/image"
BUNDLE_NAME="Movie-Watchlist-Linux-Portable"
BUNDLE_DIR="$BUILD_ROOT/$BUNDLE_NAME"
ARCHIVE="$BUILD_ROOT/$BUNDLE_NAME.tar.gz"
APP_VERSION="0.2.1"
APP_JAR="movie-watchlist-web-${APP_VERSION}.jar"
APP_IMAGE_JAR="movie-watchlist-web.jar"

if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
  printf 'Build this app image on Linux x86-64.\n' >&2
  exit 1
fi
if ! command -v jpackage >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
  printf 'A JDK 21 installation with jpackage is required to build the bundle.\n' >&2
  exit 1
fi

cd "$ROOT"
./mvnw -Dmaven.repo.local="$ROOT/.maven-cache/repository" -Pportable -DskipTests clean package
if [[ ! -f "$ROOT/target/$APP_JAR" ]]; then
  printf 'Maven did not produce the expected application JAR: %s\n' "$APP_JAR" >&2
  exit 1
fi

mkdir -p "$BUILD_ROOT"
rm -rf "$INPUT_DIR" "$IMAGE_DIR" "$BUNDLE_DIR"
rm -f "$ARCHIVE"
mkdir -p "$INPUT_DIR" "$IMAGE_DIR"
cp "$ROOT/target/$APP_JAR" "$INPUT_DIR/$APP_IMAGE_JAR"
jpackage \
  --type app-image \
  --name MovieWatchlist \
  --app-version "$APP_VERSION" \
  --description 'Local movie and TV library' \
  --input "$INPUT_DIR" \
  --main-jar "$APP_IMAGE_JAR" \
  --dest "$IMAGE_DIR" \
  --jlink-options '--strip-debug --no-man-pages --no-header-files' \
  --java-options '-Dspring.profiles.active=portable' \
  --java-options '-Dfile.encoding=UTF-8'

mkdir -p "$BUNDLE_DIR/scripts" "$BUNDLE_DIR/docs"
mv "$IMAGE_DIR/MovieWatchlist" "$BUNDLE_DIR/app-image"
cp "$ROOT/movie-watchlist.env.example" "$BUNDLE_DIR/"
cp "$ROOT/scripts/launch-movie-watchlist-portable.sh" \
   "$ROOT/scripts/stop-movie-watchlist-portable.sh" \
   "$ROOT/scripts/install-portable-desktop-launcher.sh" "$BUNDLE_DIR/scripts/"
cp "$ROOT/docs/PORTABLE_LINUX.md" "$BUNDLE_DIR/docs/README.md"
chmod 0755 "$BUNDLE_DIR/scripts/"*.sh
tar -C "$BUILD_ROOT" -czf "$ARCHIVE" "$BUNDLE_NAME"
(cd "$BUILD_ROOT" && sha256sum "$BUNDLE_NAME.tar.gz" > "$BUNDLE_NAME.tar.gz.sha256")

printf '\nStandalone Linux app ready:\n  %s\n  %s\n  %s\n' "$BUNDLE_DIR" "$ARCHIVE" "$ARCHIVE.sha256"
