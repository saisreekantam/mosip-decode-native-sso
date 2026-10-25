package main

import (
	"bytes"
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	grantTypeTokenExchange = "urn:ietf:params:oauth:grant-type:token-exchange"
	tokenTypeIDToken       = "urn:ietf:params:oauth:token-type:id_token"
	tokenTypeDeviceSecret  = "urn:openid:params:token-type:device-secret"
	clientAssertionType    = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
	acrOTP                 = "mosip:idp:acr:generated-code"
)

// Env is the eSignet deployment under test.
type Env struct {
	BaseURL     string
	MockIDURL   string
	RedirectURI string
	Verbose     bool
	http        *http.Client

	mu       sync.Mutex
	recorder *[]Exchange
}

// Exchange is one recorded HTTP call of a scenario. Tokens, secrets and proofs are never recorded.
type Exchange struct {
	Method      string `json:"method"`
	Path        string `json:"path"`
	Status      int    `json:"status"`
	Error       string `json:"error,omitempty"`
	Description string `json:"error_description,omitempty"`
	DurationMs  int64  `json:"duration_ms"`
}

// record captures the HTTP exchanges made by fn.
func (e *Env) record(fn func()) []Exchange {
	var log []Exchange
	e.mu.Lock()
	e.recorder = &log
	e.mu.Unlock()
	defer func() {
		e.mu.Lock()
		e.recorder = nil
		e.mu.Unlock()
	}()
	fn()
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([]Exchange(nil), log...)
}

func newEnv(base, mock string, verbose bool) *Env {
	return &Env{
		BaseURL:     strings.TrimRight(base, "/"),
		MockIDURL:   strings.TrimRight(mock, "/"),
		RedirectURI: "https://localhost:3000/callback",
		Verbose:     verbose,
		http: &http.Client{
			Timeout:       30 * time.Second,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}
}

// Response is a decoded HTTP response.
type Response struct {
	Status int
	Header http.Header
	JSON   map[string]any
	Raw    string
}

func (r *Response) str(key string) string {
	v, _ := r.JSON[key].(string)
	return v
}

func (e *Env) do(req *http.Request) (*Response, error) {
	start := time.Now()
	resp, err := e.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	out := &Response{Status: resp.StatusCode, Header: resp.Header, Raw: string(body)}
	_ = json.Unmarshal(body, &out.JSON)
	e.mu.Lock()
	if e.recorder != nil && !strings.HasPrefix(req.URL.Path, "/flow/") {
		*e.recorder = append(*e.recorder, Exchange{
			Method: req.Method, Path: req.URL.Path, Status: resp.StatusCode,
			Error: out.str("error"), Description: out.str("error_description"),
			DurationMs: time.Since(start).Milliseconds(),
		})
	}
	e.mu.Unlock()
	if e.Verbose {
		fmt.Printf("    %s %s -> %d %s\n", req.Method, req.URL.Path, resp.StatusCode, truncate(string(body), 600))
	}
	return out, nil
}

func (e *Env) postJSON(u string, body any) (*Response, error) {
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequest(http.MethodPost, u, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	return e.do(req)
}

func (e *Env) postForm(u string, form url.Values, headers map[string]string) (*Response, error) {
	req, err := http.NewRequest(http.MethodPost, u, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	return e.do(req)
}

func requestTime() string { return time.Now().UTC().Format("2006-01-02T15:04:05.000Z") }

// createIdentity seeds a mock identity the mock authenticator can sign in with OTP.
func (e *Env) createIdentity(uin string) error {
	lang := func(v string) []map[string]string { return []map[string]string{{"language": "eng", "value": v}} }
	resp, err := e.postJSON(e.MockIDURL+"/v1/mock-identity-system/identity", map[string]any{
		"requestTime": requestTime(),
		"request": map[string]any{
			"individualId": uin, "pin": "111111", "password": "Mosip@123",
			"email": "asha.devi@example.com", "phone": "+919876543210",
			"fullName": lang("Asha Devi"), "nickName": lang("Asha"), "preferredUsername": lang("asha"),
			"givenName": lang("Asha"), "middleName": lang("K"), "familyName": lang("Devi"),
			"gender": lang("Female"), "dateOfBirth": "1990/01/01",
			"streetAddress": lang("12 MG Road"), "locality": lang("Bengaluru"), "region": lang("Karnataka"),
			"country": lang("India"), "postalCode": "560001", "preferredLang": "eng", "locale": "en",
			"zoneInfo":     "Asia/Kolkata",
			"encodedPhoto": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGM4YWQERAwQCgAjNgSxRnwWMwAAAABJRU5ErkJggg==",
		},
	})
	if err != nil {
		return err
	}
	if resp.Status != 200 && resp.Status != 201 {
		return fmt.Errorf("create identity: %d %s", resp.Status, resp.Raw)
	}
	if errs, ok := resp.JSON["errors"].([]any); ok && len(errs) > 0 {
		return fmt.Errorf("create identity: %v", errs)
	}
	return nil
}

// Client is a registered eSignet OIDC client authenticating with private_key_jwt (PS256).
type Client struct {
	env      *Env
	ID       string
	Name     string
	RPID     string
	key      *rsa.PrivateKey
	kid      string
	Claims   []string
	Grants   []string
	Extra    map[string]any
	reqCount atomic.Int64
}

func newClient(env *Env, name, rpID string) (*Client, error) {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	id := fmt.Sprintf("%s-%d", name, time.Now().UnixNano())
	return &Client{
		env: env, ID: id, Name: name, RPID: rpID, key: key, kid: rsaThumbprint(&key.PublicKey),
		Claims: []string{"name", "email", "phone_number", "gender", "birthdate", "picture", "address"},
		Grants: []string{"authorization_code"},
		Extra: map[string]any{
			"require_pushed_authorization_requests": true,
			"dpop_bound_access_tokens":              true,
			"require_pkce":                          true,
		},
	}, nil
}

func (c *Client) register() error {
	resp, err := c.env.postJSON(c.env.BaseURL+"/client-mgmt/client", map[string]any{
		"requestTime": requestTime(),
		"request": map[string]any{
			"clientId": c.ID, "clientName": c.Name, "clientNameLangMap": map[string]string{"eng": c.Name},
			"relyingPartyId": c.RPID, "logoUri": "https://example.com/logo.png",
			"redirectUris": []string{c.env.RedirectURI}, "userClaims": c.Claims,
			"authContextRefs": []string{acrOTP}, "publicKey": rsaPublicJWK(&c.key.PublicKey, c.kid),
			"grantTypes": c.Grants, "clientAuthMethods": []string{"private_key_jwt"},
			"additionalConfig": c.Extra,
		},
	})
	if err != nil {
		return err
	}
	if errs, ok := resp.JSON["errors"].([]any); ok && len(errs) > 0 {
		return fmt.Errorf("register %s: %v", c.Name, errs)
	}
	if resp.Status != 200 {
		return fmt.Errorf("register %s: %d %s", c.Name, resp.Status, resp.Raw)
	}
	return nil
}

func (c *Client) assertion() string {
	aud := c.env.BaseURL // FAPI 2.0: the client assertion audience is the issuer
	now := time.Now().Unix()
	n := c.reqCount.Add(1)
	tok, err := signJWT(map[string]any{"alg": "PS256", "typ": "JWT", "kid": c.kid}, map[string]any{
		"iss": c.ID, "sub": c.ID, "aud": aud, "jti": fmt.Sprintf("%s-%d-%d", c.ID, now, n),
		"iat": now, "exp": now + 60,
	}, c.key)
	if err != nil {
		panic(err)
	}
	return tok
}

// LoginOptions controls an interactive (OTP) login.
type LoginOptions struct {
	Scope string
	UIN   string
	OTP   string
	// DenyClaims lists claims the user unticks on the consent screen.
	DenyClaims []string
}

// LoginResult is what an app holds after a completed authorization code grant.
type LoginResult struct {
	Token *Response
}

// login runs PAR -> authorize -> eSignet login flow (UIN + OTP + consent) -> callback -> token,
// with every protected request DPoP-signed by dpop (the app's device key).
func (c *Client) login(opts LoginOptions, dpop DPoPSigner) (*LoginResult, error) {
	verifierBytes := make([]byte, 32)
	_, _ = rand.Read(verifierBytes)
	verifier := b64(verifierBytes)

	parURL := c.env.BaseURL + "/oauth2/par"
	par, err := c.env.postForm(parURL, url.Values{
		"client_id": {c.ID}, "redirect_uri": {c.env.RedirectURI}, "response_type": {"code"},
		"state": {"native-sso"}, "nonce": {fmt.Sprintf("n-%d", time.Now().UnixNano())},
		"code_challenge": {sha256B64(verifier)}, "code_challenge_method": {"S256"},
		"scope": {opts.Scope}, "acr_values": {acrOTP}, "dpop_jkt": {dpop.Thumbprint()},
		"client_assertion_type": {clientAssertionType}, "client_assertion": {c.assertion()},
	}, map[string]string{"DPoP": dpop.Proof("POST", parURL, "")})
	if err != nil {
		return nil, err
	}
	requestURI := par.str("request_uri")
	if requestURI == "" {
		return nil, fmt.Errorf("PAR failed: %d %s", par.Status, par.Raw)
	}

	authz, err := c.env.http.Get(c.env.BaseURL + "/oauth2/authorize?client_id=" + url.QueryEscape(c.ID) +
		"&request_uri=" + url.QueryEscape(requestURI))
	if err != nil {
		return nil, err
	}
	authz.Body.Close()
	loc, err := url.Parse(authz.Header.Get("Location"))
	if err != nil || loc.Query().Get("executionId") == "" {
		return nil, fmt.Errorf("authorize did not redirect to the login UI: %d %s", authz.StatusCode,
			authz.Header.Get("Location"))
	}
	authID, executionID := loc.Query().Get("authId"), loc.Query().Get("executionId")

	flowAssertion, err := c.runLoginFlow(executionID, opts)
	if err != nil {
		return nil, err
	}

	cb, err := c.env.postJSON(c.env.BaseURL+"/oauth2/auth/callback", map[string]any{
		"authId": authID, "assertion": flowAssertion,
	})
	if err != nil {
		return nil, err
	}
	redirect, err := url.Parse(cb.str("redirect_uri"))
	if err != nil || redirect.Query().Get("code") == "" {
		return nil, fmt.Errorf("callback returned no code: %d %s", cb.Status, cb.Raw)
	}

	tokenURL := c.env.BaseURL + "/oauth2/token"
	token, err := c.env.postForm(tokenURL, url.Values{
		"grant_type": {"authorization_code"}, "code": {redirect.Query().Get("code")},
		"redirect_uri": {c.env.RedirectURI}, "client_id": {c.ID}, "code_verifier": {verifier},
		"client_assertion_type": {clientAssertionType}, "client_assertion": {c.assertion()},
	}, map[string]string{"DPoP": dpop.Proof("POST", tokenURL, "")})
	if err != nil {
		return nil, err
	}
	return &LoginResult{Token: token}, nil
}

// runLoginFlow drives eSignet's flow execution API the way the login UI does: it answers each
// prompt (UIN + captcha, OTP, consent) until the flow completes with an assertion.
func (c *Client) runLoginFlow(executionID string, opts LoginOptions) (string, error) {
	flowURL := c.env.BaseURL + "/flow/execute"
	body := map[string]any{"executionId": executionID}
	for step := 0; step < 12; step++ {
		resp, err := c.env.postJSON(flowURL, body)
		if err != nil {
			return "", err
		}
		if resp.Status != 200 {
			return "", fmt.Errorf("flow step %d: %d %s", step, resp.Status, resp.Raw)
		}
		if resp.str("flowStatus") == "COMPLETE" {
			return resp.str("assertion"), nil
		}
		if resp.str("flowStatus") == "ERROR" {
			return "", fmt.Errorf("flow error: %s", resp.Raw)
		}

		data, _ := resp.JSON["data"].(map[string]any)
		next := map[string]any{"executionId": executionID, "challengeToken": resp.str("challengeToken")}
		if action := pickAction(data); action != "" {
			next["action"] = action
		}
		inputs := map[string]any{}
		for _, in := range listOf(data["inputs"]) {
			id, _ := in["identifier"].(string)
			switch id {
			case "username":
				inputs["username"] = opts.UIN
				inputs["captcha_token"] = "test-captcha-token"
			case "captcha_token":
				inputs["captcha_token"] = "test-captcha-token"
			case "otp":
				inputs["otp"] = opts.OTP
			case "consent_decisions":
				inputs["consent_decisions"] = consentDecisions(data, opts.DenyClaims)
			}
		}
		if len(inputs) > 0 {
			next["inputs"] = inputs
		}
		body = next
	}
	return "", fmt.Errorf("login flow did not complete")
}

// pickAction prefers the action that selects OTP authentication, otherwise the first offered.
func pickAction(data map[string]any) string {
	actions := listOf(data["actions"])
	for _, a := range actions {
		raw, _ := json.Marshal(a)
		if strings.Contains(string(raw), acrOTP) || strings.Contains(strings.ToLower(string(raw)), "otp") {
			if ref, ok := a["ref"].(string); ok {
				return ref
			}
		}
	}
	if len(actions) > 0 {
		ref, _ := actions[0]["ref"].(string)
		return ref
	}
	return ""
}

// consentDecisions approves every element of the consent prompt (data.additionalData.consentPrompt,
// a JSON-encoded purpose list) except the claims in deny, like a user unticking boxes in the UI.
func consentDecisions(data map[string]any, deny []string) string {
	denied := map[string]bool{}
	for _, d := range deny {
		denied[d] = true
	}
	var prompt []struct {
		PurposeName string `json:"purposeName"`
		Essential   []struct {
			Name string `json:"name"`
		} `json:"essential"`
		Optional []struct {
			Name string `json:"name"`
		} `json:"optional"`
	}
	if extra, ok := data["additionalData"].(map[string]any); ok {
		if raw, ok := extra["consentPrompt"].(string); ok {
			_ = json.Unmarshal([]byte(raw), &prompt)
		}
	}
	purposes := []map[string]any{}
	for _, p := range prompt {
		elements := []map[string]any{}
		for _, el := range p.Essential {
			elements = append(elements, map[string]any{"name": el.Name, "approved": true})
		}
		for _, el := range p.Optional {
			elements = append(elements, map[string]any{"name": el.Name, "approved": !denied[el.Name]})
		}
		purposes = append(purposes, map[string]any{"purposeName": p.PurposeName, "approved": true, "elements": elements})
	}
	raw, _ := json.Marshal(map[string]any{"approved": true, "purposes": purposes})
	return string(raw)
}

func listOf(v any) []map[string]any {
	items, _ := v.([]any)
	out := make([]map[string]any, 0, len(items))
	for _, it := range items {
		if m, ok := it.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

// TokenExchange is a Native SSO token exchange request.
type TokenExchange struct {
	IDToken      string
	DeviceSecret string
	Scope        string
	DPoP         string
	// ActorTokenType overrides the device-secret token type (for negative tests).
	ActorTokenType string
}

func (c *Client) tokenExchange(x TokenExchange) (*Response, error) {
	tokenURL := c.env.BaseURL + "/oauth2/token"
	actorType := x.ActorTokenType
	if actorType == "" {
		actorType = tokenTypeDeviceSecret
	}
	form := url.Values{
		"grant_type": {grantTypeTokenExchange}, "client_id": {c.ID},
		"subject_token": {x.IDToken}, "subject_token_type": {tokenTypeIDToken},
		"actor_token": {x.DeviceSecret}, "actor_token_type": {actorType},
		"audience":              {c.env.BaseURL},
		"client_assertion_type": {clientAssertionType}, "client_assertion": {c.assertion()},
	}
	if x.Scope != "" {
		form.Set("scope", x.Scope)
	}
	headers := map[string]string{}
	if x.DPoP != "" {
		headers["DPoP"] = x.DPoP
	}
	return c.env.postForm(tokenURL, form, headers)
}

func (e *Env) userinfo(accessToken, dpopProof string) (*Response, error) {
	req, err := http.NewRequest(http.MethodGet, e.BaseURL+"/oauth2/userinfo", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "DPoP "+accessToken)
	req.Header.Set("DPoP", dpopProof)
	return e.do(req)
}

func (e *Env) logout(deviceSecret string) (*Response, error) {
	return e.postForm(e.BaseURL+"/native-sso/logout", url.Values{"device_secret": {deviceSecret}}, nil)
}

func truncate(s string, n int) string {
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) > n {
		return s[:n] + "..."
	}
	return s
}
