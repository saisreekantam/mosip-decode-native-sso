package main

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"strings"
)

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func sha256B64(s string) string {
	sum := sha256.Sum256([]byte(s))
	return b64(sum[:])
}

// signJWT produces a compact JWS. alg is ES256 (ecdsa P-256 key) or PS256 (RSA key).
func signJWT(header, claims map[string]any, key crypto.Signer) (string, error) {
	h, err := json.Marshal(header)
	if err != nil {
		return "", err
	}
	c, err := json.Marshal(claims)
	if err != nil {
		return "", err
	}
	input := b64(h) + "." + b64(c)
	digest := sha256.Sum256([]byte(input))

	var sig []byte
	switch k := key.(type) {
	case *ecdsa.PrivateKey:
		r, s, err := ecdsa.Sign(rand.Reader, k, digest[:])
		if err != nil {
			return "", err
		}
		sig = append(pad32(r), pad32(s)...)
	case *rsa.PrivateKey:
		sig, err = rsa.SignPSS(rand.Reader, k, crypto.SHA256, digest[:], &rsa.PSSOptions{SaltLength: 32})
		if err != nil {
			return "", err
		}
	default:
		return "", fmt.Errorf("unsupported key type %T", key)
	}
	return input + "." + b64(sig), nil
}

func pad32(n *big.Int) []byte {
	out := make([]byte, 32)
	n.FillBytes(out)
	return out
}

func ecPublicJWK(pub *ecdsa.PublicKey) map[string]any {
	return map[string]any{"kty": "EC", "crv": "P-256", "x": b64(pad32(pub.X)), "y": b64(pad32(pub.Y))}
}

// ecThumbprint is the RFC 7638 JWK SHA-256 thumbprint of a P-256 public key.
func ecThumbprint(pub *ecdsa.PublicKey) string {
	j := ecPublicJWK(pub)
	return sha256B64(fmt.Sprintf(`{"crv":"P-256","kty":"EC","x":"%s","y":"%s"}`, j["x"], j["y"]))
}

func rsaPublicJWK(pub *rsa.PublicKey, kid string) map[string]any {
	return map[string]any{
		"kty": "RSA", "n": b64(pub.N.Bytes()), "e": b64(big.NewInt(int64(pub.E)).Bytes()),
		"kid": kid, "use": "sig", "alg": "PS256",
	}
}

func rsaThumbprint(pub *rsa.PublicKey) string {
	j := rsaPublicJWK(pub, "")
	return sha256B64(fmt.Sprintf(`{"e":"%s","kty":"RSA","n":"%s"}`, j["e"], j["n"]))
}

// decodeJWT returns the header and payload of a compact JWS without verifying it.
func decodeJWT(token string) (map[string]any, map[string]any, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, nil, fmt.Errorf("not a JWS (%d parts)", len(parts))
	}
	var header, payload map[string]any
	for i, dst := range []*map[string]any{&header, &payload} {
		raw, err := base64.RawURLEncoding.DecodeString(parts[i])
		if err != nil {
			return nil, nil, err
		}
		if err := json.Unmarshal(raw, dst); err != nil {
			return nil, nil, err
		}
	}
	return header, payload, nil
}
