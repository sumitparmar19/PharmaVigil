#!/usr/bin/env bash
# Prints JaCoCo line coverage per service module from target/site/jacoco/jacoco.csv (run in services/).
set -euo pipefail
for csv in */target/site/jacoco/jacoco.csv; do
  awk -F, -v m="${csv%%/*}" 'NR > 1 { missed += $8; covered += $9 }
    END { if (missed + covered > 0) printf "%-20s line coverage %5.1f%% (%d/%d)\n", m, 100 * covered / (missed + covered), covered, missed + covered }' "$csv"
done
