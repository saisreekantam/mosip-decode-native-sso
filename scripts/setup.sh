#!/usr/bin/env bash
# Clones the ThunderID and eSignet forks (branch feature/native-sso) next to this repository's
# content, in the layout eSignet's go.mod replace directive (../../thunderid/backend) expects.
# Override the fork locations with THUNDERID_REPO / ESIGNET_REPO.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
THUNDERID_REPO=${THUNDERID_REPO:-https://github.com/saisreekantam/thunderid.git}
ESIGNET_REPO=${ESIGNET_REPO:-https://github.com/saisreekantam/esignet.git}
[ -d thunderid ] || git clone --branch feature/native-sso "$THUNDERID_REPO" thunderid
[ -d esignet ] || git clone --branch feature/native-sso "$ESIGNET_REPO" esignet
echo "Done. Next: scripts/dev-up.sh"
