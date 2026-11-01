# Native SSO for eSignet Go: sign in once, use every app of the family

[![CI](https://github.com/saisreekantam/mosip-decode-native-sso/actions/workflows/ci.yml/badge.svg)](https://github.com/saisreekantam/mosip-decode-native-sso/actions/workflows/ci.yml)

**MOSIP Decode 2026, problem statement 03: Enable Native SSO in eSignet Go for Mobile Apps.**

**Team TechTitans:** Sai Venkat, Revanth, Aditya, Ashok

A user signs in to one app with eSignet (OTP plus consent). Every other app from the same vendor on that phone then signs them in silently, with no OTP and no consent screen, in about 20 ms. The shared login is protected by:

- a device secret that is bound to the device's own hardware key and encrypted;
- an app-to-app channel that only apps signed by the vendor can use;
- a consent check, so a sibling app gets only the claims the user agreed to share.

The implementation follows OpenID Connect Native SSO for Mobile Apps 1.0 and the eSignet design in the problem statement. It covers all 10 mandatory tasks and the bonus task, plus a set of security hardening features.

| Super App signs in once | Pharmacy app: silent SSO | Consent not covered | Logout cuts off tokens | Rogue app refused |
|---|---|---|---|---|
| ![](docs/screenshots/03-super-app-signed-in.png) | ![](docs/screenshots/04-pharmacy-silent-sso.png) | ![](docs/screenshots/05-pharmacy-consent-needed.png) | ![](docs/screenshots/07-token-cut-off-after-logout.png) | ![](docs/screenshots/08-rogue-app-refused.png) |

## What is in this submission

| Part | Where | What it is |
|---|---|---|
| ThunderID engine changes | fork `thunderid`, branch `feature/native-sso` | Protocol: `device_sso` scope, `DeviceSecretProvider`/`NativeSSOConsentProvider` interfaces, `ds_hash` and `sid` in the ID token, `device_secret` in the token response, the device-secret token exchange with every check from the spec, per-app attributes, tokens that end with their session |
| eSignet changes | fork `esignet`, branch `feature/native-sso` | Policy: device secret (signed with ES256, then encrypted as RSA-OAEP JWE through the keymanager), SSO sessions, same-vendor allow-list, consent reuse with an audit column, `POST /native-sso/logout`, config, client registration, DB migration, Postman collection, [design doc](docs/design.md) |
| Android apps | [`native-sso-android/`](native-sso-android) | **Acme Super App** (eSignet login with PAR + DPoP + PKCE, keys in the Android Keystore, signature-protected SSO service) and **Acme Pharmacy** (silent SSO, step-by-step "why trusted" trace, re-consent fallback), plus a rogue build signed with another certificate |
| SSO Guardian test lab | [`native-sso-lab/`](native-sso-lab) | 21 end-to-end scenarios (happy path, attacks, consent, races, re-sign-in after token expiry, logout) against a live eSignet, a dashboard to replay any of them, mutation testing, a Postman runner |
| Docs | [`docs/`](docs) | [Design and API](docs/design.md), [conformance and threat model](docs/conformance.md), [testing and results](docs/testing.md), [iOS design](docs/ios-design.md), [demo script](docs/demo-script.md), [submission checklist](docs/submission.md) |

## Since the demo video (v1.0.0 → v1.1.0)

**The demo video was recorded with [v1.0.0](https://github.com/saisreekantam/mosip-decode-native-sso/releases/tag/v1.0.0).** The current version is [v1.1.0](https://github.com/saisreekantam/mosip-decode-native-sso/releases/tag/v1.1.0). The protocol, the APIs and every button in the apps work as in the video; screens and screenshots may look different. v1.1.0 adds:

- **Sibling apps sign in again after their token expires.** An app holds one live token per SSO session; when it expires, the app signs in again silently while the session is open. Replays are still refused (`redemption_policy`, scenario S1).
- **Plain-language screens.** Key thumbprints, `sid` and token fields moved behind a *Developer details* switch. The trust trace is now an animated timeline.
- **Connected apps (Super App).** Lists which Acme apps use the sign-in and when each last used it. It offers a per-app *Sign out*, which takes effect at once because the app needs the Super App to sign every request, and a confirmation before *Log out of all Acme apps*.
- **Consent preview (Pharmacy).** "Pharmacy will see: Name, Date of birth, Gender, Photo" before signing in. When consent is missing, it names exactly which detail.
- **Visual identity.** App icons, a brand colour per app (Super App blue, Pharmacy green), a top bar, loading spinners and a success tick.
- **Proof:** a [conformance and threat-model table](docs/conformance.md) and a [horizontal-scaling run](docs/testing.md#scales-horizontally) (two eSignet instances sharing Redis).

## Architecture

```mermaid
flowchart LR
  subgraph Phone
    SA["Acme Super App<br/>key A in Keystore<br/>id_token + device_secret (encrypted)"]
    CA["Acme Pharmacy<br/>(same signing certificate)"]
    CA -- "bindService<br/>signature permission" --> SA
    SA -- "SSO bundle +<br/>DPoP proofs signed by key A" --> CA
  end
  UI["eSignet login UI<br/>(oidc-ui)"]
  subgraph eSignet["eSignet Go"]
    T["ThunderID engine<br/>authorization code + token exchange grants"]
    DSP["DeviceSecretProvider<br/>JWS→JWE, SSO sessions, vendor allow-list"]
    CP["NativeSSOConsentProvider<br/>consent reuse + audit"]
    KM["keymanager"]
    T --> DSP --> KM
    T --> CP
  end
  SA -- "PAR + DPoP, OTP login" --> UI --> T
  SA -- "code → access_token, id_token(ds_hash, sid), device_secret" --> T
  CA -- "token exchange: id_token + device_secret + DPoP(key A)" --> T
```

The step-by-step sequence diagrams for both flows are in [docs/design.md](docs/design.md).

## How the requirements map to the code

| # | Problem statement task | Done in |
|---|---|---|
| 1 | `device_sso` scope on the authorize request | ThunderID `granthandlers/native_sso.go` (`splitDeviceSSOScope`), `utils/oauthutils.go` |
| 2 | Token endpoint checks `allowed_authorization_scopes` contains `device_sso` | `validateDeviceSSORequest` |
| 3 | Device Secret Provider interface for ThunderID | `pkg/thunderidengine/providers/interface.go`, `thunderidengine.WithDeviceSecretProvider` |
| 4 | Generate (signed JWT: `cnf.jkt`, `auth_id`, `exp`, `client_id`, `scope`, `acr`, `claims`, `iss`) and Validate | eSignet `internal/engine/device_secret_provider.go` |
| 5 | Token endpoint invokes generation | `issueDeviceSecret` in the authorization code grant |
| 6 | Return `access_token`, `id_token`, `device_secret` (signed then encrypted) | token response `device_secret`; ID token `ds_hash`, `sid` |
| 7 | Second app gets `id_token`, `device_secret` and a DPoP proof app-to-app | Android `SsoService` (Super App) + `SsoConnection` (Pharmacy) |
| 8 | Token exchange call reusing the first app's key, without `device_sso` | `EsignetClient.nativeSsoExchange` |
| 9 | Token exchange grant: device-secret actor type, validate secret, `ds_hash`, `sid`, `device_sso` allowed, `cnf.jkt` = DPoP key | `handleNativeSSOGrant`, `verifyNativeSSOIDToken` |
| 10 | Scope subset rule; reject `device_sso` on token exchange | `nativeSSOScopes`, `validateNativeSSOExchangeRequest` |
| Bonus | Re-consent screen in the second app when scopes differ | `interaction_required` → Pharmacy "Review and consent" (eSignet login with `prompt=consent`) |

Beyond the problem statement:

- **Each app gets only its own claims.** The child's userinfo is re-issued to the child with only the consented claims. The first version leaked the Super App's identity record; the fix is described in the design doc.
- **Tokens end with their session.** Logout cuts off tokens already issued to sibling apps, not just future sign-ins.
- **Vendor boundary.** A sibling app must be under the same `rp_id` and on the Super App's allow-list.
- **One live token per app per SSO session.** Each redemption is claimed atomically: of 100 concurrent redemptions of one device secret, exactly 1 succeeds, and a replay while the app's token is still valid is refused. Once that token expires, the app signs in again silently for as long as the SSO session lasts (`redemption_policy`, see the design doc).
- **Consent reuse is audited.** `consent_history.reused_from_client_id` records which app's consent was reused.
- **Expired ID tokens are accepted on purpose.** They live 120 s, so the device secret's expiry and the server-side SSO session govern access instead.

## Run it

**Prerequisites:** Docker; Go 1.26+; for the apps, JDK 17 and the Android SDK (platform 35) with an emulator or a USB device.

```bash
scripts/setup.sh          # clone the two forks next to this repo (set THUNDERID_REPO / ESIGNET_REPO)
scripts/dev-up.sh         # Postgres + mock identity, eSignet login UI (:3000), eSignet (:8080)
```

**Android apps:** prebuilt APKs are attached to the [v1.1.0 release](https://github.com/saisreekantam/mosip-decode-native-sso/releases/tag/v1.1.0) (`adb install` both, then the `adb reverse` line below). The demo video shows the [v1.0.0](https://github.com/saisreekantam/mosip-decode-native-sso/releases/tag/v1.0.0) APKs. To build them yourself:

```bash
cd native-sso-android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :super-app:installDebug :child-app:installDebug   # both signed with the same debug certificate
adb reverse tcp:8080 tcp:8080 && adb reverse tcp:3000 tcp:3000 && adb reverse tcp:8082 tcp:8082
```

1. In **Acme Super App**, tap *Create demo user* (the OTP is always `111111`), then *Sign in with eSignet*.
2. Open **Acme Pharmacy** and tap *Continue with your Acme account*.
3. To see the refusal path, install `:child-app:installRogue`; that build is signed with a different certificate.

**Test lab and dashboard:**

```bash
cd native-sso-lab
go run .                     # 21 scenarios against the running eSignet; exits non-zero on any failure
go run . serve               # SSO Guardian dashboard: http://localhost:8099 (?autorun=all)
scripts/test-all.sh          # every layer: unit, fuzz, Android, end-to-end, Postman, mutation
scripts/scale-test.sh        # two eSignet instances + Redis + nginx, scenarios through the load balancer
```

## Results

| Layer | Result |
|---|---|
| ThunderID unit tests (Native SSO) | 57 pass, plus the existing OAuth/engine suites |
| eSignet unit tests (Native SSO) | 55 pass, plus the full existing suite |
| Fuzzing | ~3.2 M inputs, no failures |
| Mutation testing | 17/17 disabled security checks caught |
| End-to-end scenarios | 21/21 ([report](docs/e2e-results.json)) |
| Postman collection | 18/18 steps |
| Scales horizontally | 21/21 scenarios against two eSignet instances sharing Redis behind a load balancer; R1 across instances: exactly 1 of 100 succeeds ([details](docs/testing.md#scales-horizontally)) |
| Android on device | silent SSO, consent fallback, re-consent, logout cut-off, rogue refusal |

Details and test data: [docs/testing.md](docs/testing.md).
