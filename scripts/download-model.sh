#!/usr/bin/env bash
# Downloads and extracts a Vosk model into ./models (default: small US English).
set -euo pipefail

MODEL="${1:-vosk-model-small-en-us-0.15}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/models"

if [[ -d "$DEST/$MODEL" ]]; then
    echo "Model already present: $DEST/$MODEL"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading $MODEL ..."
curl -fL --progress-bar -o "$TMP/$MODEL.zip" \
    "https://alphacephei.com/vosk/models/$MODEL.zip"

echo "Extracting to $DEST ..."
mkdir -p "$DEST"
unzip -q "$TMP/$MODEL.zip" -d "$DEST"

echo "Done: $DEST/$MODEL"
