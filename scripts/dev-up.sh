#!/usr/bin/env bash
# Starts the local Native SSO stack: Postgres + mock identity system, the eSignet login UI, and
# eSignet (feature/native-sso) built from source against the ThunderID fork next to it.
# Expects this layout (scripts/setup.sh creates it):
#   ./thunderid  (thunder-id/thunderid fork, branch feature/native-sso)
#   ./esignet    (mosip/esignet fork,      branch feature/native-sso)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

[ -d thunderid/backend ] && [ -d esignet/esignet-service ] || { echo "run scripts/setup.sh first"; exit 1; }

echo "== Postgres + mock identity system"
docker compose -f esignet/docker-compose/dependent-docker-compose.yaml up -d
echo "== eSignet login UI (oidc-ui) on :3000"
docker compose -f native-sso-lab/dev/docker-compose.oidc-ui.yaml up -d

cd esignet/esignet-service
if [ ! -f .env ]; then
  cp .env.example .env
  sed -i.bak -e 's/^DATABASE_PORT=5432/DATABASE_PORT=5455/' -e 's/^DATABASE_USERNAME=esignet/DATABASE_USERNAME=postgres/' \
    -e 's/^# DB_DBUSER_PASSWORD=secret/DB_DBUSER_PASSWORD=postgres/' \
    -e 's#^KEYMANAGER_PKCS12_FILE_PATH=/opt/mosip/test.pfx#KEYMANAGER_PKCS12_FILE_PATH=./keystore.pfx#' .env && rm -f .env.bak
fi
echo "== eSignet on :8080 (log: /tmp/esignet-run.log)"
if ! curl -sf localhost:8080/health >/dev/null; then
  nohup ./make.sh run > /tmp/esignet-run.log 2>&1 &
  for _ in $(seq 1 180); do curl -sf localhost:8080/health >/dev/null && break; sleep 1; done
fi
curl -sf localhost:8080/health >/dev/null && echo "eSignet is up: http://localhost:8080/.well-known/openid-configuration" \
  || { echo "eSignet did not start, see /tmp/esignet-run.log"; exit 1; }

if command -v adb >/dev/null && adb get-state >/dev/null 2>&1; then
  for p in 8080 3000 8082; do adb reverse tcp:$p tcp:$p >/dev/null; done
  echo "adb reverse set for 8080/3000/8082: the apps reach the stack at localhost"
fi
