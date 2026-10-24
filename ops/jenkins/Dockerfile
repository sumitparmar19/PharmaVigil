# Jenkins controller for PharmaVigil (M10): Jenkins LTS on JDK 21 with the pipeline's tools (Maven, Node.js, Docker
# CLI, kind, Helm, kubectl, Playwright's browser libraries), plugins from plugins.txt and configuration as code
# (casc.yaml) that creates the "pharmavigil" pipeline job. Builds run on the controller and use the host's Docker
# through the mounted socket: a local demo setup, not a hardened Jenkins.
FROM maven:3.9.15-eclipse-temurin-21 AS maven
FROM docker:29.8.2-cli AS docker
FROM node:24-trixie-slim AS node

FROM jenkins/jenkins:2.580.1-lts-jdk21
USER root
ARG KIND_VERSION=v0.33.0
ARG HELM_VERSION=v4.3.0
ARG KUBECTL_VERSION=v1.37.1
COPY --from=maven /usr/share/maven /usr/share/maven
COPY --from=docker /usr/local/bin/docker /usr/local/bin/docker
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/share/maven/bin/mvn /usr/local/bin/mvn \
    && ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && cd /tmp \
    && curl -fsSLo kind "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-linux-amd64" \
    && curl -fsSLo kind.sha256 "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/kind-linux-amd64.sha256sum" \
    && echo "$(cut -d' ' -f1 kind.sha256)  kind" | sha256sum -c - \
    && curl -fsSLo helm.tgz "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz" \
    && curl -fsSLo helm.sha256 "https://get.helm.sh/helm-${HELM_VERSION}-linux-amd64.tar.gz.sha256sum" \
    && echo "$(cut -d' ' -f1 helm.sha256)  helm.tgz" | sha256sum -c - \
    && curl -fsSLo kubectl "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl" \
    && curl -fsSLo kubectl.sha256 "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl.sha256" \
    && echo "$(cat kubectl.sha256)  kubectl" | sha256sum -c - \
    && tar xzf helm.tgz \
    && install kind kubectl linux-amd64/helm /usr/local/bin/ \
    && rm -rf /tmp/* \
    && npx --yes playwright@1.64.1 install-deps chromium \
    && git config --system --add safe.directory '*'
USER jenkins
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/casc.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc.yaml \
    JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true"
