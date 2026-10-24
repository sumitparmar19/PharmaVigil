#!/usr/bin/env bash
# Deletes the kind cluster and the throwaway Secret file written by deploy.sh.
cd "$(dirname "$0")/../.."
kind delete cluster --name "${KIND_CLUSTER:-pv}" || true
rm -f "${PV_SECRETS_FILE:-$PWD/.kind-secrets.env}"
