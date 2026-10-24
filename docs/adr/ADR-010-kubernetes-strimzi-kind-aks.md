# ADR-010: Kubernetes with Helm, Kafka through Strimzi, tested on kind, targeting AKS

<!-- Records how PharmaVigil is packaged for Kubernetes, why Kafka is run by the Strimzi operator rather than a
     Helm chart, and how the deployment is tested without a cloud account. -->

- **Status:** accepted
- **Milestone:** M10 (Azure in M11)

## Context

The local stack is docker compose. The build instructions ask for Helm charts per service plus an umbrella
chart, Strimzi for Kafka, an end-to-end test on kind in GitHub Actions, and Azure (AKS) later. Sumit has no paid
cloud budget, so everything must be provable on a free CI runner, and the same charts should later run on AKS.

## Options considered

1. **Plain manifests (kustomize).** Pros: no templating. Cons: five near-identical service manifests to keep in
   step; no single install/upgrade unit.
2. **One Helm chart per service built on a shared library chart, plus an umbrella chart.** Pros: one install
   unit; each service chart stays tiny and identical in shape; per-environment values files (kind, AKS).
   Cons: templating to learn.
3. **Kafka from a Helm chart (e.g. a community Kafka chart) instead of an operator.** Pros: one tool. Cons:
   topic, listener and upgrade management by hand.
4. **Kafka by the Strimzi operator** (CRDs `Kafka`, `KafkaNodePool`, `KafkaTopic`). Pros: declarative topics
   (the six pipeline topics and their `.dlq` twins are files in git), KRaft and rolling upgrades handled by the
   operator, the common way to run Kafka on AKS. Cons: an operator to install; its supported Kafka versions lag
   Apache releases.

## Decision

Options 2 and 4.

- `ops/helm/pv-lib` (library) renders a Deployment and Service with the same probes (Spring Boot liveness and
  readiness), non-root pod security, Prometheus annotations and secret wiring. The six service charts are
  generated from one table (`ops/helm/generate_service_charts.py`) so they cannot drift from compose.
- `ops/helm/pharmavigil` (umbrella) installs the services, the UI and, for demos, Oracle Free and Artemis
  (switch off with `oracle-free.enabled=false`, `artemis.enabled=false` to use managed services).
- **No secret in any chart.** The charts read a pre-created Secret (`global.secretName`); the kind test fills it
  with random throwaway values, AKS will fill it from Key Vault (M11).
- Kafka: Strimzi **0.51.0**, one dual-role KRaft node on kind (`ops/k8s/strimzi/`). Strimzi 0.51 supports Kafka
  up to **4.2.0**, so Kubernetes runs 4.2.0 while compose runs the Apache image 4.3.2; the services use
  kafka-clients 4.2.1, compatible with both.
- **Test:** `.github/workflows/kind-e2e.yml` builds the images, creates a kind v0.33.0 cluster, installs Strimzi,
  Kafka and the chart (Helm 4.3.0), and runs the same Playwright smoke test as the compose job through a NodePort.

## Consequences

- One `helm install` deploys everything except Kafka; Kafka is two `kubectl apply` calls after the operator.
- The kind test needs about 20–30 minutes on a free runner and pulls Oracle Free (several GB); it runs as its own
  workflow so the main CI stays fast.
- The model bundle and E2B(R3) schemas are not baked into the images; on Kubernetes they are mounted through the
  `volumes`/`volumeMounts` values (empty by default: cases go to manual coding and nothing can be submitted,
  which is the documented graceful-degradation path).
- On AKS the same charts are installed with an AKS values file (images from ACR, Secret from Key Vault).
