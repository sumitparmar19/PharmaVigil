# Jenkins pipeline

<!-- How the Jenkins delivery pipeline is set up (configuration as code, docker compose) and how CI proves it runs.
     The GitHub Actions workflows remain the gate on pull requests; Jenkins shows the same delivery as one
     pipeline, from checkout to a smoke test on a fresh Kubernetes cluster. -->

## Stages (`ops/jenkins/Jenkinsfile`)

| Stage | What runs |
|---|---|
| Checkout | the commit being built |
| Build and unit tests | in parallel: `mvn install` plus the 40 Java tests that need no containers (they pass with Docker unreachable); frontend typecheck, lint, Vitest and build |
| Integration tests | `mvn verify`: every service against Oracle Database Free, Kafka and Artemis from Testcontainers; JUnit results recorded |
| Coverage gate | `jacoco:check` (≥ 70% line coverage per service) and a per-module summary |
| Images | the six images (`ops/kind/build-images.sh`) |
| Helm lint | dependency build and `helm lint` of the charts |
| Deploy to kind | `ops/kind/deploy.sh`: kind cluster, Strimzi Kafka and topics, random throwaway Secret, umbrella chart |
| Smoke test | `ops/kind/smoke.sh`: the Playwright smoke test against the cluster |

The kind and smoke stages share their scripts with the *Kubernetes e2e (kind)* workflow, so both run the same
deployment.

## The controller (`ops/jenkins/`)

- `Dockerfile`: Jenkins LTS 2.580.1 on JDK 21 with Maven 3.9.15, Node.js 24, Docker CLI 29.8.2, kind v0.33.0,
  Helm v4.3.0, kubectl v1.37.1 (checksums verified) and Playwright's browser libraries.
- `plugins.txt`: configuration-as-code, job-dsl, workflow-aggregator, git, timestamper, junit,
  pipeline-stage-view. Not pinned (the plugin update site is not reachable from the build container, so no
  versions could be checked there); every headless run log starts with the versions actually installed.
- `casc.yaml`: one local `admin` user whose password comes from `JENKINS_ADMIN_PASSWORD`, no sign-up, and the
  `pharmavigil` pipeline job (Job DSL) reading the Jenkinsfile from the mounted repository.
- `docker-compose.yml`: runs the controller on http://localhost:8090 with host networking and the host's Docker
  socket. **Local demo only:** the socket gives the builds full control of the host's Docker.

## Run it

```bash
ops/jenkins/run-headless.sh jenkins-run.log    # build, start, trigger one build, wait, write the log
```

or start it and use the UI:

```bash
cd ops/jenkins
export JENKINS_ADMIN_PASSWORD=<choose one> DOCKER_GID=$(stat -c %g /var/run/docker.sock) PV_GIT_REF=$(git rev-parse HEAD)
docker compose up -d --build     # then http://localhost:8090, job "pharmavigil", Build with Parameters
```

## Proof that it runs

The workflow *Jenkins pipeline (headless)* runs `run-headless.sh` on a GitHub runner whenever `ops/jenkins/`
changes (or on demand) and keeps the console log as an artifact; the log of a successful run is committed as
`analysis/m10/jenkins-run.log`. Jenkinsfile Runner was not used: its image on Docker Hub is only tagged `latest`
and could not be checked against the current Jenkins LTS, while a real controller also exercises the JCasC
setup that users run.
