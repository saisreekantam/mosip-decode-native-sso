# Native SSO Android apps

Two apps from the same vendor ("Acme Health") that show OpenID Connect Native SSO against eSignet Go.

| Module | Package | Role |
|---|---|---|
| `sso-core` | `com.acme.sso.core` | Shared library: Keystore keys (`KeystoreKey`), JOSE helpers, eSignet client (PAR, token, token exchange, userinfo, logout), Keystore-encrypted storage, the bound-service protocol, the login launcher (Custom Tab, falling back to a WebView) |
| `super-app` | `com.acme.superapp` | Signs in with eSignet (`openid profile email device_sso`; PAR + PKCE + DPoP), stores the ID token and device secret encrypted, and exposes `SsoService`, guarded by the signature permission `com.acme.permission.NATIVE_SSO` and a per-message caller-signature check |
| `child-app` | `com.acme.pharmacy` | Signs in silently through the Super App (token exchange with a DPoP proof signed by key A), shows each trust step, falls back to its own consent login on `interaction_required` |
| `child-app`, build type `rogue` | `com.acme.pharmacy.rogue` | The same app signed with another certificate: Android refuses the bind |

## Security model on the device

- **Key A** (EC P-256, StrongBox when available, else the TEE) is generated in the Android Keystore and never exported. Sibling apps only receive signatures (DPoP proofs) made with it.
- The **client key** (RSA-2048, PS256 `private_key_jwt`) is also Keystore-bound. Each install registers itself with eSignet's client-management API (local development provisioning). The client id ends with the key thumbprint, because eSignet keeps a registered public key immutable.
- The **ID token and device secret** are stored with AES-GCM under a Keystore key.
- **App-to-app:** the OS lets only apps signed with the vendor certificate bind `SsoService`, and the service re-checks `checkSignatures` for every message. On first bind, the Super App adds the sibling to its eSignet allow-list (`native_sso_allowed_client_ids`); this stands in for vendor onboarding.

## Build and run

```bash
export JAVA_HOME=<JDK 17>
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :sso-core:testDebugUnitTest :super-app:installDebug :child-app:installDebug
./gradlew :child-app:installRogue          # optional: the lookalike app
adb reverse tcp:8080 tcp:8080; adb reverse tcp:3000 tcp:3000; adb reverse tcp:8082 tcp:8082
```

The apps talk to `http://localhost:8080` (the eSignet issuer); `adb reverse` maps it to the host running `scripts/dev-up.sh`. To use another issuer, change `esignetIssuer` in `gradle.properties`. Cleartext HTTP is allowed only for `localhost` (`res/xml/native_sso_network_security.xml`), for local development.
