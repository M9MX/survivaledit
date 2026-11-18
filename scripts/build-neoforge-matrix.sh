#!/usr/bin/env bash
set -euo pipefail

matrix="${1:-gradle/neoforge-versions.txt}"

if [[ ! -f "$matrix" ]]; then
    echo "Matrix file not found: $matrix" >&2
    exit 1
fi

mkdir -p build/neoforge-matrix

while read -r minecraft neo parchment parchment_mappings extra; do
    if [[ -z "${minecraft:-}" || "${minecraft}" == "#"* ]]; then
        continue
    fi

    echo "Building Minecraft $minecraft with NeoForge $neo"
    ./gradlew --no-daemon \
        -PneoforgeMinecraft="$minecraft" \
        -PneoforgeVersion="$neo" \
        -PparchmentMinecraft="$parchment" \
        -PparchmentMappings="$parchment_mappings" \
        :worldedit-neoforge:assemble

    cp worldedit-neoforge/build/libs/survivaledit-neoforge-*dist.jar \
        "build/neoforge-matrix/survivaledit-neoforge-mc${minecraft}-neo${neo}.jar"
done < "$matrix"
