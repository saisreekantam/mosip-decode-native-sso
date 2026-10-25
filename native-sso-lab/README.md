# native-sso-lab

An end-to-end test harness for Native SSO in eSignet Go. It plays the Super App, the Child App and several attackers against a running eSignet, and prints one result per scenario.

It models the device the way Android does. The Super App's DPoP key (key A) lives in a non-exportable `SecureKey`. A sibling app can only use it by binding the Super App's SSO service, and the OS allows that only for apps signed with the same certificate. The Child App never holds key A. It asks the service for signed DPoP proofs.

## Run

```bash
# 1. Postgres + mock identity system
cd ../esignet/docker-compose && docker compose -f dependent-docker-compose.yaml up -d
# 2. eSignet (feature/native-sso), from esignet-service with the local .env
cd ../esignet-service && ./make.sh run
# 3. The scenarios
cd ../../native-sso-lab && go run . -report results.json        # add -v to print every HTTP exchange
```

The harness seeds a fresh mock identity and registers six clients on every run: the Super App, two child apps, an unlisted app of the same vendor, an app of another vendor, and an app not registered for `device_sso`. It then runs:

| ID | Scenario | Expected |
|---|---|---|
| H1 | Super App OTP login requesting `device_sso` | 200 with `device_secret`; `ds_hash`/`sid` in the ID token; access token bound to key A |
| H2 | Child App silent sign-in through token exchange | 200 DPoP token for the child, `cnf.jkt` = key A, `act.sub` = Super App |
| H3 | Child App userinfo with a borrowed DPoP proof | 200 with the claims consented at Super App login |
| A1 | Same app redeems the same device secret twice | `invalid_grant` |
| A2 | Stolen ID token + device secret used with the attacker's own key | `invalid_grant` |
| A3 | No DPoP proof | `invalid_dpop_proof` |
| A4 | One byte of the device secret ciphertext flipped | `invalid_grant` |
| A5 | ID token of another session presented with this device secret | `invalid_grant` |
| A6 | Child asks for a scope the Super App never got | `invalid_scope` |
| A7 | `device_sso` requested on token exchange | `invalid_scope` |
| A8 | App of another vendor (`rp_id`), even though allow-listed | `invalid_grant` |
| A9 | Same vendor but not allow-listed | `invalid_grant` |
| A10 | App signed with another certificate binds the SSO service | refused by the OS |
| A11 | Client not registered for `device_sso` requests it | `invalid_scope` |
| C1 | User declined email; child asks for email | `interaction_required` |
| C2 | Consent reuse audited | `consent_history` row with `reused_from_client_id` |
| R1 | 100 concurrent redemptions of one device secret | exactly 1 succeeds |
| R2 | A captured DPoP proof replayed against userinfo | 401 |
| L2 | Logout with a child token already issued | userinfo 200 before, 401 after |
| L1 | Super App ends the SSO session; sibling tries to sign in | `invalid_grant` |

The process exits non-zero if any scenario fails, so it can gate CI.

## SSO Guardian dashboard

`go run . serve` (then open http://localhost:8099, or add `?autorun=all`) replays any scenario against the live eSignet. For each one it shows the requirement, the expected and observed result, and the requests made with eSignet's answer. Tokens, secrets and DPoP proofs are never recorded. The dashboard also runs and shows the mutation report.

## Mutation testing

`python3 mutation/mutate.py --report mutation-report.json` disables each of 16 security checks in the ThunderID and eSignet sources, one at a time. For each, it runs that repository's Native SSO unit tests and expects them to fail; the sources are always restored. 16/16 mutants are caught.

## Postman

`postman/run-native-sso.sh` runs the eSignet Postman collection's Native SSO path end to end (18 requests), executing the collection's own scripts in Node, because newman's sandbox has no Web Crypto.

## Local eSignet login UI

`dev/docker-compose.oidc-ui.yaml` runs the official `mosipid/oidc-ui` image with its API proxy pointed at an eSignet running from source on the host. The Android apps use it in a Custom Tab.
