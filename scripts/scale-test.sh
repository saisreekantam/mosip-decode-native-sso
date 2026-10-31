#!/usr/bin/env bash
# Horizontal-scaling test: two eSignet instances share one Redis behind a round-robin nginx on :8080,
# and the native-sso-lab scenarios run against the load balancer. Every request can land on either
# instance, so SSO sessions, redemption locks, DPoP jti replay checks and logout must be shared.
#
#   scripts/scale-test.sh        start the scaled stack, run the scenarios, report per-instance traffic
#   scripts/scale-test.sh down   stop it (then scripts/dev-up.sh brings back the single instance)
#
# Needs the dependencies from scripts/dev-up.sh (Postgres :5455, mock identity :8082).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SVC="$ROOT/esignet/esignet-service"
PORTS=(8181 8182)
REDIS_PORT=6380
LOG_DIR=${LOG_DIR:-/tmp}

down() {
  for p in "${PORTS[@]}"; do lsof -ti tcp:$p -sTCP:LISTEN 2>/dev/null | xargs kill 2>/dev/null || true; done
  docker rm -f nsso-lb nsso-redis >/dev/null 2>&1 || true
}

if [ "${1:-}" = down ]; then down; echo "scaled stack stopped"; exit 0; fi

[ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8082/v1/mock-identity-system/identity)" != 000 ] \
  || { echo "mock identity system not reachable on :8082; run scripts/dev-up.sh first"; exit 1; }

echo "== stopping a single eSignet on :8080 if one is running"
if lsof -ti tcp:8080 -sTCP:LISTEN >/dev/null 2>&1 && ! docker ps --format '{{.Names}}' | grep -qx nsso-lb; then
  lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill
  pkill -f "go run ./cmd/esignet" 2>/dev/null || true
  sleep 2
fi
down

echo "== Redis on :$REDIS_PORT"
docker run -d --name nsso-redis -p $REDIS_PORT:6379 redis:7.4-alpine >/dev/null
for _ in $(seq 1 30); do docker exec nsso-redis redis-cli ping 2>/dev/null | grep -q PONG && break; sleep 1; done

echo "== building eSignet once"
(cd "$SVC" && CGO_ENABLED=0 go build -o out/esignet ./cmd/esignet)

start_instance() {
  local port=$1
  (
    cd "$SVC"
    # A temp file rather than <(...): bash 3.2 (macOS) cannot source a process substitution.
    if [ -f .env ]; then env_file=$(mktemp); tr -d '\r' < .env > "$env_file"; set -a; source "$env_file"; set +a; rm -f "$env_file"; fi
    export PORT=$port METRICS_PORT=$((port + 1000)) MOSIP_ESIGNET_HOST=http://localhost:8080 DATA_DIR=./data \
      MOSIP_ESIGNET_AUTHN_PROVIDER=${MOSIP_ESIGNET_AUTHN_PROVIDER:-mock} \
      MOSIP_ESIGNET_CACHE_TYPE=redis REDIS_URL=redis://localhost:$REDIS_PORT/0
    nohup ./out/esignet > "$LOG_DIR/esignet-$port.log" 2>&1 &
  )
  for _ in $(seq 1 120); do curl -sf localhost:$port/health >/dev/null && return 0; sleep 1; done
  echo "eSignet on :$port did not start, see $LOG_DIR/esignet-$port.log"; exit 1
}
# Start one at a time so the first instance creates any missing keymanager keys alone.
for p in "${PORTS[@]}"; do echo "== eSignet instance on :$p (log $LOG_DIR/esignet-$p.log)"; start_instance $p; done

echo "== nginx round-robin load balancer on :8080"
CONF="$LOG_DIR/nsso-lb.conf"
cat > "$CONF" <<EOF
events {}
http {
  upstream esignet { server host.docker.internal:${PORTS[0]}; server host.docker.internal:${PORTS[1]}; }
  server {
    listen 8080;
    location / {
      proxy_pass http://esignet;
      proxy_set_header Host localhost:8080;
      proxy_set_header X-Forwarded-For \$remote_addr;
    }
  }
}
EOF
docker run -d --name nsso-lb --add-host=host.docker.internal:host-gateway -p 8080:8080 \
  -v "$CONF":/etc/nginx/nginx.conf:ro nginx:1.27-alpine >/dev/null
for _ in $(seq 1 30); do curl -sf localhost:8080/health >/dev/null && break; sleep 1; done

echo "== scenarios against the load balancer"
count() { grep -c '"level":"ACCESS"' "$LOG_DIR/esignet-$1.log" || true; }
tokens() { grep '"level":"ACCESS"' "$LOG_DIR/esignet-$1.log" | grep -c '/oauth2/token' || true; }
before=($(count ${PORTS[0]}) $(count ${PORTS[1]}))
status=0
(cd "$ROOT/native-sso-lab" && go run . -report results-scale.json) || status=$?

echo "== traffic per instance"
for i in 0 1; do
  p=${PORTS[$i]}
  echo "instance :$p  requests $(( $(count $p) - before[i] ))  token-endpoint calls $(tokens $p)"
done
echo "redis keys: $(docker exec nsso-redis redis-cli dbsize)"
exit $status
