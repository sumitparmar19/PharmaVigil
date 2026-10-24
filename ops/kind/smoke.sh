#!/usr/bin/env bash
# Runs the Playwright smoke test (frontend/e2e) against the UI on the kind cluster (NodePort 30080 → 127.0.0.1:8080)
# with the demo password from the Secret file written by deploy.sh.
set -euo pipefail
cd "$(dirname "$0")/../../frontend"
PV_SECRETS_FILE=${PV_SECRETS_FILE:-$PWD/../.kind-secrets.env}
npm ci
npx playwright install ${PLAYWRIGHT_WITH_DEPS:+--with-deps} chromium
PV_DEMO_PASSWORD="$(grep '^PV_DEMO_PASSWORD=' "$PV_SECRETS_FILE" | cut -d= -f2-)" PV_UI_URL=http://127.0.0.1:8080 \
  npm run e2e
