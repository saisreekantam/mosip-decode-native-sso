package com.acme.sso.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** Native SSO token exchange types (OpenID Connect Native SSO for Mobile Apps 1.0, eSignet profile). */
object NativeSso {
    const val GRANT_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange"
    const val TOKEN_TYPE_ID_TOKEN = "urn:ietf:params:oauth:token-type:id_token"
    const val TOKEN_TYPE_DEVICE_SECRET = "urn:openid:params:token-type:device-secret"
    const val SCOPE_DEVICE_SSO = "device_sso"
    const val ACR_OTP = "mosip:idp:acr:generated-code"
}

/** Values the app keeps between the PAR request and the redirect back from the eSignet UI. */
data class PendingLogin(val codeVerifier: String, val state: String, val authorizeUrl: String)

/**
 * An eSignet OIDC client authenticating with private_key_jwt (PS256, Keystore client key) and
 * sender-constraining its tokens with DPoP (Keystore device key).
 */
class EsignetClient(
    val clientId: String,
    val redirectUri: String,
    private val clientKey: KeystoreKey,
    val issuer: String = BuildConfig.ESIGNET_ISSUER,
) {
    val tokenEndpoint = "$issuer/oauth2/token"
    val userinfoEndpoint = "$issuer/oauth2/userinfo"

    /** private_key_jwt client assertion; FAPI 2.0 requires the issuer as audience. */
    fun clientAssertion(): String {
        val now = Instant.now().epochSecond
        return Jose.signedJwt(
            JSONObject().put("alg", "PS256").put("typ", "JWT").put("kid", clientKey.thumbprint),
            JSONObject().put("iss", clientId).put("sub", clientId).put("aud", issuer)
                .put("jti", UUID.randomUUID().toString()).put("iat", now).put("exp", now + 60),
            clientKey::sign,
        )
    }

    /** Pushed authorization request (RFC 9126) with PKCE and a DPoP key binding (dpop_jkt). */
    fun pushAuthorization(scope: String, dpop: KeystoreKey, prompt: String? = null): PendingLogin {
        val verifier = Jose.b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = Jose.b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = UUID.randomUUID().toString()
        val params = mutableMapOf(
            "client_id" to clientId, "redirect_uri" to redirectUri, "response_type" to "code",
            "scope" to scope, "state" to state, "nonce" to UUID.randomUUID().toString(),
            "code_challenge" to challenge, "code_challenge_method" to "S256",
            "acr_values" to NativeSso.ACR_OTP, "dpop_jkt" to dpop.thumbprint,
            "client_assertion_type" to "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            "client_assertion" to clientAssertion(),
        )
        if (prompt != null) params["prompt"] = prompt
        val parUrl = "$issuer/oauth2/par"
        val res = Http.request("POST", parUrl, mapOf("DPoP" to dpop.dpopProof("POST", parUrl)),
            Http.form(params), "application/x-www-form-urlencoded")
        val requestUri = res.json?.optString("request_uri").orEmpty()
        check(requestUri.isNotEmpty()) { "PAR failed: ${res.status} ${res.body}" }
        val authorizeUrl = Uri.parse("$issuer/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId).appendQueryParameter("request_uri", requestUri)
            .build().toString()
        return PendingLogin(verifier, state, authorizeUrl)
    }

    /** Authorization code grant; returns the raw token response. */
    fun exchangeCode(code: String, pending: PendingLogin, dpop: KeystoreKey): HttpResult =
        Http.request("POST", tokenEndpoint, mapOf("DPoP" to dpop.dpopProof("POST", tokenEndpoint)), Http.form(mapOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri,
            "client_id" to clientId, "code_verifier" to pending.codeVerifier,
            "client_assertion_type" to "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            "client_assertion" to clientAssertion(),
        )), "application/x-www-form-urlencoded")

    /** Native SSO: exchange a sibling app's ID token + device secret with a DPoP proof made by key A. */
    fun nativeSsoExchange(idToken: String, deviceSecret: String, scope: String, dpopProof: String): HttpResult =
        Http.request("POST", tokenEndpoint, mapOf("DPoP" to dpopProof), Http.form(mapOf(
            "grant_type" to NativeSso.GRANT_TOKEN_EXCHANGE, "client_id" to clientId,
            "subject_token" to idToken, "subject_token_type" to NativeSso.TOKEN_TYPE_ID_TOKEN,
            "actor_token" to deviceSecret, "actor_token_type" to NativeSso.TOKEN_TYPE_DEVICE_SECRET,
            "scope" to scope,
            "client_assertion_type" to "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
            "client_assertion" to clientAssertion(),
        )), "application/x-www-form-urlencoded")

    fun userinfo(accessToken: String, dpopProof: String): HttpResult =
        Http.request("GET", userinfoEndpoint, mapOf("Authorization" to "DPoP $accessToken", "DPoP" to dpopProof))

    fun logoutEverywhere(deviceSecret: String): HttpResult =
        Http.request("POST", "$issuer/native-sso/logout", body = Http.form(mapOf("device_secret" to deviceSecret)),
            contentType = "application/x-www-form-urlencoded")

    companion object {
        /** Claims of a userinfo response, which eSignet returns as a signed JWT or as JSON. */
        fun userinfoClaims(res: HttpResult): JSONObject =
            res.json ?: runCatching { Jose.payload(res.body.trim()) }.getOrElse { JSONObject() }
    }
}

/**
 * Local-development provisioning against eSignet's client management API. In production the vendor
 * registers its apps once at onboarding; here each install registers itself with a Keystore key.
 */
object DevProvisioning {
    private fun requestTime() = Instant.now().toString().let { it.substring(0, 23) + "Z" }

    fun register(
        issuer: String, clientId: String, name: String, rpId: String, redirectUri: String, clientKey: KeystoreKey,
        grantTypes: List<String>, additionalConfig: JSONObject,
    ): HttpResult {
        val jwk = clientKey.publicJwk.put("kid", clientKey.thumbprint).put("use", "sig").put("alg", "PS256")
        val body = JSONObject().put("requestTime", requestTime()).put("request", JSONObject()
            .put("clientId", clientId).put("clientName", name).put("clientNameLangMap", JSONObject().put("eng", name))
            .put("relyingPartyId", rpId).put("logoUri", "https://example.com/logo.png")
            .put("redirectUris", JSONArray().put(redirectUri))
            .put("userClaims", JSONArray(listOf("name", "email", "phone_number", "gender", "birthdate", "picture")))
            .put("authContextRefs", JSONArray().put(NativeSso.ACR_OTP))
            .put("publicKey", jwk).put("grantTypes", JSONArray(grantTypes))
            .put("clientAuthMethods", JSONArray().put("private_key_jwt"))
            .put("additionalConfig", additionalConfig))
        return Http.request("POST", "$issuer/client-mgmt/client", body = body.toString())
    }

    fun updateAdditionalConfig(issuer: String, clientId: String, additionalConfig: JSONObject): HttpResult {
        val body = JSONObject().put("requestTime", requestTime())
            .put("request", JSONObject().put("additionalConfig", additionalConfig))
        return Http.request("PATCH", "$issuer/client-mgmt/client/$clientId", body = body.toString())
    }

    /** Seeds a mock identity (eSignet mock identity system) the OTP login can use. */
    fun createMockIdentity(mockIdentityUrl: String, uin: String, fullName: String): HttpResult {
        fun lang(v: String) = JSONArray().put(JSONObject().put("language", "eng").put("value", v))
        val body = JSONObject().put("requestTime", requestTime()).put("request", JSONObject()
            .put("individualId", uin).put("pin", "111111").put("password", "Mosip@123")
            .put("email", "asha.devi@example.com").put("phone", "+919876543210")
            .put("fullName", lang(fullName)).put("nickName", lang(fullName)).put("preferredUsername", lang(fullName))
            .put("givenName", lang(fullName.substringBefore(' '))).put("middleName", lang("K"))
            .put("familyName", lang(fullName.substringAfter(' '))).put("gender", lang("Female"))
            .put("dateOfBirth", "1990/01/01").put("streetAddress", lang("12 MG Road")).put("locality", lang("Bengaluru"))
            .put("region", lang("Karnataka")).put("country", lang("India")).put("postalCode", "560001")
            .put("preferredLang", "eng").put("locale", "en").put("zoneInfo", "Asia/Kolkata")
            .put("encodedPhoto", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR4nGM4YWQERAwQCgAjNgSxRnwWMwAAAABJRU5ErkJggg=="))
        return Http.request("POST", "$mockIdentityUrl/v1/mock-identity-system/identity", body = body.toString())
    }
}
