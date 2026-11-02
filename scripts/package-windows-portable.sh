#!/usr/bin/env bash
# Builds the Windows portable zip from the current source, including a portable
# Temurin 21 JRE (jpackage cannot cross-build a Windows app-image from Linux,
# but the Temurin JRE zip runs straight from the bundle folder).
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ROOT="$ROOT/target/portable-windows"
BUNDLE_NAME="Movie-Watchlist-Windows-Portable"
BUNDLE_DIR="$BUILD_ROOT/$BUNDLE_NAME"
ARCHIVE="$BUILD_ROOT/$BUNDLE_NAME.zip"
APP_VERSION="$(sed -n '/<\/parent>/,$ { /<version>/{ s/.*<version>\([^<]*\)<\/version>.*/\1/p; q; } }' "$ROOT/pom.xml")"
APP_JAR="movie-watchlist-web-${APP_VERSION}.jar"
JRE_CACHE="$BUILD_ROOT/cache/windows-jre.zip"
JRE_URL="https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse"

cd "$ROOT"
./mvnw -B -Dmaven.repo.local="$ROOT/.maven-cache/repository" -Pportable -DskipTests package
[[ -f "$ROOT/target/$APP_JAR" ]] || { printf 'Maven did not produce %s\n' "$APP_JAR" >&2; exit 1; }

# Download (once) the portable Windows Java runtime.
if [[ ! -s "$JRE_CACHE" ]]; then
  mkdir -p "$(dirname -- "$JRE_CACHE")"
  printf 'Downloading the portable Windows Java runtime (Temurin 21 JRE)...\n'
  curl -fL --retry 3 -o "$JRE_CACHE" "$JRE_URL"
fi

rm -rf "$BUNDLE_DIR"
rm -f "$ARCHIVE" "$ARCHIVE.sha256"
mkdir -p "$BUNDLE_DIR" "$BUNDLE_DIR/updater"
cp "$ROOT/target/$APP_JAR" "$BUNDLE_DIR/movie-watchlist-web.jar"
"$ROOT/scripts/build-updater.sh"
cp "$ROOT/target/reelshelf-updater/reelshelf-updater.jar" "$BUNDLE_DIR/updater/"
cp "$ROOT/portable-windows/movie-watchlist.env.example" "$ROOT/portable-windows/README.txt" \
  "$ROOT/portable-windows/update-reelshelf.bat" "$BUNDLE_DIR/"
# cmd expects CRLF line endings in .bat files.
sed 's/$/\r/' "$ROOT/portable-windows/start-movie-watchlist.bat" > "$BUNDLE_DIR/start-movie-watchlist.bat"

# Unpack the JRE into runtime\ (strip the archive's single root folder).
mkdir -p "$BUNDLE_DIR/runtime"
unzip -q "$JRE_CACHE" -d "$BUILD_ROOT/runtime-tmp"
JRE_ROOT="$(find "$BUILD_ROOT/runtime-tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
shopt -s dotglob
mv "$JRE_ROOT"/* "$BUNDLE_DIR/runtime/"
shopt -u dotglob
rm -rf "$BUILD_ROOT/runtime-tmp"
[[ -f "$BUNDLE_DIR/runtime/bin/java.exe" ]] || { printf 'Windows java.exe missing from bundle\n' >&2; exit 1; }

if command -v zip >/dev/null 2>&1; then
  (cd "$BUILD_ROOT" && zip -qr "$ARCHIVE" "$BUNDLE_NAME")
else
  python3 - "$ARCHIVE" "$BUNDLE_DIR" <<'PY'
import os, sys, zipfile
archive, bundle = sys.argv[1], sys.argv[2]
root = os.path.dirname(bundle)
name = os.path.basename(bundle)
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zf:
    for base, _, files in os.walk(bundle):
        for f in files:
            full = os.path.join(base, f)
            zf.write(full, os.path.relpath(full, root))
PY
fi
(cd "$BUILD_ROOT" && sha256sum "$BUNDLE_NAME.zip" > "$BUNDLE_NAME.zip.sha256")

printf '\nWindows portable ready (jar + bundled Java runtime):\n  %s\n  %s\n  %s.sha256\n' "$BUNDLE_DIR" "$ARCHIVE" "$ARCHIVE"
