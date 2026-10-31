# Conformance and threat model

This page maps every rule of the problem statement (PS-03) and of [OpenID Connect Native SSO for Mobile Apps 1.0](https://openid.net/specs/openid-connect-native-sso-1_0.html) to the code that implements it and the test that proves it. Where we deviate on purpose, the reason and the configuration switch are given. The second half is a STRIDE threat model.

Test IDs:

- **H, A, C, R, S, L:** live end-to-end scenarios in `native-sso-lab` ([results](testing.md#end-to-end-scenarios-native-sso-lab)).
- **M:** mutants. Each one disables a check and must make a unit test fail ([table](testing.md#mutation-testing)).
- **Unit tests:** `TS/…` is ThunderID `granthandlers/native_sso_test.go`; `DSP/…` is eSignet `engine/device_secret_provider_test.go`; `CP/…` is eSignet `engine/native_sso_consent_provider_test.go`.

## 1. Conformance table

Legend: ✅ follows, ➕ follows and goes further, ⚠️ deliberate deviation.

### Authorization request and code grant (first app)

| # | Rule | Source | Where implemented | Status | Reason / config switch | Proving test |
|---|---|---|---|---|---|---|
| 1 | The client requests SSO with the `device_sso` scope on the authorization request | Spec §3.1; PS task 1 | ThunderID `splitDeviceSSOScope`; classified as an OIDC protocol scope in `utils.SeparateOIDCAndNonOIDCScopes` | ✅ | `native_sso.enabled` (`MOSIP_ESIGNET_NATIVE_SSO_ENABLED`) | H1; `TS/TestSplitDeviceSSOScope` |
| 2 | Only clients allowed `device_sso` get it | PS task 2 | `validateDeviceSSORequest` checks `additional_config.allowed_authorization_scopes` | ✅ | per client: `allowed_authorization_scopes: ["device_sso"]` | A11; `TS/TestValidateDeviceSSORequest`; M9 |
| 3 | `device_sso` only in an OpenID Connect request | Spec §3.1 | `validateDeviceSSORequest` (requires `openid`) | ✅ | | `TS/TestValidateDeviceSSORequest/not_an_OpenID_Connect_request` |
| 4 | The device secret is bound to the device | Spec §6 (security) | `device_sso` refused without a DPoP proof; `cnf.jkt` stored in the secret | ➕ | DPoP is mandatory, not optional | `TS/TestValidateDeviceSSORequest/no_DPoP_proof_to_bind_to` |
| 5 | A Device Secret Provider interface is exposed to the engine | PS task 3 | `providers.DeviceSecretProvider`, `thunderidengine.WithDeviceSecretProvider` | ✅ | | compile-time; `DSP/*` |
| 6 | The device secret is a signed JWT with `cnf.jkt`, `auth_id`, `exp`, `client_id`, `scope`, `acr`, `claims`, `iss` | PS task 4 | eSignet `GenerateDeviceSecret` | ➕ | also signed then **encrypted** (RSA-OAEP JWE, key `NATIVE_SSO`), so it is opaque to apps; `native_sso.encryption_reference_id` | H1; `DSP/TestRoundTrip`; A4 |
| 7 | The token endpoint generates the secret | PS task 5 | `issueDeviceSecret` in the authorization code grant | ✅ | | H1; `TS/TestIssueDeviceSecretBindsIDTokenAndExtendsAttributes`, `TS/TestIssueDeviceSecretFailsClosed` |
| 8 | The token response returns `access_token`, `id_token` and `device_secret` | Spec §3.2; PS task 6 | `model.TokenResponse.DeviceSecret` | ✅ | | H1 |
| 9 | The ID token carries `sid` | Spec §3.3 | `sid` = eSignet `auth_id` | ✅ | | H1, A5 |
| 10 | The ID token carries `ds_hash` | Spec §3.3 | `deviceSecretHash`: base64url of the left half of SHA-256 | ⚠️ minor | The spec uses the hash of the ID token's `alg` (as for `at_hash`). eSignet signs ID tokens with RS256/PS256, so the result is identical. Only a SHA-384/512 `alg` would differ, and eSignet does not configure one. | `TS/TestDeviceSecretHashIsLeftHalfOfSHA256`; A5; M2 |
| 11 | The ID token must stay presentable by a sibling app | Spec §4 (implied) | `device_sso` refused for clients with `id_token_response_type: JWE` | ➕ | an encrypted ID token could only be read by the first app | `TS/TestValidateDeviceSSORequest/encrypted_ID_tokens` |

### Token exchange (second app)

| # | Rule | Source | Where implemented | Status | Reason / config switch | Proving test |
|---|---|---|---|---|---|---|
| 12 | The second app gets `id_token`, `device_secret` and a DPoP proof over an app-to-app channel | PS task 7 | Android `SsoService` (Super App, signature-permission bound service) + `SsoConnection` (Pharmacy); lab model `device.go` | ➕ | key A never leaves the Super App; it signs proofs for siblings | H2; A10 |
| 13 | `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token` = ID token, `subject_token_type=…:id_token` | Spec §4.1; PS task 8 | `validateNativeSSOExchangeRequest` | ✅ | grant must be registered for the client and in `oauth.allowed_grant_types` | H2; `TS/TestValidateGrantNativeSSOShape` |
| 14 | `actor_token` = device secret, with a device-secret `actor_token_type` | Spec §4.1; PS task 9.i | constant `TokenTypeIdentifierDeviceSecret` | ⚠️ | We use `urn:openid:params:token-type:device-secret` as the MOSIP problem statement specifies. The spec's registered value is `urn:x-oath:params:oauth:token-type:device-secret`. Accepting it as an alias is a one-line change. | H2; `TS/TestValidateGrantNativeSSOShape/device_secret_as_subject_token` |
| 15 | The spec's `audience` parameter | Spec §4.1 | not enforced | ⚠️ minor | The token endpoint is the only possible audience, so we don't require the parameter. The issued token's `aud` comes from RFC 8707 `resource` / the default resource server. | H2 |
| 16 | Validate the device secret | Spec §4.2; PS task 9.ii | eSignet `ValidateDeviceSecret`: decrypt, verify ES256, `token_use`, `iss`, `exp`, session alive | ✅ | | A4; `DSP/TestValidateRejectsForgedOrExpiredSecrets`, `DSP/TestValidateRejectsSecretsOfAnotherIssuer`; fuzz `FuzzValidateDeviceSecret`; M13, M14 |
| 17 | Validate the ID token's signature and issuer | Spec §4.2 | `verifyNativeSSOIDToken` | ✅ | refresh tokens (same `typ`) are refused too | `TS/TestRejectsInvalidIDTokens` |
| 18 | ID token expiry | Spec §4.2 | `exp` not enforced | ⚠️ | eSignet ID tokens live 120 s, but the SSO session lasts as long as the device secret (24 h). The secret's `exp` and the server-side session decide; the ID token is still checked for signature, issuer, `ds_hash`, `sid`, `sub` and `aud`. | S1 (re-sign-in after 2 min uses the original ID token) |
| 19 | `ds_hash` in the ID token matches the device secret | Spec §4.2; PS task 9.iii.1 | constant-time compare in `handleNativeSSOGrant` | ✅ | | A5; `TS/TestRejectsBindingMismatches/ds_hash_of_another_device_secret`; M2 |
| 20 | `sid` of the ID token matches the device secret's session | Spec §4.2; PS task 9.iii.2 | `handleNativeSSOGrant` (also `sub`, `aud`) | ➕ | `sub` and `aud` checked too | A5; `TS/TestRejectsBindingMismatches/*`; M3 |
| 21 | The original client is still allowed `device_sso` | PS task 9.iii.3 | `handleNativeSSOGrant` re-reads the original client | ✅ | | `TS/TestRejectsWhenOriginalClientLostDeviceSSO`; M4 |
| 22 | DPoP key thumbprint equals the device secret's `cnf.jkt` | PS task 9.iii.4 | constant-time compare in `handleNativeSSOGrant`; DPoP mandatory | ✅ | | A2, A3; `TS/TestRejectsBindingMismatches/DPoP_key_is_not_the_device_key`; M1 |
| 23 | Requested scopes are a subset of the original grant | PS task 10.a | `nativeSSOScopes` | ➕ | strict: an excess scope fails with `invalid_scope` rather than being silently dropped as plain RFC 8693 allows | A6; `TS/TestEnforcesScopeRules`; fuzz `FuzzNativeSSOScopes`; M5 |
| 24 | `device_sso` cannot be requested on token exchange | PS task 10.b | `validateNativeSSOExchangeRequest` | ✅ | | A7; `TS/TestValidateGrantNativeSSOShape/device_sso_requested`; M6 |
| 25 | Only apps of the same vendor may use the secret | Spec §6 (policy left to the AS); PS diagram "allow-list" | eSignet `authorizeRedemption`: same `rp_id` **and** listed in `native_sso_allowed_client_ids` | ➕ | per client: `additional_config.native_sso_allowed_client_ids` | A8, A9; `DSP/TestRedemptionAllowList`; M10, M11 |
| 26 | Mark the device secret as used | PS diagram | eSignet `MarkDeviceSecretUsed`: atomic `PutIfNotExists` on `jti\|client_id` | ⚠️ | The default `once_per_token_lifetime` allows one live token per app per session: the lock lasts as long as the access token. A replay while it is live fails, and after expiry the app signs in again silently. DPoP binding (rule 22) already stops use from another device. `native_sso.redemption_policy` (`MOSIP_ESIGNET_NATIVE_SSO_REDEMPTION_POLICY`): `once_per_token_lifetime` (default), `once_per_client` (the literal reading of the diagram), `reusable`. | A1, R1, S1; `DSP/TestOncePerTokenLifetimeAllowsSignInAgainAfterExpiry`, `DSP/TestRedeemOncePerClient`; M7, M12, M17 |
| 27 | Consent reuse: the child gets the original consent only when it covers the request | PS diagram; bonus task | `NativeSSOConsentProvider` (resolve has no side effects; record runs after issuance) | ✅ | Not covered → `interaction_required`; the app then runs its own login with `prompt=consent`. | C1; `CP/TestResolve`; M16; Android screenshots 05–06 |
| 28 | Append a `consent_history` row for the child | PS diagram | `RecordNativeSSOConsent`; new column `reused_from_client_id` | ➕ | migration `2.0.0_to_2.1.0_upgrade.sql` | C2 |
| 29 | The second app receives an access token | Spec §4.3 | `buildNativeSSOAccessToken`: `client_id` = child, `cnf.jkt` = key A, `act.sub` = first app, `issued_token_type=…:access_token` | ✅ | | H2 |
| 30 | The child sees only its own consented claims | (extension) | per-child attribute set; userinfo addressed to the child | ➕ | | H3; `TS/TestChildAttributesFilterPlainAttributes` |
| 31 | `sid` liveness (out of scope in the PS diagram) | PS diagram note | server-side SSO session; `POST /native-sso/logout`; revocation hook `IsSessionActive` | ➕ | | L1, L2; `TS/TestSessionEnforcement`; M8, M15 |
| 32 | The exchange may return a new `device_secret` / refresh token | Spec §4.3 (optional) | not issued | ✅ (optional) | The first app keeps the only device secret; a sibling signs in again through it (S1). | — |

## 2. STRIDE threat model

**Assets:** the device secret, key A (the Super App's DPoP key), access tokens, the user's consent decisions, and the SSO session.

**Trust boundaries:**

- app ↔ app on the phone (bound service);
- phone ↔ eSignet (TLS);
- eSignet ↔ its store (Postgres, Redis).

| STRIDE | Threat | Mitigation | Test |
|---|---|---|---|
| **S**poofing | A stolen `id_token` + `device_secret` used from another device | Device secret bound to key A (`cnf.jkt`); DPoP proof required and compared to it; key A non-exportable in Android Keystore | A2, A3; M1 |
| Spoofing | A malicious app on the same phone pretends to be a sibling | Bound service protected by a signature permission (same signing certificate); key A never leaves the Super App | A10; Android screenshot 08 |
| Spoofing | An app of another vendor, or an unlisted app of the same vendor, redeems the secret | Same `rp_id` **and** the original client's allow-list | A8, A9; M10, M11 |
| Spoofing | A forged device secret, or another esignet JWT passed off as one | Signed (ES256) then encrypted (JWE); `token_use`, `iss`, `jti` checked | A4; fuzz `FuzzValidateDeviceSecret`; M14 |
| **T**ampering | A modified device secret or ID token | JWE integrity + JWS signature; ID token signature and issuer verified | A4; `TS/TestRejectsInvalidIDTokens` |
| Tampering | Mix-and-match: an ID token from another session or user with this secret | `ds_hash` (constant time), `sid`, `sub`, `aud` must match | A5; M2, M3 |
| Tampering | Scope escalation through the exchange | Strict subset rule; `device_sso` refused on exchange | A6, A7; M5, M6 |
| **R**epudiation | A sibling app's silent sign-in leaves no trace of whose consent was used | `consent_history` row per silent sign-in with `reused_from_client_id`; structured logs for issue, redeem, replay and logout | C2 |
| **I**nformation disclosure | The child reads claims the user never consented to share with it | Per-child attribute set = registered ∩ accepted ∩ scope claims; userinfo addressed to the child | H3, C1; M16 |
| Information disclosure | Device secret contents (`sub`, claims) readable by apps | Encrypted to a server-only key; opaque to clients | H1 (5-part JWE) |
| Information disclosure | Secrets leak through test reports | The dashboard and reports never record tokens, secrets or proofs | `native-sso-lab/README.md` |
| **D**enial of service | A flood of concurrent redemptions causes double issuance or a race | Atomic `PutIfNotExists` claim; lock in the shared store (works across instances) | R1 (100 concurrent, exactly 1 success); scale test |
| Denial of service | A legitimate app is locked out after its token expires | `once_per_token_lifetime` releases the lock when the token expires | S1; M17 |
| **E**levation of privilege | A replayed device secret or DPoP proof obtains another token | One live token per app per session; DPoP `jti` single-use | A1, R2; M7, M12 |
| Elevation of privilege | Tokens outlive logout | Child tokens carry `native-sso:<sid>`; revocation hook checks session liveness on every use | L1, L2; M8, M15 |
| Elevation of privilege | The original client loses `device_sso`, but its old secrets still work | The original client's registration is re-checked on every exchange | `TS/TestRejectsWhenOriginalClientLostDeviceSSO`; M4 |

### Residual risks and assumptions

- **A rooted or compromised phone** can drive the Super App's service directly. This is out of scope, as it is for every on-device SSO. Key A stays in the TEE/StrongBox, so it still cannot be exported.
- **Weaker policies.** With `redemption_policy: reusable`, replay protection rests on DPoP alone (A2, R2). Use it only where that is acceptable.
- **A shared store is required for scale-out.** With more than one eSignet instance, `MOSIP_ESIGNET_CACHE_TYPE=redis` is mandatory: the redemption lock, SSO sessions and DPoP `jti` cache must be shared. See [testing.md](testing.md#scales-horizontally).
