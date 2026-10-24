# The live demo: hosting, memory budget and operations

<!-- How the public PharmaVigil demo runs (Phase 2, P5–P7): one Oracle Cloud Always Free Ampere A1 VM with docker
     compose, what is on it, the memory budget with the measured usage, how it is deployed, reset, limited and
     tested. Measured numbers are copied from analysis/p6/ with their scope. -->

Not for clinical or regulatory use. Spontaneous reports do not prove that a drug caused a reaction.

## What runs where

| Piece | Where | Notes |
|---|---|---|
| The whole platform | one Oracle Cloud **Always Free** Ampere A1 VM (arm64, 2 OCPUs, 12 GB, Ubuntu 24.04) | `docker-compose.demo.yml`, started by systemd |
| Images | GHCR `ghcr.io/sumitparmar19/pharmavigil-*`, linux/amd64 + linux/arm64 | built by *Demo images* on every push to main, tagged with the commit SHA and `demo` |
| Models, signal export, replay sample | release asset `demo-data-v1` (`pharmavigil-demo-data.tar.gz`) | SHA-256 pinned in `ops/demo/demo-data.lock`, checked before unpacking |
| ICH E2B(R3) schemas | downloaded on the VM from EMA's published copy | `scripts/fetch-e2b-xsd.sh`; never committed or baked into images |
| HTTPS | Caddy with a Let's Encrypt certificate for `<ip-with-dashes>.sslip.io` | no domain to buy |
| Secrets | `ops/demo/.env` on the VM, created by `ops/demo/make-env.sh` with random values | never in git, chat, CI logs or images |
| Jenkins | not on the VM | stays in CI |

As of 15 June 2026 an Always Free account gets **2 OCPUs and 12 GB of memory in total** for Ampere A1 (no longer
4 and 24), plus 200 GB of block storage. Oracle may reclaim an Always Free instance that stays below 20% CPU,
network *and* memory use for 7 days; the demo's steady-state memory use (below) keeps it above that line.

## Memory budget (target: under 9 GB at steady state)

Every container has an explicit limit; the Java services run with `-XX:MaxRAMPercentage=60` and the serial GC,
Kafka with a 384 MB heap, Artemis with 256 MB.

| Container | Limit |
|---|---|
| oracle | 2,800 MiB |
| kafka | 768 MiB |
| artemis | 512 MiB |
| pharmacy-service | 512 MiB |
| intake-gateway | 576 MiB |
| case-service | 1,280 MiB |
| signal-service | 640 MiB |
| submission-gateway | 512 MiB |
| frontend (nginx) | 96 MiB |
| prometheus | 320 MiB |
| grafana | 320 MiB |
| caddy | 128 MiB |
| **total of the limits** | **8,464 MiB (8.3 GiB)** |

Measured usage (idle and during a replay) is added here from `analysis/p6/` once measured; not measured yet.

## Deploy, roll back, reset

- **First start:** `ops/demo/cloud-init.yaml` (pasted when the VM is created) installs Docker and a 4 GB swap file,
  opens ports 80 and 443 in the VM firewall (SSH stays key-only), clones the repository, creates `.env`, downloads
  the demo data and the schemas, starts the stack and schedules the nightly reset.
- **Every new version:** *Demo images* pushes the images, then *Deploy demo* connects over SSH (key in the
  `DEMO_SSH_KEY` secret of the protected `demo` environment) and runs `ops/demo/deploy.sh <tag>`: update the
  checkout, refresh the data, pull, restart, and check `https://<host>/api/status`. If the check fails it goes
  back to the previous tag.
- **Nightly reset (04:30 UTC):** `ops/demo/reset.sh` deletes the database, Kafka and metrics volumes and starts
  again from the seed state (a few minutes of downtime). The TLS certificate is kept.

## Abuse limits

- nginx (`ops/demo/nginx.demo.conf`): 10 requests/s per client IP for the API, 10 sign-ins per minute, 4 replay
  starts per minute; request bodies up to 16 KB; no public route to E2B uploads, pharmacy report intake, the
  signal reload, actuator endpoints or SOAP.
- Replay: one run at a time per account, a 2-minute cooldown, at most 200 reports, at most two runs at once.
- Caddy: 64 KB body limit, header and body read timeouts.
- No sign-up, no file uploads, no outbound calls triggered by user input (narratives use the template; signal
  lookups stay inside the server).

## Proof

- *Live smoke test* (daily and on demand): Playwright against `DEMO_URL` signs in with the account shown on the
  landing page, runs a 10-report replay, waits for an ACK, opens the E2B(R3) XML, checks Signals and Evidence.
- `GET /api/status` (public): each service's health, Kafka lag, the loaded model versions; no secrets or data.
