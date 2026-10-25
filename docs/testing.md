# Test cases and results

All results below come from runs against a live eSignet (feature/native-sso) built from source, a mock identity system, and the real eSignet login UI. Reproduce everything with `scripts/test-all.sh`.

## Test data

| Item | Value | Source |
|---|---|---|
| Demo identity | random 10-digit UIN, name Asha Devi, born 1990/01/01, email asha.devi@example.com | created per run via `POST :8082/v1/mock-identity-system/identity` (the harness, the Postman runner, or the Super App's *Create demo user* button) |
| OTP | `111111` | fixed by the mock identity system |
| Captcha | hCaptcha test site key; the harness sends `test-captcha-token` | eSignet local configuration |
| Relying party | `acme-health` (vendor); `other-vendor` for the cross-vendor attack | client registration |
| Clients | Super App (`device_sso` allowed, allow-list of siblings); Pharmacy and Labs (token-exchange grant); an unlisted same-vendor app; another vendor's app; an app not registered for `device_sso` | registered fresh on every run with new keys |
| Keys | client keys RSA-2048 (PS256); device key A EC P-256 (ES256 DPoP); on Android both live in the Keystore | generated per run or install |

No real credentials or personal data are used anywhere.

## End-to-end scenarios (native-sso-lab)

| ID | Scenario | Requirement | Expected | Observed | Result |
|---|---|---|---|---|---|
| H1 | Super App signs in with OTP and requests device_sso | Tasks 1-6: device_sso scope, token endpoint returns access_token + id_token + device_secret | 200 with device_secret; id_token carries sid and ds_hash bound to the device secret; DPoP-bound access token | device_secret is a 5-part JWE (1715 bytes); sid=01a16a5b-02cf-7f75-b93c-7951f147b33a; ds_hash verified; access token bound to key A | PASS |
| H2 | Child App signs in silently via token exchange (no login, no consent screen) | Tasks 7-9: id_token + device_secret + DPoP proof via app-to-app channel, token_exchange grant | 200 DPoP access token for the Child App, bound to key A, act.sub = Super App | signed in in 8ms; token_type=DPoP; issued_token_type=urn:ietf:params:oauth:token-type:access_token; scope="openid profile"; act.sub=acme-sup… | PASS |
| H3 | Child App calls userinfo with a DPoP proof borrowed from the Super App | End-to-end: child receives the user's consented claims | 200 addressed to the Child App, with only the profile claims the user consented to (no email) | userinfo for Asha Devi: claims [aud birthdate exp gender iat iss jti name nbf picture sub] | PASS |
| A1 | Replay: the same app redeems the same device secret again | Diagram: mark device_secret jti as used | 400 invalid_grant | 400 invalid_grant: The device_secret cannot be redeemed | PASS |
| A2 | Stolen id_token + device_secret replayed from another device (attacker's own DPoP key) | Task 9.iii.4: cnf.jkt of the device secret must match the DPoP proof key | 400 invalid_grant | 400 invalid_grant: The DPoP proof key does not match the device_secret binding | PASS |
| A3 | Token exchange without a DPoP proof | Device secret is sender-constrained; proof of possession is mandatory | 400 invalid_dpop_proof | 400 invalid_dpop_proof: Invalid DPoP proof | PASS |
| A4 | Tampered device secret (one byte of the JWE ciphertext flipped) | Device secret is signed-then-encrypted (integrity + confidentiality) | 400 invalid_grant | 400 invalid_grant: Invalid actor_token | PASS |
| A5 | Mix-and-match: id_token from another SSO session presented with this device secret | Task 9.iii.1-2: ds_hash binding and sid validation | 400 invalid_grant | 400 invalid_grant: The subject_token is not bound to the device_secret | PASS |
| A6 | Scope escalation: child asks for a scope the Super App was never granted | Task 10.a: requested scope must be a subset of the Super App's scope | 400 invalid_scope | 400 invalid_scope: Requested scopes exceed the scopes granted to the original client: phone | PASS |
| A7 | device_sso requested on the token-exchange call | Task 10.b: device_sso is only valid on the authorization_code grant | 400 invalid_scope | 400 invalid_scope: The device_sso scope cannot be requested on token exchange | PASS |
| A8 | App of a different vendor (other rp_id), even though allow-listed | Diagram: allow-list check; SSO is limited to one vendor's app family | 400 invalid_grant | 400 invalid_grant: Invalid actor_token | PASS |
| A9 | Same vendor, but not in the Super App's native_sso_allowed_client_ids | Diagram: orig_client_id allow-list | 400 invalid_grant | 400 invalid_grant: Invalid actor_token | PASS |
| A10 | Rogue app signed with a different certificate binds the Super App's SSO service | Diagram: bindService is signature-permission protected | bind refused by the OS (key A and the bundle never leave the Super App) | SecurityException: caller does not hold the signature permission | PASS |
| A11 | Client not registered for device_sso asks for it at login | Task 2: allowed_authorization_scopes must include device_sso | 400 invalid_scope at the token endpoint | 400 invalid_scope: The client is not allowed to request the device_sso scope | PASS |
| C1 | Consent not covered: user declined email at Super App login, child asks for email | Consent reuse (NOCAPTURE) only when the child's claims are a subset of consented claims | 400 interaction_required (child must show its own consent screen) | 400 interaction_required: User consent is required for this client: email | PASS |
| C2 | Consent reuse is audited in consent_history | Diagram: append consent_history row (client_id=child, reused from Super App) | consent_history row with reused_from_client_id = Super App | row: acme-pharmacy-1792785711603927000\|acme-super-app-1792785711783796000\|["name","picture","gender","birthdate"] | PASS |
| R1 | Race: 100 concurrent redemptions of one device secret by the same app | Single-use redemption is atomic (no check-then-set window) | exactly 1 success, 99 rejected with invalid_grant | 1 succeeded, 99 rejected (invalid_grant), 0 other | PASS |
| R2 | Replay of a captured DPoP proof against userinfo | RFC 9449: each DPoP proof jti is single-use | first call 200, replayed proof 401 | first 200, replay 401 invalid_token | PASS |
| L2 | Logout cuts off a Child App token that was already issued | Extension: issued Native SSO tokens die with their SSO session (userinfo, introspection) | userinfo 200 before logout, 401 with the same token after logout | before logout 200, after logout 401 invalid_token | PASS |
| L1 | Global logout: Super App ends the SSO session, sibling apps can no longer sign in | Extension: session-level revocation (sid liveness, out of scope in the diagram) | logout 200, then token exchange 400 invalid_grant | logout ok, then 400 invalid_grant: Invalid actor_token | PASS |

## Mutation testing

Each mutant disables one security check, runs the unit tests of its repository, and expects a failure. `native-sso-lab/mutation/mutate.py` always restores the source afterwards.

| ID | Security property | Result | Caught by |
|---|---|---|---|
| M1 | DPoP key must equal the device secret's cnf.jkt | killed | `TestNativeSSOTestSuite/TestRejectsBindingMismatches/DPoP_key_is_not_the_device_key` |
| M2 | ID token must carry the device secret's ds_hash | killed | `TestNativeSSOTestSuite/TestRejectsBindingMismatches/ds_hash_of_another_device_secret` |
| M3 | ID token sid/sub/aud must match the device secret session | killed | `TestNativeSSOTestSuite/TestRejectsBindingMismatches/ID_token_of_another_session` |
| M4 | Original client must still be registered for device_sso | killed | `TestNativeSSOTestSuite/TestRejectsWhenOriginalClientLostDeviceSSO` |
| M5 | Requested scopes must be a subset of the original grant | killed | `TestNativeSSOTestSuite/TestEnforcesScopeRules/scope_not_granted_to_the_original_client` |
| M6 | device_sso cannot be requested on token exchange | killed | `TestNativeSSOTestSuite/TestValidateGrantNativeSSOShape/device_sso_requested` |
| M7 | A redemption must be claimed before a token is issued | killed | `TestNativeSSOTestSuite/TestChildAttributesRequireInteractionWhenUnavailable` |
| M8 | Tokens die with their SSO session | killed | `TestNativeSSOTestSuite/TestSessionEnforcement/ended_session_revokes_its_tokens` |
| M9 | Only clients registered for device_sso can request it | killed | `TestNativeSSOTestSuite/TestValidateDeviceSSORequest/client_not_registered_for_device_sso` |
| M10 | Apps of another vendor (rp_id) cannot redeem | killed | `TestDeviceSecretProviderTestSuite/TestRedemptionAllowList/foreign` |
| M11 | Only allow-listed apps can redeem | killed | `TestDeviceSecretProviderTestSuite/TestRedemptionAllowList/unlisted` |
| M12 | Each app redeems a device secret at most once | killed | `TestDeviceSecretProviderTestSuite/TestRedeemOncePerClient` |
| M13 | Expired device secrets are rejected | killed | `TestDeviceSecretProviderTestSuite/TestValidateRejectsForgedOrExpiredSecrets/expired` |
| M14 | Only device secrets (token_use) are accepted | killed | `TestDeviceSecretProviderTestSuite/TestValidateRejectsSecretsOfAnotherIssuer` |
| M15 | A device secret of an ended or replaced session is rejected | killed | `TestDeviceSecretProviderTestSuite/TestNewSessionSupersedesOldDeviceSecret` |
| M16 | A child only receives claims the user accepted | killed | `TestNativeSSOConsentProviderTestSuite/TestResolve` |

## Unit tests and fuzzing

| Suite | Location | Cases |
|---|---|---|
| ThunderID Native SSO | `thunderid/backend/internal/oauth/oauth2/granthandlers/native_sso_test.go` | 57 |
| eSignet device secret provider | `esignet/esignet-service/internal/engine/device_secret_provider_test.go` | part of 43 |
| eSignet consent reuse | `esignet/esignet-service/internal/engine/native_sso_consent_provider_test.go` | part of 43 |
| Android JOSE helpers | `native-sso-android/sso-core/src/test/.../JoseTest.kt` | 5 (DER→JOSE round trip, RFC 7638 thumbprints, fixed-width coordinates) |
| Fuzz: scope invariants | `FuzzNativeSSOScopes` | ~1.2 M inputs |
| Fuzz: device secret forgery | `FuzzValidateDeviceSecret` | ~2 M inputs |

The existing ThunderID OAuth/engine suites and the full eSignet suite still pass with the changes.

## Postman

`native-sso-lab/postman/run-native-sso.sh` runs the collection's own scripts (newman's sandbox lacks Web Crypto): create the Super App and the child, enable `device_sso`, run the FAPI 2.0 OTP login, then the Native SSO folder. All 18 steps pass, including replay → `invalid_grant` and userinfo after logout → 401.

## Android, on an Android 15 emulator

| Check | Result | Screenshot |
|---|---|---|
| Super App login through the real eSignet UI in a Chrome Custom Tab (PAR, DPoP, PKCE, OTP, consent) | signed in, device secret stored encrypted | `screenshots/01`–`03` |
| Pharmacy silent SSO | token in 22–38 ms; `cnf.jkt` = key A; `act.sub` = Super App; userinfo addressed to Pharmacy, no email | `screenshots/04` |
| Pharmacy asks for email, which was declined at Super App login | `interaction_required: … email`, consent prompt shown | `screenshots/05` |
| Bonus re-consent | Pharmacy's own eSignet login with `prompt=consent`; email released after the user allows it | `screenshots/06` |
| Log out of all Acme apps | Pharmacy's existing token: userinfo 401 | `screenshots/07` |
| Rogue build signed with another certificate | Android refuses the bind to the SSO service | `screenshots/08` |

