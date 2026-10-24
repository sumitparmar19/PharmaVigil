{{/*
pv-lib.service renders a Deployment and a Service for one PharmaVigil service. Values (see a service chart's
values.yaml): image, port, env, secretEnv, resources, probes (liveness/readiness paths), volumes, volumeMounts,
service.type/nodePort, waitFor (host:port list an init container waits for; a Service only accepts connections
once it has a ready pod, so the service starts after its database and brokers instead of crash-looping).
Secrets are never in values: secretEnv maps an env var to a key of the existing Secret global.secretName.
*/}}
{{- define "pv-lib.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "pv-lib.labels" -}}
app.kubernetes.io/name: {{ include "pv-lib.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: pharmavigil
app.kubernetes.io/version: {{ .Values.image.tag | default .Values.global.imageTag | default .Chart.AppVersion | quote }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "pv-lib.selector" -}}
app.kubernetes.io/name: {{ include "pv-lib.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "pv-lib.service" -}}
{{- $name := include "pv-lib.name" . -}}
{{- $g := .Values.global | default dict -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    {{- include "pv-lib.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicas | default 1 }}
  selector:
    matchLabels:
      {{- include "pv-lib.selector" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "pv-lib.selector" . | nindent 8 }}
      {{- if .Values.metrics }}
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: /actuator/prometheus
        prometheus.io/port: {{ .Values.port | quote }}
      {{- end }}
    spec:
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      {{- with .Values.waitFor }}
      initContainers:
        - name: wait-for-dependencies
          image: {{ $g.waitImage | default "busybox:1.37.0" }}
          command:
            - sh
            - -c
            - |
              for target in {{ join " " . }}; do
                until nc -z -w 2 "${target%:*}" "${target##*:}"; do echo "waiting for $target"; sleep 3; done
              done
          securityContext:
            runAsUser: 65534
            runAsGroup: 65534
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          resources:
            requests: {cpu: 10m, memory: 8Mi}
            limits: {memory: 32Mi}
      {{- end }}
      containers:
        - name: {{ $name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default $g.imageTag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy | default $g.imagePullPolicy | default "IfNotPresent" }}
          ports:
            - name: http
              containerPort: {{ .Values.port }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: {{ .Values.readOnlyRootFilesystem | default false }}
            capabilities:
              drop: ["ALL"]
          {{- if or .Values.env .Values.secretEnv }}
          env:
            {{- range $k, $v := .Values.env }}
            - name: {{ $k }}
              value: {{ tpl (toString $v) $ | quote }}
            {{- end }}
            {{- range $k, $key := .Values.secretEnv }}
            - name: {{ $k }}
              valueFrom:
                secretKeyRef:
                  name: {{ $g.secretName | default "pharmavigil-secrets" }}
                  key: {{ $key }}
            {{- end }}
          {{- end }}
          {{- with .Values.probes }}
          startupProbe:
            httpGet: {path: {{ .liveness }}, port: http}
            periodSeconds: 5
            failureThreshold: {{ .startupFailureThreshold | default 60 }}
          livenessProbe:
            httpGet: {path: {{ .liveness }}, port: http}
            periodSeconds: 10
          readinessProbe:
            httpGet: {path: {{ .readiness }}, port: http}
            periodSeconds: 5
          {{- end }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          {{- with .Values.volumeMounts }}
          volumeMounts:
            {{- toYaml . | nindent 12 }}
          {{- end }}
      {{- with .Values.volumes }}
      volumes:
        {{- toYaml . | nindent 8 }}
      {{- end }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    {{- include "pv-lib.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type | default "ClusterIP" }}
  selector:
    {{- include "pv-lib.selector" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.port }}
      targetPort: http
      {{- if and (eq (.Values.service.type | default "ClusterIP") "NodePort") .Values.service.nodePort }}
      nodePort: {{ .Values.service.nodePort }}
      {{- end }}
{{- end -}}
