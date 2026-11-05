#!/usr/bin/env bash
# Lints and unit-tests the Helm chart with the pinned tool images (deploy/helm/tools.env).
# Only Docker is needed on the host. Bash 3.2 compatible.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
helm_dir="$(cd "$here/.." && pwd)"
chart="$helm_dir/reliable-push"

# shellcheck source=../tools.env
. "$helm_dir/tools.env"

# Required values (no defaults on purpose) supplied for linting only; placeholders, not secrets.
lint_args="--set image.repository=registry.example.com/reliable-push --set database.url=jdbc:postgresql://db:5432/rp --set database.username=rp --set secrets.existingSecret=rp-secrets --set config.apps.shop.default-timeout-minutes=10"

echo "== helm lint --strict ($HELM_IMAGE)"
# shellcheck disable=SC2086
docker run --rm -v "$chart:/apps:ro" "$HELM_IMAGE" lint --strict $lint_args /apps

echo "== helm lint --strict, mode=split"
# shellcheck disable=SC2086
docker run --rm -v "$chart:/apps:ro" "$HELM_IMAGE" lint --strict $lint_args --set mode=split /apps

echo "== helm unittest ($HELM_UNITTEST_IMAGE)"
docker run --rm -v "$chart:/apps:ro" "$HELM_UNITTEST_IMAGE" /apps

echo "validate.sh: all checks passed"
