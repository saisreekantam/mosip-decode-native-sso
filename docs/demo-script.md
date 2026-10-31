# Demo video script (about 4 minutes)

> **Recorded with v1.0.0.** The counts below (20/20, 16/16) and the screens are those of v1.0.0. v1.1.0 has 21/21 scenarios and 17/17 mutants and refreshed screens with the same buttons; see the README section "Since the demo video".

Record the emulator screen with `adb shell screenrecord /sdcard/demo.mp4` (3 minutes maximum per file; record each scene separately), and the dashboard with any screen recorder. Before recording, run `scripts/dev-up.sh`, install both apps, and do one Super App login so its client is registered.

| Time | Screen | Say |
|---|---|---|
| 0:00–0:20 | Title slide | "Mobile users sign in to every app of a vendor separately, even when all of them trust the same eSignet identity. We implemented OpenID Native SSO in eSignet Go: sign in once, and the vendor's other apps sign you in silently and securely." |
| 0:20–1:10 | Super App: *Create demo user* → *Sign in with eSignet* → eSignet UI: UIN, OTP 111111, consent (leave Email off) → back in the app | "The Super App uses PAR, PKCE and DPoP with a hardware-backed key. eSignet returns, beside the usual tokens, a device secret: a signed-then-encrypted token bound to that key. The ID token is bound to it through `ds_hash` and `sid`." |
| 1:10–1:50 | Pharmacy: *Continue with your Acme account* → profile and the trust trace | "Pharmacy has never logged in. It binds the Super App's SSO service (Android only allows apps signed by Acme), gets the bundle and a DPoP proof signed by the Super App's key, which never leaves it, and exchanges them. About 20 ms, no OTP, no consent screen. The token is bound to the same device key, and userinfo is issued to Pharmacy with only what the user agreed to share." |
| 1:50–2:30 | Pharmacy: choose *profile + email* → consent card → *Review and consent* (optional) | "The user didn't share email with the Super App, so eSignet refuses to reuse that consent and Pharmacy asks for it itself: the bonus re-consent flow." |
| 2:30–3:00 | Super App: *Log out of all Acme apps* → Pharmacy: *Refresh profile* → 401 | "One tap ends the SSO session for the whole family, including tokens already issued. That goes beyond the spec, which leaves session liveness out of scope." |
| 3:00–3:15 | Install the rogue build → *Continue* → "Android refused to connect" | "A lookalike app signed by someone else cannot even reach the SSO service." |
| 3:15–4:00 | SSO Guardian dashboard: *Run all scenarios* (20/20), open A2 and R1, show the mutation table (16/16) | "Every security property is tested against the live server: stolen device secrets, tampered secrets, mixed sessions, scope escalation, other vendors, 100 concurrent replays, DPoP proof replay. Mutation testing shows each check is guarded: disable any one and a test fails." |
| 4:00–4:10 | Closing slide: repos and results | "Everything is open source, documented, and runs with two scripts." |
