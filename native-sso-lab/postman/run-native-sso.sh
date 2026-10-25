#!/usr/bin/env bash
# Runs the eSignet Postman collection's Native SSO path end to end against a running eSignet,
# executing the collection's own scripts (see run.cjs for why newman cannot be used).
set -euo pipefail
cd "$(dirname "$0")"
[ -d node_modules ] || npm install --silent
COLLECTION=../../esignet/postman-collection/Go-eSignet.postman_collection.json
ENV_FILE=$(mktemp)
trap 'rm -f "$ENV_FILE"' EXIT
python3 - "../../esignet/postman-collection/Go-eSignet (local).postman_environment.json" "$ENV_FILE" <<'PY'
import json, random, sys
env = json.load(open(sys.argv[1]))
for v in env["values"]:
    if v["key"] == "client_id":
        v["value"] = f"postman-super-{random.randint(1, 10**9)}"
    if v["key"] == "individual_id":
        v["value"] = str(random.randint(1000000000, 9999999999))
json.dump(env, open(sys.argv[2], "w"))
PY
node run.cjs "$COLLECTION" "$ENV_FILE" \
  "Client Management/Create client" "User Mgmt/Mock/Create User" \
  "Native SSO/Create Child App client" "Native SSO/Enable device_sso on the Super App client" \
  "FAPI2.0 flow/Initiate PAR" "FAPI2.0 flow/Initiate Authorization" \
  "FAPI2.0 flow/Flow execute — start" "FAPI2.0 flow/Flow execute — select acr" \
  "FAPI2.0 flow/Flow execute — enter uin" "FAPI2.0 flow/Flow execute — enter OTP" \
  "FAPI2.0 flow/Flow execute — consent" "FAPI2.0 flow/Auth callback" "FAPI2.0 flow/Exchange Code for Token" \
  "Native SSO/Child App token exchange (silent SSO)" "Native SSO/Child App userinfo" \
  "Native SSO/Replay the device secret (expect invalid_grant)" "Native SSO/Native SSO logout (all apps)" \
  "Native SSO/Child App userinfo after logout (expect 401)"
