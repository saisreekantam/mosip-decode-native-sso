package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"errors"
	"fmt"
	"time"
)

// DPoPSigner produces DPoP proofs (RFC 9449) with a key it never exposes.
type DPoPSigner interface {
	Thumbprint() string
	Proof(htm, htu, accessToken string) string
}

// SecureKey models a non-exportable, hardware-backed key (Android Keystore / StrongBox, iOS Secure
// Enclave): callers can ask it to sign, but cannot read the private key.
type SecureKey struct {
	priv *ecdsa.PrivateKey
}

func newSecureKey() *SecureKey {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		panic(err)
	}
	return &SecureKey{priv: priv}
}

func (k *SecureKey) Thumbprint() string { return ecThumbprint(&k.priv.PublicKey) }

func (k *SecureKey) Proof(htm, htu, accessToken string) string {
	claims := map[string]any{
		"jti": fmt.Sprintf("%d-%s", time.Now().UnixNano(), randomHex(6)), "htm": htm, "htu": htu,
		"iat": time.Now().Unix(),
	}
	if accessToken != "" {
		claims["ath"] = sha256B64(accessToken)
	}
	tok, err := signJWT(map[string]any{"typ": "dpop+jwt", "alg": "ES256", "jwk": ecPublicJWK(&k.priv.PublicKey)},
		claims, k.priv)
	if err != nil {
		panic(err)
	}
	return tok
}

// AppIdentity is what the OS knows about an installed app: its package and signing certificate.
type AppIdentity struct {
	Package     string
	SigningCert string
}

// SuperApp is the first app of the vendor family. It signs the user in with eSignet, keeps the ID
// token and device secret in app-private storage, and owns the device key (key A).
type SuperApp struct {
	Identity     AppIdentity
	Client       *Client
	Key          *SecureKey
	IDToken      string
	DeviceSecret string
	AccessToken  string
}

// ErrBindDenied is returned when the OS refuses to bind an app to the SSO service.
var ErrBindDenied = errors.New("SecurityException: caller does not hold the signature permission")

// BindSSOService models Android's bindService() against a service protected by a
// signature-level permission: the OS only lets apps signed with the same certificate bind.
func (s *SuperApp) BindSSOService(caller AppIdentity) (*SSOService, error) {
	if caller.SigningCert != s.Identity.SigningCert {
		return nil, ErrBindDenied
	}
	return &SSOService{app: s, caller: caller}, nil
}

// SSOService is the Super App's bound service. It hands out the SSO bundle and signs DPoP proofs
// for sibling apps with key A; the private key never crosses the process boundary.
type SSOService struct {
	app    *SuperApp
	caller AppIdentity
}

func (svc *SSOService) Bundle() (idToken, deviceSecret string, err error) {
	if svc.app.DeviceSecret == "" {
		return "", "", errors.New("not signed in")
	}
	return svc.app.IDToken, svc.app.DeviceSecret, nil
}

func (svc *SSOService) Thumbprint() string { return svc.app.Key.Thumbprint() }

func (svc *SSOService) Proof(htm, htu, accessToken string) string {
	return svc.app.Key.Proof(htm, htu, accessToken)
}

// ChildApp is a second app of the same vendor. It has its own OIDC client registration but no
// device key of its own for SSO: it borrows key A through the Super App's SSO service.
type ChildApp struct {
	Identity    AppIdentity
	Client      *Client
	AccessToken string
}

func randomHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%x", b)
}
