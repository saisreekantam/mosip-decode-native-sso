# Native SSO on iOS

The problem statement lists iOS (Swift / Objective-C) next to Android. This document shows how the Android solution carries over to iOS. It is a design only; no iOS app is built for this submission.

## Goal

The same two flows as on Android ([design.md](design.md)), on iOS:

1. **First login.** The Acme Super App signs in with eSignet (PAR + PKCE + DPoP, OTP and consent) and receives `access_token`, `id_token` (with `ds_hash` and `sid`) and `device_secret`.
2. **Silent sign-in.** Acme Pharmacy signs in with no OTP and no consent screen, through the token exchange with the Super App's ID token, the device secret and a DPoP proof signed by key A.

**The eSignet server needs no change.** It sees the same HTTP requests whichever OS the client runs on: the same PAR, token and token-exchange calls, the same DPoP proofs, the same `private_key_jwt`. Only data changes: the iOS apps are registered as clients (under the same `relyingPartyId`, with their own redirect URIs), and the Pharmacy client id goes on the Super App's `native_sso_allowed_client_ids`.

## Android → iOS mapping

| Android (what we built) | iOS |
|---|---|
| Android Keystore / StrongBox key (key A), EC P-256, not exportable | Secure Enclave P-256 key: `SecKeyCreateRandomKey` with `kSecAttrTokenID = kSecAttrTokenIDSecureEnclave`, `kSecAttrIsPermanent`, and `kSecAttrAccessGroup` set to the shared group |
| Signature-protected bound service (`SsoService`, permission `com.acme.permission.NATIVE_SSO`, `checkSignatures` per message) | Shared **Keychain access group** `<TeamID>.com.acme.sso`: only apps signed by the same Apple Team ID can read it |
| `SsoService` signs DPoP proofs for siblings (`MSG_SIGN_DPOP`); key A never leaves the Super App | Siblings sign with key A directly: they look the key up in the shared access group and call `SecKeyCreateSignature`. Key A still never leaves the Secure Enclave |
| `MSG_GET_BUNDLE` returns `id_token` + `device_secret` | Siblings read the two keychain items from the shared access group |
| Chrome Custom Tab login (`LoginLauncher`), WebView fallback | `ASWebAuthenticationSession`, with the redirect URI as `callbackURLScheme` (or an `https` universal-link callback on iOS 17.4+) |
| Keystore-encrypted storage (AES-GCM under a Keystore key) | Keychain items (`kSecClassGenericPassword`) with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`: encrypted by the OS, never backed up or synced to iCloud |
| Client key: RSA-2048 in the Keystore, PS256 `private_key_jwt` | RSA-2048 key in the app's own (unshared) keychain group, PS256. The Secure Enclave holds only P-256 keys, so this key is software-backed but `ThisDeviceOnly`. If eSignet client registration accepts EC keys, an ES256 client key in the Secure Enclave is better |
| Connected apps list (`recordUse` in the Super App) | A small record in a shared App Group container (`group.com.acme.sso`) that each app updates when it signs in |

On iOS the sibling app does not need the Super App to be running: everything it needs is in the shared keychain. The Android sibling binds a service, which starts the Super App's process.

### What the Pharmacy app does on iOS

```text
1. SecItemCopyMatching(access group <TeamID>.com.acme.sso)  → id_token, device_secret, key A
   (errSecItemNotFound → "Sign in to the Acme Super App first")
2. Build the DPoP proof for POST /oauth2/token and sign it with key A
3. POST /oauth2/token  grant_type=token-exchange, subject_token=id_token,
                       actor_token=device_secret, actor_token_type=…device-secret, DPoP
4. GET /oauth2/userinfo with a new DPoP proof (ath = hash of the access token)
5. interaction_required → ASWebAuthenticationSession login with prompt=consent (same fallback as Android)
```

## Trust boundary

| | Android | iOS |
|---|---|---|
| Who is trusted | Apps signed with the **same signing certificate** | Apps signed under the **same Apple Team ID** |
| Who enforces it | The OS refuses `bindService` without the signature permission, and `SsoService` re-checks `checkSignatures` | The OS gives an app only the keychain access groups in its `keychain-access-groups` entitlement. Apple puts that entitlement in the code signature, and a provisioning profile only allows groups that start with the developer's own Team ID |

**Why both stop a lookalike app.** A rogue "Acme Pharmacy" can copy the name, the icon and even the package or bundle id on a test device. It cannot copy the signing identity:

- On Android it has no copy of Acme's private signing key, so its certificate does not match and the bind is refused (the `rogue` build shows this).
- On iOS it cannot get a provisioning profile for Acme's Team ID, because Apple issues those only to Acme's developer account. A profile under any other team cannot grant `<AcmeTeamID>.com.acme.sso`, so its keychain query returns `errSecItemNotFound`. It can neither read the device secret nor sign with key A.

In both cases the check is on a cryptographic identity, not on a name the attacker chooses. Even if the device secret leaked, it is useless without key A (`cnf.jkt`), and key A cannot be exported from the Secure Enclave.

## Differences and risks

- **No Secure Enclave in the iOS Simulator.** Key A can only be created on a real iPhone. For simulator builds, use a software P-256 key in the keychain behind `#if targetEnvironment(simulator)`, clearly labelled and never in a release build. The trust-boundary and rogue-app tests must run on a device.
- **Keys cannot be exported.** This is the point, but it means a restored backup or a new phone gets no key A. The old device secret then fails the `cnf.jkt` check and the user signs in again. That matches Android.
- **Entitlements must be exactly right.** Both apps need the same group in `keychain-access-groups`, and key A and the two tokens must be created with an explicit `kSecAttrAccessGroup`. Without it they go to the app's default group and siblings cannot see them. Both apps also need the App Group for the Connected apps list.
- **No mediator, so per-app sign-out is weaker.** On Android every DPoP proof goes through `SsoService`, so the Super App can refuse an app the user signed out (`app_signed_out`) and the cut-off is immediate. On iOS siblings sign by themselves. The Super App can write a "signed out" flag that sibling apps honour (they are the vendor's own code), and remove the app from `native_sso_allowed_client_ids` through the existing client-management API. eSignet then refuses its next exchange, and its current token runs out within 120 s. *Log out of all Acme apps* (`POST /native-sso/logout`) works the same as on Android.
- **Enrollment.** On Android, the first bind (`MSG_ENROLL`) makes the Super App add the sibling to its allow-list. On iOS the sibling can't call into the Super App, so the sibling's client id is put on the allow-list at vendor onboarding (which is what production should do anyway). For local development, the sibling can leave an enrollment request in the App Group for the Super App to apply the next time it opens.
- **Keychain items survive an uninstall.** If the user deletes the Super App, the shared items stay, and siblings could keep signing in until the device secret expires (24 h). On its first launch the Super App should clear any old items (a flag in `UserDefaults`, which an uninstall does delete). Siblings can also check `canOpenURL` for the Super App's URL scheme before they use the bundle.
- **Signature format.** `SecKeyCreateSignature` with `.ecdsaSignatureMessageX962SHA256` returns a DER signature. JWS ES256 needs the raw 64-byte `r‖s`, so convert it before building a DPoP proof. This is the most common bug when moving from Android.
- **Locked device.** With `WhenUnlockedThisDeviceOnly` a sibling can't sign in silently while the phone is locked. That is fine, because sign-in happens when the user opens the app.
- **Login sheet prompt.** `ASWebAuthenticationSession` asks "*Acme wants to use esignet… to sign in*". Setting `prefersEphemeralWebBrowserSession = true` removes the prompt, and Native SSO doesn't need browser cookies anyway.

## Effort estimate

About **1.5 to 2 days** for one developer who knows SwiftUI, with a physical iPhone and an Apple Developer team:

| Work | Time |
|---|---|
| Shared Swift package `AcmeSSO`: Secure Enclave key, JOSE (JWK thumbprint, DPoP, PS256 assertion, DER→raw), eSignet client (PAR, token, token exchange, userinfo, logout), keychain store | 0.75 day |
| SwiftUI Super App: login with `ASWebAuthenticationSession`, signed-in screen, Connected apps, logout | 0.5 day |
| SwiftUI Pharmacy: silent sign-in, trust trace, consent fallback | 0.5 day |
| Client registration, device testing, a "rogue" build under a different Team ID | 0.25 day |

The screens and the button labels can follow the Android apps exactly, so the demo script carries over unchanged.
