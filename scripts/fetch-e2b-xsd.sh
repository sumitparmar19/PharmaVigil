#!/usr/bin/env bash
# Downloads the ICH E2B(R3) ICSR schema set (HL7 v3 multicacheschemas + coreschemas) into a local
# directory so E2bValidator can check exported files offline. The schemas are not committed to git
# (docs/E2B_R3.md). Source: the copy the European Medicines Agency publishes for EudraVigilance at the
# schemaLocation used in ICSR files. Starting from the batch entry schema, the script follows every
# xs:include / xs:import schemaLocation and refuses any path that would leave the schema root.
#
# Usage: scripts/fetch-e2b-xsd.sh <target-dir>   then   export PV_E2B_XSD_DIR=<target-dir>
set -euo pipefail

BASE="${E2B_XSD_BASE_URL:-https://eudravigilance.ema.europa.eu/XSD}"
ENTRY="multicacheschemas/MCCI_IN200100UV01.xsd"
TARGET="${1:?usage: fetch-e2b-xsd.sh <target-dir>}"
# Download into a scratch directory and move it into place only when the whole set arrived, so a failed
# run never leaves a half schema set that would look installed.
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

queue=("$ENTRY")
declare -A seen=()
count=0
while ((${#queue[@]})); do
  rel="${queue[0]}"
  queue=("${queue[@]:1}")
  [[ -n "${seen[$rel]:-}" ]] && continue
  seen[$rel]=1
  case "$rel" in
    /* | *..* | *[!A-Za-z0-9._/-]*) echo "refusing schema path: $rel" >&2; exit 1 ;;
  esac
  mkdir -p "$OUT/$(dirname "$rel")"
  curl -fsSL --retry 3 --retry-delay 2 -o "$OUT/$rel" "$BASE/$rel"
  count=$((count + 1))
  dir="$(dirname "$rel")"
  while IFS= read -r loc; do
    [[ -z "$loc" ]] && continue
    # Normalise "<dir>/../coreschemas/x.xsd" to "coreschemas/x.xsd" without touching the filesystem.
    target="$(python3 -c 'import posixpath,sys; print(posixpath.normpath(posixpath.join(sys.argv[1], sys.argv[2])))' "$dir" "$loc")"
    queue+=("$target")
  done < <(grep -oE '<xs:(include|import)[^>]*schemaLocation="[^"]+"' "$OUT/$rel" \
             | sed -E 's/.*schemaLocation="([^"]+)".*/\1/' || true)
done
# mktemp -d is mode 0700; the services run as a non-root user and must be able to read the (public) schemas.
chmod -R a+rX "$OUT"
rm -rf "$TARGET"
mkdir -p "$(dirname "$TARGET")"
mv "$OUT" "$TARGET"
trap - EXIT
echo "fetched $count schema files from $BASE into $TARGET"
