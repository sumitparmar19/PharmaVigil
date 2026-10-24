#!/usr/bin/env bash
# Deploys one image tag on the demo server (called by the "Deploy demo" workflow over SSH, or by hand):
# updates the checkout to origin/main, refreshes the demo data, pulls the images for the tag, restarts the stack and
# checks https://<host>/api/status. If the check fails, it goes back to the previous tag and says so (exit 1).
set -euo pipefail
tag="${1:?usage: deploy.sh <image tag>}"
[[ "$tag" =~ ^[a-z0-9._-]{1,64}$ ]] || { echo "bad tag" >&2; exit 2; }
cd "$(dirname "$0")/../.."
git fetch -q origin main && git checkout -q --detach origin/main
ops/demo/make-env.sh >/dev/null
ops/demo/fetch-demo-data.sh
env_file=ops/demo/.env
previous="$(grep '^PV_IMAGE_TAG=' "$env_file" | cut -d= -f2)"
host="$(grep '^PV_DEMO_HOST=' "$env_file" | cut -d= -f2)"
compose() { docker compose -f docker-compose.demo.yml --env-file "$env_file" "$@"; }
set_tag() { sed -i "s/^PV_IMAGE_TAG=.*/PV_IMAGE_TAG=$1/" "$env_file"; }
healthy() {
  for _ in $(seq 1 60); do
    if curl -fsS -m 10 "https://$host/api/status" | grep -q '"status":"UP"'; then return 0; fi
    sleep 10
  done
  return 1
}
set_tag "$tag"
if compose pull -q && compose up -d --wait --wait-timeout 1200 && healthy; then
  echo "deployed $tag (previous $previous)"
  docker image prune -f >/dev/null
  exit 0
fi
echo "deploy of $tag failed its health check; rolling back to $previous" >&2
set_tag "$previous"
compose up -d --wait --wait-timeout 1200 || true
exit 1
