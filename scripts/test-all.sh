#!/usr/bin/env bash
# Runs every test layer of the Native SSO implementation. The end-to-end, Postman and mutation
# layers need the stack from scripts/dev-up.sh.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)

echo "== ThunderID unit tests (OAuth + engine)"
(cd "$ROOT/thunderid/backend" && go test ./internal/oauth/... ./pkg/thunderidengine/... -count=1 | grep -v "no test files")
echo "== eSignet unit tests"
(cd "$ROOT/esignet/esignet-service" && CGO_ENABLED=0 go test ./... -count=1 | grep -v "no test files")
echo "== Fuzzing (20s each)"
(cd "$ROOT/thunderid/backend" && go test ./internal/oauth/oauth2/granthandlers/ -run '^$' -fuzz FuzzNativeSSOScopes -fuzztime 20s)
(cd "$ROOT/esignet/esignet-service" && CGO_ENABLED=0 go test ./internal/engine/ -run '^$' -fuzz FuzzValidateDeviceSecret -fuzztime 20s)
echo "== Android sso-core unit tests"
(cd "$ROOT/native-sso-android" && ./gradlew -q :sso-core:testDebugUnitTest && echo ok)
echo "== End-to-end scenarios"
(cd "$ROOT/native-sso-lab" && go run . -report results.json)
echo "== Postman collection (Native SSO path)"
"$ROOT/native-sso-lab/postman/run-native-sso.sh"
echo "== Mutation testing"
(cd "$ROOT/native-sso-lab" && python3 mutation/mutate.py --report mutation-report.json)
