package main

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand/v2"
	"os"
	"os/exec"
	"slices"
	"strings"
	"sync"
	"time"
)

const (
	vendorRP   = "acme-health"
	foreignRP  = "other-vendor"
	vendorCert = "SHA256:AC:ME:HE:AL:TH:VE:ND:OR"
	rogueCert  = "SHA256:EV:IL:RE:PA:CK:AG:ED"
	superScope = "openid profile email device_sso"
	mockOTP    = "111111"
)

// Lab is the set of apps and clients the scenarios run against.
type Lab struct {
	env      *Env
	UIN      string
	Super    *SuperApp
	Child    *ChildApp
	Child2   *ChildApp
	Outsider *Client
	Foreign  *Client
	NoSSO    *Client
}

func setupLab(env *Env) (*Lab, error) {
	lab := &Lab{env: env, UIN: fmt.Sprintf("%010d", 1000000000+rand.IntN(899999999))}
	if err := env.createIdentity(lab.UIN); err != nil {
		return nil, err
	}

	mk := func(name, rp string, grants ...string) (*Client, error) {
		c, err := newClient(env, name, rp)
		if err != nil {
			return nil, err
		}
		c.Grants = append(c.Grants, grants...)
		return c, nil
	}
	var err error
	childClient, err := mk("acme-pharmacy", vendorRP, grantTypeTokenExchange)
	if err != nil {
		return nil, err
	}
	child2Client, err := mk("acme-labs", vendorRP, grantTypeTokenExchange)
	if err != nil {
		return nil, err
	}
	if lab.Outsider, err = mk("acme-unlisted", vendorRP, grantTypeTokenExchange); err != nil {
		return nil, err
	}
	if lab.Foreign, err = mk("other-vendor-app", foreignRP, grantTypeTokenExchange); err != nil {
		return nil, err
	}
	if lab.NoSSO, err = mk("acme-legacy", vendorRP); err != nil {
		return nil, err
	}
	superClient, err := mk("acme-super-app", vendorRP)
	if err != nil {
		return nil, err
	}
	superClient.Extra["allowed_authorization_scopes"] = []string{"device_sso"}
	// The foreign app is deliberately allow-listed: the same-vendor rule must still reject it.
	superClient.Extra["native_sso_allowed_client_ids"] = []string{childClient.ID, child2Client.ID, lab.Foreign.ID}

	for _, c := range []*Client{childClient, child2Client, lab.Outsider, lab.Foreign, lab.NoSSO, superClient} {
		if err := c.register(); err != nil {
			return nil, err
		}
	}

	lab.Super = &SuperApp{Identity: AppIdentity{"com.acme.superapp", vendorCert}, Client: superClient, Key: newSecureKey()}
	lab.Child = &ChildApp{Identity: AppIdentity{"com.acme.pharmacy", vendorCert}, Client: childClient}
	lab.Child2 = &ChildApp{Identity: AppIdentity{"com.acme.labs", vendorCert}, Client: child2Client}
	return lab, nil
}

// Result is the outcome of one scenario.
type Result struct {
	ID          string     `json:"id"`
	Name        string     `json:"name"`
	Requirement string     `json:"requirement"`
	Expected    string     `json:"expected"`
	Observed    string     `json:"observed"`
	Pass        bool       `json:"pass"`
	DurationMs  int64      `json:"duration_ms"`
	Exchanges   []Exchange `json:"exchanges,omitempty"`
	Setup       bool       `json:"setup,omitempty"`
}

type scenario struct {
	id, name, requirement, expected string
	run                             func(*Lab) (string, error)
}

func runScenarios(lab *Lab, mode string) []Result {
	var results []Result
	for _, s := range scenarios() {
		if mode == "happy" && !strings.HasPrefix(s.id, "H") {
			continue
		}
		r := runOne(lab, s)
		results = append(results, r)
		mark := "PASS"
		if !r.Pass {
			mark = "FAIL"
		}
		fmt.Printf("[%s] %-4s %s\n       %s\n", mark, r.ID, r.Name, r.Observed)
		if s.id == "H1" && !r.Pass {
			break
		}
	}
	return results
}

// runOne runs a scenario and records the HTTP exchanges it made.
func runOne(lab *Lab, s scenario) Result {
	start := time.Now()
	var observed string
	var err error
	exchanges := lab.env.record(func() { observed, err = s.run(lab) })
	r := Result{ID: s.id, Name: s.name, Requirement: s.requirement, Expected: s.expected,
		Observed: observed, Pass: err == nil, DurationMs: time.Since(start).Milliseconds(), Exchanges: exchanges}
	if err != nil {
		r.Observed = err.Error()
	}
	return r
}

// session is a completed Super App login.
type session struct {
	idToken, deviceSecret, accessToken string
	claims                             map[string]any
}

func (lab *Lab) superLogin(opts LoginOptions) (*session, error) {
	if opts.UIN == "" {
		opts.UIN = lab.UIN
	}
	if opts.OTP == "" {
		opts.OTP = mockOTP
	}
	if opts.Scope == "" {
		opts.Scope = superScope
	}
	res, err := lab.Super.Client.login(opts, lab.Super.Key)
	if err != nil {
		return nil, err
	}
	t := res.Token
	if t.Status != 200 {
		return nil, fmt.Errorf("token endpoint: %d %s", t.Status, t.Raw)
	}
	s := &session{idToken: t.str("id_token"), deviceSecret: t.str("device_secret"), accessToken: t.str("access_token")}
	if s.idToken == "" || s.deviceSecret == "" {
		return nil, fmt.Errorf("token response lacks id_token/device_secret: %s", truncate(t.Raw, 300))
	}
	_, s.claims, err = decodeJWT(s.idToken)
	if err != nil {
		return nil, err
	}
	lab.Super.IDToken, lab.Super.DeviceSecret, lab.Super.AccessToken = s.idToken, s.deviceSecret, s.accessToken
	return s, nil
}

// childSSO performs the child-side flow: bind the Super App's SSO service, fetch the bundle and a
// DPoP proof signed with key A, then exchange.
func (lab *Lab) childSSO(child *ChildApp, scope string) (*Response, *SSOService, error) {
	svc, err := lab.Super.BindSSOService(child.Identity)
	if err != nil {
		return nil, nil, err
	}
	idToken, deviceSecret, err := svc.Bundle()
	if err != nil {
		return nil, nil, err
	}
	resp, err := child.Client.tokenExchange(TokenExchange{
		IDToken: idToken, DeviceSecret: deviceSecret, Scope: scope,
		DPoP: svc.Proof("POST", lab.env.BaseURL+"/oauth2/token", ""),
	})
	return resp, svc, err
}

func expectError(resp *Response, status int, code string) (string, error) {
	observed := fmt.Sprintf("%d %s: %s", resp.Status, resp.str("error"), resp.str("error_description"))
	if resp.Status != status || resp.str("error") != code {
		return observed, fmt.Errorf("expected %d %s, got %s", status, code, observed)
	}
	return observed, nil
}

func dsHash(deviceSecret string) string {
	sum := sha256.Sum256([]byte(deviceSecret))
	return b64(sum[:16])
}

func scenarios() []scenario {
	var first *session
	tokenURL := func(lab *Lab) string { return lab.env.BaseURL + "/oauth2/token" }

	return []scenario{
		{
			id: "H1", name: "Super App signs in with OTP and requests device_sso",
			requirement: "Tasks 1-6: device_sso scope, token endpoint returns access_token + id_token + device_secret",
			expected:    "200 with device_secret; id_token carries sid and ds_hash bound to the device secret; DPoP-bound access token",
			run: func(lab *Lab) (string, error) {
				s, err := lab.superLogin(LoginOptions{})
				if err != nil {
					return "", err
				}
				first = s
				if got := s.claims["ds_hash"]; got != dsHash(s.deviceSecret) {
					return "", fmt.Errorf("ds_hash %v does not match the device secret", got)
				}
				if s.claims["sid"] == nil || s.claims["sid"] == "" {
					return "", errors.New("id_token has no sid")
				}
				_, at, err := decodeJWT(s.accessToken)
				if err != nil {
					return "", err
				}
				cnf, _ := at["cnf"].(map[string]any)
				if cnf["jkt"] != lab.Super.Key.Thumbprint() {
					return "", fmt.Errorf("access token cnf.jkt %v is not key A", cnf["jkt"])
				}
				if strings.Contains(fmt.Sprint(at["scope"]), "device_sso") {
					return "", errors.New("device_sso leaked into the access token scope")
				}
				parts := strings.Count(s.deviceSecret, ".") + 1
				return fmt.Sprintf("device_secret is a %d-part JWE (%d bytes); sid=%v; ds_hash verified; access token bound to key A",
					parts, len(s.deviceSecret), s.claims["sid"]), nil
			},
		},
		{
			id: "H2", name: "Child App signs in silently via token exchange (no login, no consent screen)",
			requirement: "Tasks 7-9: id_token + device_secret + DPoP proof via app-to-app channel, token_exchange grant",
			expected:    "200 DPoP access token for the Child App, bound to key A, act.sub = Super App",
			run: func(lab *Lab) (string, error) {
				start := time.Now()
				resp, _, err := lab.childSSO(lab.Child, "openid profile")
				if err != nil {
					return "", err
				}
				if resp.Status != 200 {
					return "", fmt.Errorf("exchange failed: %d %s", resp.Status, resp.Raw)
				}
				lab.Child.AccessToken = resp.str("access_token")
				_, at, err := decodeJWT(lab.Child.AccessToken)
				if err != nil {
					return "", err
				}
				cnf, _ := at["cnf"].(map[string]any)
				act, _ := at["act"].(map[string]any)
				switch {
				case resp.str("token_type") != "DPoP":
					return "", fmt.Errorf("token_type %q", resp.str("token_type"))
				case cnf["jkt"] != lab.Super.Key.Thumbprint():
					return "", errors.New("child token is not bound to key A")
				case at["client_id"] != lab.Child.Client.ID:
					return "", fmt.Errorf("child token client_id %v", at["client_id"])
				case act["sub"] != lab.Super.Client.ID:
					return "", fmt.Errorf("act.sub %v", act["sub"])
				case at["sub"] != first.claims["sub"]:
					return "", errors.New("child token subject differs from the Super App session")
				}
				return fmt.Sprintf("signed in in %dms; token_type=DPoP; issued_token_type=%s; scope=%q; act.sub=%v",
					time.Since(start).Milliseconds(), resp.str("issued_token_type"), resp.str("scope"), act["sub"]), nil
			},
		},
		{
			id: "H3", name: "Child App calls userinfo with a DPoP proof borrowed from the Super App",
			requirement: "End-to-end: child receives the user's consented claims",
			expected:    "200 addressed to the Child App, with only the profile claims the user consented to (no email)",
			run: func(lab *Lab) (string, error) {
				svc, err := lab.Super.BindSSOService(lab.Child.Identity)
				if err != nil {
					return "", err
				}
				resp, err := lab.env.userinfo(lab.Child.AccessToken,
					svc.Proof("GET", lab.env.BaseURL+"/oauth2/userinfo", lab.Child.AccessToken))
				if err != nil {
					return "", err
				}
				if resp.Status != 200 {
					return "", fmt.Errorf("userinfo: %d %s", resp.Status, truncate(resp.Raw, 300))
				}
				claims := resp.JSON
				if claims == nil {
					// eSignet returns userinfo as a signed JWT for the mock identity system.
					if _, payload, err := decodeJWT(strings.TrimSpace(resp.Raw)); err == nil {
						claims = payload
					}
				}
				// The child must be served its own record: addressed to it, and only the claims its
				// "openid profile" request and the user's consent cover (no email).
				if aud := fmt.Sprint(claims["aud"]); claims["aud"] != nil && aud != lab.Child.Client.ID {
					return "", fmt.Errorf("userinfo is addressed to %s, not the Child App", aud)
				}
				if _, leaked := claims["email"]; leaked {
					return "", errors.New("userinfo released email although the child asked only for openid profile")
				}
				names := make([]string, 0, len(claims))
				for k := range claims {
					names = append(names, k)
				}
				slices.Sort(names)
				return fmt.Sprintf("userinfo for %v: claims %v", claims["name"], names), nil
			},
		},
		{
			id: "A1", name: "Replay: the same app redeems the same device secret again",
			requirement: "Diagram: mark device_secret jti as used",
			expected:    "400 invalid_grant",
			run: func(lab *Lab) (string, error) {
				resp, _, err := lab.childSSO(lab.Child, "openid profile")
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_grant")
			},
		},
		{
			id: "A2", name: "Stolen id_token + device_secret replayed from another device (attacker's own DPoP key)",
			requirement: "Task 9.iii.4: cnf.jkt of the device secret must match the DPoP proof key",
			expected:    "400 invalid_grant",
			run: func(lab *Lab) (string, error) {
				attacker := newSecureKey()
				resp, err := lab.Child2.Client.tokenExchange(TokenExchange{
					IDToken: first.idToken, DeviceSecret: first.deviceSecret, Scope: "openid profile",
					DPoP: attacker.Proof("POST", tokenURL(lab), ""),
				})
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_grant")
			},
		},
		{
			id: "A3", name: "Token exchange without a DPoP proof",
			requirement: "Device secret is sender-constrained; proof of possession is mandatory",
			expected:    "400 invalid_dpop_proof",
			run: func(lab *Lab) (string, error) {
				resp, err := lab.Child2.Client.tokenExchange(TokenExchange{
					IDToken: first.idToken, DeviceSecret: first.deviceSecret, Scope: "openid profile",
				})
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_dpop_proof")
			},
		},
		{
			id: "A4", name: "Tampered device secret (one byte of the JWE ciphertext flipped)",
			requirement: "Device secret is signed-then-encrypted (integrity + confidentiality)",
			expected:    "400 invalid_grant",
			run: func(lab *Lab) (string, error) {
				parts := strings.Split(first.deviceSecret, ".")
				c := []byte(parts[3])
				c[len(c)/2] ^= 0x01
				if c[len(c)/2] == '.' {
					c[len(c)/2] = 'A'
				}
				parts[3] = string(c)
				svc, _ := lab.Super.BindSSOService(lab.Child2.Identity)
				resp, err := lab.Child2.Client.tokenExchange(TokenExchange{
					IDToken: first.idToken, DeviceSecret: strings.Join(parts, "."), Scope: "openid profile",
					DPoP: svc.Proof("POST", tokenURL(lab), ""),
				})
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_grant")
			},
		},
		{
			id: "A5", name: "Mix-and-match: id_token from another SSO session presented with this device secret",
			requirement: "Task 9.iii.1-2: ds_hash binding and sid validation",
			expected:    "400 invalid_grant",
			run: func(lab *Lab) (string, error) {
				second, err := lab.superLogin(LoginOptions{})
				if err != nil {
					return "", err
				}
				svc, _ := lab.Super.BindSSOService(lab.Child2.Identity)
				resp, err := lab.Child2.Client.tokenExchange(TokenExchange{
					IDToken: second.idToken, DeviceSecret: first.deviceSecret, Scope: "openid profile",
					DPoP: svc.Proof("POST", tokenURL(lab), ""),
				})
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_grant")
			},
		},
		{
			id: "A6", name: "Scope escalation: child asks for a scope the Super App was never granted",
			requirement: "Task 10.a: requested scope must be a subset of the Super App's scope",
			expected:    "400 invalid_scope",
			run: func(lab *Lab) (string, error) {
				resp, _, err := lab.childSSO(lab.Child2, "openid phone")
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_scope")
			},
		},
		{
			id: "A7", name: "device_sso requested on the token-exchange call",
			requirement: "Task 10.b: device_sso is only valid on the authorization_code grant",
			expected:    "400 invalid_scope",
			run: func(lab *Lab) (string, error) {
				resp, _, err := lab.childSSO(lab.Child2, "openid device_sso")
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_scope")
			},
		},
		{
			id: "A8", name: "App of a different vendor (other rp_id), even though allow-listed",
			requirement: "Diagram: allow-list check; SSO is limited to one vendor's app family",
			expected:    "400 invalid_grant",
			run: func(lab *Lab) (string, error) {
				resp, err := lab.Foreign.tokenExchange(TokenExchange{
					IDToken: lab.Super.IDToken, DeviceSecret: lab.Super.DeviceSecret, Scope: "openid profile",
					DPoP: lab.Super.Key.Proof("POST", tokenURL(lab), ""),
				})
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_grant")
			},
		},
		{
			id: "A9", name: "Same vendor, but not in the Super App's native_sso_allowed_client_ids",
			requirement: "Diagram: orig_client_id allow-list",
			expected:    "400 invalid_grant",
			run: func(lab *Lab) (string, error) {
				resp, err := lab.Outsider.tokenExchange(TokenExchange{
					IDToken: lab.Super.IDToken, DeviceSecret: lab.Super.DeviceSecret, Scope: "openid profile",
					DPoP: lab.Super.Key.Proof("POST", tokenURL(lab), ""),
				})
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "invalid_grant")
			},
		},
		{
			id: "A10", name: "Rogue app signed with a different certificate binds the Super App's SSO service",
			requirement: "Diagram: bindService is signature-permission protected",
			expected:    "bind refused by the OS (key A and the bundle never leave the Super App)",
			run: func(lab *Lab) (string, error) {
				_, err := lab.Super.BindSSOService(AppIdentity{"com.evil.lookalike", rogueCert})
				if !errors.Is(err, ErrBindDenied) {
					return "", fmt.Errorf("expected bind to be denied, got %v", err)
				}
				return err.Error(), nil
			},
		},
		{
			id: "A11", name: "Client not registered for device_sso asks for it at login",
			requirement: "Task 2: allowed_authorization_scopes must include device_sso",
			expected:    "400 invalid_scope at the token endpoint",
			run: func(lab *Lab) (string, error) {
				res, err := lab.NoSSO.login(LoginOptions{Scope: superScope, UIN: lab.UIN, OTP: mockOTP}, newSecureKey())
				if err != nil {
					return "", err
				}
				return expectError(res.Token, 400, "invalid_scope")
			},
		},
		{
			id: "C1", name: "Consent not covered: user declined email at Super App login, child asks for email",
			requirement: "Consent reuse (NOCAPTURE) only when the child's claims are a subset of consented claims",
			expected:    "400 interaction_required (child must show its own consent screen)",
			run: func(lab *Lab) (string, error) {
				// A new user, so the consent screen is shown (eSignet remembers consent per user and app).
				uin := fmt.Sprintf("%010d", 1000000000+rand.IntN(899999999))
				if err := lab.env.createIdentity(uin); err != nil {
					return "", err
				}
				if _, err := lab.superLogin(LoginOptions{UIN: uin, DenyClaims: []string{"email", "email_verified"}}); err != nil {
					return "", err
				}
				resp, _, err := lab.childSSO(lab.Child2, "openid email")
				if err != nil {
					return "", err
				}
				return expectError(resp, 400, "interaction_required")
			},
		},
		{
			id: "C2", name: "Consent reuse is audited in consent_history",
			requirement: "Diagram: append consent_history row (client_id=child, reused from Super App)",
			expected:    "consent_history row with reused_from_client_id = Super App",
			run: func(lab *Lab) (string, error) {
				out, err := exec.Command("docker", "exec", "docker-compose-database-1", "psql", "-U", "postgres",
					"-d", "mosip_esignet", "-tAc", fmt.Sprintf(
						"select client_id, reused_from_client_id, accepted_claims from esignet.consent_history "+
							"where client_id = '%s' and reused_from_client_id is not null", lab.Child.Client.ID)).CombinedOutput()
				if err != nil {
					return "", fmt.Errorf("query consent_history: %v %s", err, out)
				}
				row := strings.TrimSpace(string(out))
				if !strings.Contains(row, lab.Super.Client.ID) {
					return "", fmt.Errorf("no reused-consent row found (%q)", row)
				}
				return "row: " + row, nil
			},
		},
		{
			id: "R1", name: "Race: 100 concurrent redemptions of one device secret by the same app",
			requirement: "Single-use redemption is atomic (no check-then-set window)",
			expected:    "exactly 1 success, 99 rejected with invalid_grant",
			run: func(lab *Lab) (string, error) {
				s, err := lab.superLogin(LoginOptions{})
				if err != nil {
					return "", err
				}
				svc, err := lab.Super.BindSSOService(lab.Child2.Identity)
				if err != nil {
					return "", err
				}
				const attempts = 100
				statuses := make([]*Response, attempts)
				var wg sync.WaitGroup
				start := make(chan struct{})
				for i := range attempts {
					wg.Add(1)
					go func() {
						defer wg.Done()
						proof := svc.Proof("POST", tokenURL(lab), "")
						<-start
						statuses[i], _ = lab.Child2.Client.tokenExchange(TokenExchange{
							IDToken: s.idToken, DeviceSecret: s.deviceSecret, Scope: "openid profile", DPoP: proof,
						})
					}()
				}
				close(start)
				wg.Wait()
				ok, rejected, other := 0, 0, 0
				for _, r := range statuses {
					switch {
					case r == nil:
						other++
					case r.Status == 200:
						ok++
					case r.Status == 400 && r.str("error") == "invalid_grant":
						rejected++
					default:
						other++
					}
				}
				observed := fmt.Sprintf("%d succeeded, %d rejected (invalid_grant), %d other", ok, rejected, other)
				if ok != 1 || rejected != attempts-1 {
					return observed, errors.New("redemption is not single-use under concurrency: " + observed)
				}
				return observed, nil
			},
		},
		{
			id: "R2", name: "Replay of a captured DPoP proof against userinfo",
			requirement: "RFC 9449: each DPoP proof jti is single-use",
			expected:    "first call 200, replayed proof 401",
			run: func(lab *Lab) (string, error) {
				resp, svc, err := lab.childSSO(lab.Child, "openid profile")
				if err == nil && resp.Status != 200 {
					// The Child App already redeemed the first session; sign in with a fresh one.
					if _, err = lab.superLogin(LoginOptions{}); err == nil {
						resp, svc, err = lab.childSSO(lab.Child, "openid profile")
					}
				}
				if err != nil {
					return "", err
				}
				if resp.Status != 200 {
					return "", fmt.Errorf("exchange: %d %s", resp.Status, resp.Raw)
				}
				token := resp.str("access_token")
				proof := svc.Proof("GET", lab.env.BaseURL+"/oauth2/userinfo", token)
				first, err := lab.env.userinfo(token, proof)
				if err != nil {
					return "", err
				}
				replay, err := lab.env.userinfo(token, proof)
				if err != nil {
					return "", err
				}
				observed := fmt.Sprintf("first %d, replay %d %s", first.Status, replay.Status, replay.str("error"))
				if first.Status != 200 || replay.Status != 401 {
					return observed, errors.New("captured DPoP proof was accepted twice: " + observed)
				}
				return observed, nil
			},
		},
		{
			id: "L2", name: "Logout cuts off a Child App token that was already issued",
			requirement: "Extension: issued Native SSO tokens die with their SSO session (userinfo, introspection)",
			expected:    "userinfo 200 before logout, 401 with the same token after logout",
			run: func(lab *Lab) (string, error) {
				s, err := lab.superLogin(LoginOptions{})
				if err != nil {
					return "", err
				}
				resp, svc, err := lab.childSSO(lab.Child, "openid profile")
				if err != nil {
					return "", err
				}
				if resp.Status != 200 {
					return "", fmt.Errorf("exchange: %d %s", resp.Status, resp.Raw)
				}
				token := resp.str("access_token")
				userinfoURL := lab.env.BaseURL + "/oauth2/userinfo"
				before, err := lab.env.userinfo(token, svc.Proof("GET", userinfoURL, token))
				if err != nil {
					return "", err
				}
				if out, err := lab.env.logout(s.deviceSecret); err != nil || out.Status != 200 {
					return "", fmt.Errorf("logout failed: %v %v", err, out)
				}
				after, err := lab.env.userinfo(token, svc.Proof("GET", userinfoURL, token))
				if err != nil {
					return "", err
				}
				observed := fmt.Sprintf("before logout %d, after logout %d %s", before.Status, after.Status, after.str("error"))
				if before.Status != 200 || after.Status != 401 {
					return observed, errors.New("issued token survived logout: " + observed)
				}
				return observed, nil
			},
		},
		{
			id: "L1", name: "Global logout: Super App ends the SSO session, sibling apps can no longer sign in",
			requirement: "Extension: session-level revocation (sid liveness, out of scope in the diagram)",
			expected:    "logout 200, then token exchange 400 invalid_grant",
			run: func(lab *Lab) (string, error) {
				s, err := lab.superLogin(LoginOptions{})
				if err != nil {
					return "", err
				}
				out, err := lab.env.logout(s.deviceSecret)
				if err != nil {
					return "", err
				}
				if out.Status != 200 {
					return "", fmt.Errorf("logout: %d %s", out.Status, out.Raw)
				}
				resp, _, err := lab.childSSO(lab.Child2, "openid profile")
				if err != nil {
					return "", err
				}
				observed, err := expectError(resp, 400, "invalid_grant")
				return "logout ok, then " + observed, err
			},
		},
	}
}

func printResults(results []Result) {
	pass := 0
	for _, r := range results {
		if r.Pass {
			pass++
		}
	}
	fmt.Printf("\n%d/%d scenarios passed\n", pass, len(results))
}

func writeReport(path string, lab *Lab, results []Result) error {
	raw, err := json.MarshalIndent(map[string]any{
		"generated_at": time.Now().UTC().Format(time.RFC3339),
		"base_url":     lab.env.BaseURL,
		"clients": map[string]string{
			"super_app": lab.Super.Client.ID, "child_app": lab.Child.Client.ID, "child_app_2": lab.Child2.Client.ID,
		},
		"results": results,
	}, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0o644)
}
