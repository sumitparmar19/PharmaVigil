#!/usr/bin/env bash
# Creates ops/demo/.env on the demo server with fresh random secrets (file mode 600, values never printed), and the
# public hostname <ip-with-dashes>.sslip.io derived from the server's public IP, unless one is given. Run once by
# cloud-init; running it again keeps an existing .env. The only value meant to be seen is PV_DEMO_PUBLIC_PASSWORD:
# the landing page shows it to visitors on purpose.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f .env ]; then
  echo "ops/demo/.env exists; keeping it"
  exit 0
fi
host="${1:-}"
if [ -z "$host" ]; then
  ip="$(curl -fsS --retry 3 https://checkip.amazonaws.com | tr -d '[:space:]')"
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "could not find the public IP" >&2; exit 1; }
  host="${ip//./-}.sslip.io"
fi
rand() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
umask 077
{
  echo "# Live demo secrets, created by ops/demo/make-env.sh on this server; never copy them anywhere."
  echo "PV_DEMO_HOST=$host"
  echo "ORACLE_PASSWORD=Pv$(rand)"
  echo "PV_DB_PASSWORD=Pv$(rand)"
  echo "JWT_SECRET=$(rand)$(rand)"
  echo "PV_DEMO_PASSWORD=Pv$(rand)"
  echo "PV_DEMO_PUBLIC_PASSWORD=pharmavigil-$(head -c 4 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  echo "ARTEMIS_USER=pvbroker"
  echo "ARTEMIS_PASSWORD=Pv$(rand)"
  echo "SOAP_PARTNER_USER=demo-partner"
  echo "SOAP_PARTNER_PASSWORD=Pv$(rand)"
  echo "GRAFANA_ADMIN_PASSWORD=Pv$(rand)"
  echo "PV_IMAGE_TAG=demo"
} > .env
echo "ops/demo/.env created for https://$host"
