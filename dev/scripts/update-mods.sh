#!/usr/bin/env bash
# Distributes the built HuskHomes jar and shared mods (dev/mods/*.jar) to each
# server's own mods directory (dev/mods/<server>/).  Per-server directories
# prevent concurrent LuckPerms dependency-download conflicts on startup.
# Run this after every `./gradlew :fabric:build` and after adding/updating any
# shared mod jar in dev/mods/.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODS_DIR="$ROOT_DIR/dev/mods"
CONFIG_DIR="$ROOT_DIR/dev/config"

SERVERS=(ardacraft building plots lobby)

JAR=$(ls "$ROOT_DIR/target"/HuskHomes-Fabric-*.jar 2>/dev/null | head -1)

if [[ -z "$JAR" ]]; then
    echo "ERROR: No jar found in $ROOT_DIR/target/. Run './gradlew :fabric:build' first." >&2
    exit 1
fi

for server in "${SERVERS[@]}"; do
    server_mods="$MODS_DIR/$server"
    mkdir -p "$server_mods"

    # Built mod
    cp "$JAR" "$server_mods/huskhomes.jar"

    # Shared mods placed in dev/mods/*.jar (e.g. LuckPerms)
    for mod in "$MODS_DIR"/*.jar; do
        [[ -f "$mod" ]] && cp "$mod" "$server_mods/"
    done

    # Rootless Podman: container's minecraft user is a remapped subUID → "other".
    # 777 so containers can create subdirs (LuckPerms lib cache, etc.).
    chmod 777 "$server_mods"
done

# Config dirs need world-writable so HuskHomes can write/update config files.
chmod -R 777 "$CONFIG_DIR" 2>/dev/null

echo "Distributed $(basename "$JAR") to: ${SERVERS[*]}"
