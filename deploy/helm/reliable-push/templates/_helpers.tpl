{{/* Chart name. */}}
{{- define "rp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Fully qualified name: the release name, with the chart name appended unless it is already part of it. */}}
{{- define "rp.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "rp.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Labels shared by every object (also the selector of the management Service). */}}
{{- define "rp.selectorLabels" -}}
app.kubernetes.io/name: {{ include "rp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "rp.labels" -}}
helm.sh/chart: {{ include "rp.chart" . }}
{{ include "rp.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* The management port (probes, Prometheus, GKE health check); fixed, never routed by Ingress/Route. */}}
{{- define "rp.managementPort" -}}8081{{- end -}}

{{/* Annotations of the API Service: api.service.annotations, plus the BackendConfig reference when the chart renders one. */}}
{{- define "rp.apiServiceAnnotations" -}}
{{- $ann := deepCopy (default (dict) .Values.api.service.annotations) -}}
{{- if .Values.gke.backendConfig.enabled -}}
{{- $_ := set $ann "cloud.google.com/backend-config" (printf "{\"default\": \"%s-api\"}" (include "rp.fullname" .)) -}}
{{- end -}}
{{- if $ann -}}
{{- toYaml $ann -}}
{{- end -}}
{{- end -}}

{{/* Component that serves the API Service: "combined" or "api". */}}
{{- define "rp.apiComponent" -}}
{{- if eq .Values.mode "split" -}}api{{- else -}}combined{{- end -}}
{{- end -}}

{{- define "rp.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "rp.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "rp.image" -}}
{{- $repo := required "image.repository is required (the backend image repository, e.g. registry.example.com/reliable-push)" .Values.image.repository -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" $repo .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}
{{- end -}}

{{/* Checks the required values; fails with a message naming the key. */}}
{{- define "rp.validate" -}}
{{- $_ := required "image.repository is required" .Values.image.repository -}}
{{- $_ := required "database.url is required (JDBC URL, e.g. jdbc:postgresql://host:5432/db)" .Values.database.url -}}
{{- $_ := required "database.username is required" .Values.database.username -}}
{{- $_ := required "secrets.existingSecret is required (name of an existing Secret holding SPRING_DATASOURCE_PASSWORD and every env:NAME referenced in config)" .Values.secrets.existingSecret -}}
{{- if not (and .Values.config (kindIs "map" .Values.config.apps) .Values.config.apps) -}}
{{- fail "config.apps must contain at least one app" -}}
{{- end -}}
{{- include "rp.checkRefs" (dict "path" "config" "value" .Values.config) -}}
{{- end -}}

{{/*
Walks maps and lists under config: every key ending in "ref" (any case, with "-", "_" or camelCase: hmac-secret-ref,
hmac_secret_ref, hmacSecretRef, as Spring's relaxed binding accepts them all) must be env:NAME. The message names the
key path and never echoes the value (it may be an inline secret).
*/}}
{{- define "rp.checkRefs" -}}
{{- $path := .path -}}
{{- $v := .value -}}
{{- if kindIs "map" $v -}}
{{- range $k, $x := $v -}}
{{- $p := printf "%s.%s" $path $k -}}
{{- if regexMatch "(?i)[-_]?ref$" $k -}}
{{- if not (and (kindIs "string" $x) (regexMatch "^env:[A-Za-z_][A-Za-z0-9_]*$" $x)) -}}
{{- fail (printf "%s must be a reference of the form env:NAME (NAME is a key of secrets.existingSecret); inline values are not allowed" $p) -}}
{{- end -}}
{{- else -}}
{{- include "rp.checkRefs" (dict "path" $p "value" $x) -}}
{{- end -}}
{{- end -}}
{{- else if kindIs "slice" $v -}}
{{- range $i, $x := $v -}}
{{- include "rp.checkRefs" (dict "path" (printf "%s[%d]" $path $i) "value" $x) -}}
{{- end -}}
{{- end -}}
{{- end -}}
