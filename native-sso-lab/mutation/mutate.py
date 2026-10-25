#!/usr/bin/env python3
"""Security mutation testing for Native SSO.

Each mutant disables one security check in the ThunderID or eSignet source, runs that repo's
Native SSO unit tests, and expects them to fail ("killed"). A mutant that survives means a
security property is not guarded by any test. Sources are always restored, even on Ctrl-C.

Usage: python3 mutation/mutate.py [--report mutation-report.json]
"""
import json
import os
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
THUNDER = os.path.join(ROOT, "thunderid", "backend")
ESIGNET = os.path.join(ROOT, "esignet", "esignet-service")
GRANT = "internal/oauth/oauth2/granthandlers/native_sso.go"
DSP = "internal/engine/device_secret_provider.go"
CONSENT = "internal/engine/native_sso_consent_provider.go"

TESTS = {
    THUNDER: ["go", "test", "./internal/oauth/oauth2/granthandlers/", "-run", "TestNativeSSOTestSuite", "-count=1"],
    ESIGNET: ["go", "test", "./internal/engine/", "-run", "DeviceSecret|NativeSSO", "-count=1"],
}

# (id, property, repo, file, original code, mutated code)
MUTANTS = [
    ("M1", "DPoP key must equal the device secret's cnf.jkt", THUNDER, GRANT,
     "if subtle.ConstantTimeCompare([]byte(jkt), []byte(dsClaims.CnfJkt)) != 1 {",
     "if false {"),
    ("M2", "ID token must carry the device secret's ds_hash", THUNDER, GRANT,
     "if subtle.ConstantTimeCompare([]byte(deviceSecretHash(tokenRequest.ActorToken)), []byte(idClaims.dsHash)) != 1 {",
     "if false {"),
    ("M3", "ID token sid/sub/aud must match the device secret session", THUNDER, GRANT,
     "if idClaims.sid != dsClaims.SessionID || idClaims.sub != dsClaims.Subject ||",
     "if false && idClaims.sid != dsClaims.SessionID ||"),
    ("M4", "Original client must still be registered for device_sso", THUNDER, GRANT,
     "if origApp == nil || !slices.Contains(origApp.Scopes, constants.ScopeDeviceSSO) {",
     "if origApp == nil {"),
    ("M5", "Requested scopes must be a subset of the original grant", THUNDER, GRANT,
     "notGranted = append(notGranted, s)",
     "_ = s"),
    ("M6", "device_sso cannot be requested on token exchange", THUNDER, GRANT,
     "if slices.Contains(tokenservice.ParseScopes(tokenRequest.Scope), constants.ScopeDeviceSSO) {",
     "if false {"),
    ("M7", "A redemption must be claimed before a token is issued", THUNDER, GRANT,
     "if svcErr := h.nativeSSO.DeviceSecret.MarkDeviceSecretUsed(ctx, dsClaims, tokenRequest.ClientID); svcErr != nil {",
     "if svcErr := (*common.ServiceError)(nil); svcErr != nil {"),
    ("M8", "Tokens die with their SSO session", THUNDER, GRANT,
     "if !active {",
     "if false && !active {"),
    ("M9", "Only clients registered for device_sso can request it", THUNDER, GRANT,
     "if !slices.Contains(oauthApp.Scopes, constants.ScopeDeviceSSO) {",
     "if false {"),
    ("M10", "Apps of another vendor (rp_id) cannot redeem", ESIGNET, DSP,
     "if original.RpID == \"\" || original.RpID != requesting.RpID {",
     "if false && original.RpID != requesting.RpID {"),
    ("M11", "Only allow-listed apps can redeem", ESIGNET, DSP,
     "if !slices.Contains(stringSliceConfig(original.AdditionalConfig, NativeSSOAllowedClientsKey), clientID) {",
     "if false {"),
    ("M12", "Each app redeems a device secret at most once", ESIGNET, DSP,
     "if !stored {",
     "if false && !stored {"),
    ("M13", "Expired device secrets are rejected", ESIGNET, DSP,
     "if p.now().Unix() >= payload.Expiry {",
     "if false {"),
    ("M14", "Only device secrets (token_use) are accepted", ESIGNET, DSP,
     "if payload.TokenUse != deviceSecretTokenUse || payload.Issuer != p.cfg.Issuer || payload.JTI == \"\" ||",
     "if payload.JTI == \"\" ||"),
    ("M15", "A device secret of an ended or replaced session is rejected", ESIGNET, DSP,
     "\tif session == nil || session.JTI != payload.JTI {\n\t\treturn nil, nativeSSOError(\"native_sso_session_ended\", \"The SSO session has ended\")\n\t}\n\n\tif svcErr := p.authorizeRedemption",
     "\tif false && (session == nil || session.JTI != payload.JTI) {\n\t\treturn nil, nativeSSOError(\"native_sso_session_ended\", \"The SSO session has ended\")\n\t}\n\n\tif svcErr := p.authorizeRedemption"),
    ("M16", "A child only receives claims the user accepted", ESIGNET, CONSENT,
     "if slices.Contains(client.Claims, claim) && slices.Contains(record.AcceptedClaims, claim) {",
     "if slices.Contains(client.Claims, claim) {"),
]


def run_tests(repo):
    env = dict(os.environ, CGO_ENABLED="0")
    proc = subprocess.run(TESTS[repo], cwd=repo, env=env, capture_output=True, text=True)
    return proc.returncode == 0, proc.stdout + proc.stderr


def main():
    report_path = None
    if "--report" in sys.argv:
        report_path = sys.argv[sys.argv.index("--report") + 1]

    for repo in TESTS:
        ok, out = run_tests(repo)
        if not ok:
            print(f"baseline tests fail in {repo}; fix them before mutation testing\n{out}")
            return 2

    results = []
    for mid, prop, repo, rel, old, new in MUTANTS:
        path = os.path.join(repo, rel)
        with open(path) as f:
            original = f.read()
        if original.count(old) != 1:
            results.append({"id": mid, "property": prop, "status": "stale", "detail": "anchor not found"})
            print(f"[STALE ] {mid:4} {prop}")
            continue
        start = time.time()
        try:
            with open(path, "w") as f:
                f.write(original.replace(old, new, 1))
            passed, out = run_tests(repo)
        finally:
            with open(path, "w") as f:
                f.write(original)
        if "build failed" in out or "[setup failed]" in out:
            status = "invalid"
        else:
            status = "survived" if passed else "killed"
        failing = sorted({line.split("--- FAIL: ")[1].split(" ")[0]
                          for line in out.splitlines() if "--- FAIL: " in line})
        results.append({"id": mid, "property": prop, "repo": os.path.basename(os.path.dirname(repo))
                        if repo == THUNDER else "esignet", "file": rel, "status": status,
                        "killed_by": failing[:3], "seconds": round(time.time() - start, 1)})
        print(f"[{status.upper():7}] {mid:4} {prop}" + (f"  <- {failing[-1]}" if failing else ""))

    killed = sum(r["status"] == "killed" for r in results)
    print(f"\n{killed}/{len(results)} mutants killed")
    if report_path:
        with open(report_path, "w") as f:
            json.dump({"generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "results": results},
                      f, indent=2)
    return 0 if killed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
