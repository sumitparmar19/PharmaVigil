#!/usr/bin/env bash
# Builds the live demo's data archive (pharmavigil-demo-data.tar.gz) from the Phase 2 runs, on the build machine:
#   models/   the trained case-service bundle written by `pv-ml p4` (bundle.json lists every file's SHA-256)
#   signals/  the FAERS signal export written by `pv-ml export-signals` (signals.csv, signal_trend.csv, meta)
#   replay/   the demo slice written by `pv-data demo-slice` (replay_reports.jsonl + replay_meta.json)
# Prints the archive's SHA-256, which goes into ops/demo/demo-data.lock; the archive itself is uploaded as the
# release asset named there (OWNER_STEPS.md). No MedDRA dictionary file is included: the coder's label list is the
# preferred-term strings FDA publishes in FAERS.
#
#   ops/demo/build-demo-data.sh <models dir> <signals dir> <replay dir> <out.tar.gz>
set -euo pipefail
models="${1:?models dir}"; signals="${2:?signals dir}"; replay="${3:?replay dir}"; out="${4:?output archive}"
for f in "$models/bundle.json" "$models/coder.onnx" "$signals/signals.csv" "$replay/replay_reports.jsonl"; do
  [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
done
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$stage/models" "$stage/signals" "$stage/replay"
cp -r "$models"/. "$stage/models/"
cp "$signals"/signals.csv "$signals"/signal_trend.csv "$signals"/signals_meta.json "$stage/signals/"
cp "$replay"/replay_reports.jsonl "$replay"/replay_meta.json "$stage/replay/"
# Fixed owner, mode and time stamps so the same inputs give the same archive and SHA-256.
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-01-01 00:00Z' -C "$stage" -cf - models signals replay \
  | gzip -n -9 > "$out"
sha256sum "$out" | cut -d' ' -f1
