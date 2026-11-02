#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/src/updater/java"
OUTPUT="$ROOT/target/reelshelf-updater"
CLASSES="$OUTPUT/classes"
JAR="$OUTPUT/reelshelf-updater.jar"

mkdir -p "$CLASSES"
javac --release 21 -d "$CLASSES" "$SOURCE/com/segi/watchlist/updater/ReelShelfUpdater.java"
jar --create --file "$JAR" --main-class com.segi.watchlist.updater.ReelShelfUpdater -C "$CLASSES" .
printf 'Updater helper ready: %s\n' "$JAR"
