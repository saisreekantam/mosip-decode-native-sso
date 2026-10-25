# Submission checklist (MOSIP Decode 2026)

Mandatory items from the submission guidelines, and where each one is:

| Guideline item | Status | Where |
|---|---|---|
| Source code (hosted, organised, commented, everything needed to run) | ready to push | this repository + the `thunderid` and `esignet` forks (branch `feature/native-sso`) |
| Working prototype / demo | done | Android apps (`native-sso-android`), eSignet stack (`scripts/dev-up.sh`), SSO Guardian dashboard |
| Pitch presentation / slide deck | outline below | build from the outline (10 slides) |
| Video demo (2–5 min) | script ready | [demo-script.md](demo-script.md); record and upload |
| Usage and installation instructions | done | [README](../README.md#run-it), [native-sso-android/README.md](../native-sso-android/README.md), [native-sso-lab/README.md](../native-sso-lab/README.md) |
| API documentation (mandatory if applicable) | done | [design.md → API changes](design.md#api-changes), Postman collection in the eSignet fork (`postman-collection/`, folder **Native SSO**) |
| Git repository with version history | commits on `feature/native-sso` in both forks + this repo | push all three |
| College / university ID | your action | upload with the other deliverables |

Optional items, also included:

| Item | Where |
|---|---|
| Design mockups / UI | `docs/screenshots/` (the running apps, the eSignet UI, the dashboard) |
| Architecture diagram | [README](../README.md#architecture), sequence diagrams in [design.md](design.md) |
| Test data | [testing.md → Test data](testing.md#test-data) |
| Test cases and results | [testing.md](testing.md), `e2e-results.json`, `mutation-report.json` |

## Before sending

1. Create the GitHub repositories (forks of `thunder-id/thunderid` and `mosip/esignet`, plus this repository) and push the `feature/native-sso` branches.
2. In `esignet/esignet-service/go.mod`, replace the local `replace` (`../../thunderid/backend`) with the pushed fork: `go mod edit -replace github.com/thunder-id/thunderid=github.com/<you>/thunderid/backend@<commit>` then `go mod tidy`. Alternatively, keep the local replace and point judges to `scripts/setup.sh`, which recreates the side-by-side layout.
3. Set `THUNDERID_REPO` / `ESIGNET_REPO` defaults in `scripts/setup.sh` to your fork URLs.
4. Upload the video, deck and college ID to a Google Drive folder, shared as "anyone with the link can view".

## Email to decode@mosip.io

```
Subject: MOSIP Decode 2026 submission: TechTitans, PS03 Native SSO in eSignet Go

Team name: TechTitans
Team members: Sai Venkat, Revanth, Aditya, Ashok
Problem statement addressed: 03 eSignet | Enable Native SSO in eSignet Go for Mobile Apps

Source code:
  Submission repository (apps, test lab, docs): https://github.com/saisreekantam/mosip-decode-native-sso
  ThunderID fork, branch feature/native-sso:     https://github.com/saisreekantam/thunderid/tree/feature/native-sso
  eSignet fork, branch feature/native-sso:       https://github.com/saisreekantam/esignet/tree/feature/native-sso
Demo video:      <Drive URL>
Pitch deck:      <Drive URL>
College IDs:     <Drive URL>
Docs:            https://github.com/saisreekantam/mosip-decode-native-sso/blob/main/README.md (installation), docs/design.md (design + API), docs/testing.md (tests)
```

## Pitch deck outline (10 slides)

1. **Title.** Native SSO for eSignet Go: sign in once, use every app. Team, PS03.
2. **Problem.** Mobile apps share no cookies, so users re-authenticate (OTP plus consent) in every app of the same vendor. That means friction, drop-off, and repeated sharing of personal data.
3. **Solution in one picture.** Super App signs in → device secret bound to a hardware key → sibling app signs in silently via a signature-protected app-to-app channel. About 20 ms, no OTP.
4. **How it works.** The two sequence diagrams (login and token exchange), `ds_hash`/`sid` binding, DPoP key A.
5. **What we built.** The ThunderID engine (protocol), eSignet (device secret, sessions, consent, logout), two Android apps, a Postman collection; the 10 tasks plus the bonus done.
6. **Security by design.** Signed-then-encrypted device secret, DPoP on both legs, vendor allow-list (same `rp_id`), atomic single-use redemption, consent reuse only within what the user granted, an audit trail.
7. **Beyond the spec.** Logout cuts off already-issued tokens; per-app userinfo (the leak we found in our first version and fixed); a re-consent fallback.
8. **Proof.** The SSO Guardian dashboard; 20/20 live scenarios, 16/16 mutants, 100 tests, ~3 M fuzz inputs, 18/18 Postman steps, on-device screenshots.
9. **Impact and extension.** Works for any MOSIP deployment's app families (health, social protection, banking); config-only enablement per client; the next steps are iOS (Secure Enclave and an app group), Play Integrity attestation of key A, and refresh tokens for sibling apps.
10. **Thank you.** Links to repos, the video and the docs.
