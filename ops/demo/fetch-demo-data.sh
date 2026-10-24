#!/usr/bin/env bash
# Puts the live demo's data in place on the server (ops/demo/data/, git-ignored):
#   - the demo-data archive from the GitHub release named in demo-data.lock (trained model bundle, FAERS signal
#     export, replay sample), checked against the SHA-256 recorded in that file before anything is unpacked;
#   - the official ICH E2B(R3) schema set, downloaded from EMA's published copy by scripts/fetch-e2b-xsd.sh.
# Safe to run again: it skips what is already there and verified.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck disable=SC1091
source demo-data.lock   # DEMO_DATA_URL, DEMO_DATA_SHA256
mkdir -p data
if [ ! -f data/.sha256 ] || [ "$(cat data/.sha256)" != "$DEMO_DATA_SHA256" ]; then
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' EXIT
  echo "downloading $DEMO_DATA_URL"
  curl -fL --retry 4 --retry-delay 5 -o "$tmp" "$DEMO_DATA_URL"
  echo "$DEMO_DATA_SHA256  $tmp" | sha256sum -c -
  rm -rf data/models data/signals data/replay
  tar -xzf "$tmp" -C data --no-same-owner models signals replay
  echo "$DEMO_DATA_SHA256" > data/.sha256
fi
if [ ! -f data/xsd/multicacheschemas/MCCI_IN200100UV01.xsd ]; then
  ../../scripts/fetch-e2b-xsd.sh data/xsd
fi
echo "demo data ready: $(ls data)"
