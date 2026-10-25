package com.acme.pharmacy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.acme.sso.core.DevProvisioning
import com.acme.sso.core.EsignetClient
import com.acme.sso.core.Jose
import com.acme.sso.core.KeystoreKey
import com.acme.sso.core.NativeSso
import com.acme.sso.core.PendingLogin
import com.acme.sso.core.SecureStore
import com.acme.sso.core.SsoConnection
import com.acme.sso.core.SsoProtocol
import org.json.JSONObject

/** One step of a sign-in, shown to the user as the trust trace. */
data class TraceStep(val ok: Boolean, val title: String, val detail: String)

/** Outcome of a Native SSO attempt. */
sealed interface SsoOutcome {
    data class SignedIn(val trace: List<TraceStep>, val claims: JSONObject) : SsoOutcome
    data class ConsentRequired(val trace: List<TraceStep>, val missing: String) : SsoOutcome
    data class Failed(val trace: List<TraceStep>) : SsoOutcome
}

/**
 * The Child App's sign-in state. Signing in through Native SSO, its access token is bound to the
 * Super App's key A, so every request it makes needs a DPoP proof the Super App signs for it.
 * Signing in interactively (re-consent), it uses its own device key instead.
 */
class ChildSession private constructor(private val context: Context) {
    private val store = SecureStore(context, "pharmacy")
    private val ownKey = KeystoreKey.deviceKey("pharmacy_device_key")
    private val clientKey = KeystoreKey.clientKey("pharmacy_client_key")
    val clientId = "acme-pharmacy-" + clientKey.thumbprint.filter(Char::isLetterOrDigit).take(10).lowercase()
    val client = EsignetClient(clientId, REDIRECT_URI, clientKey)

    val accessToken: String? get() = store.get("access_token")
    /** "sso" when the token is bound to the Super App's key, "own" after an interactive login. */
    val mode: String? get() = store.get("mode")

    fun ensureRegistered(): String {
        if (store.get("registered") == "1") return "Registered as $clientId"
        val res = DevProvisioning.register(client.issuer, clientId, "Acme Pharmacy", RP_ID, REDIRECT_URI, clientKey,
            listOf("authorization_code", NativeSso.GRANT_TOKEN_EXCHANGE), JSONObject()
                .put("require_pushed_authorization_requests", true)
                .put("dpop_bound_access_tokens", true).put("require_pkce", true))
        val duplicate = res.body.contains("duplicate_client_id")
        check(res.status == 200 && (!res.body.contains("errorCode") || duplicate)) { "Registration failed: ${res.body}" }
        store.put("registered", "1")
        return "Registered $clientId under relying party $RP_ID"
    }

    /** Silent sign-in through the Super App (Native SSO token exchange). */
    suspend fun signInWithSso(scope: String): SsoOutcome {
        val trace = mutableListOf<TraceStep>()
        val sso = try {
            SsoConnection.bind(context).also {
                trace += TraceStep(true, "Bound the Super App's SSO service", "OS verified both apps share the vendor signing certificate")
            }
        } catch (e: SecurityException) {
            trace += TraceStep(false, "Android refused to connect to the Super App",
                "this app is not signed by Acme, so it cannot use your Acme sign-in")
            return SsoOutcome.Failed(trace)
        } catch (e: Exception) {
            trace += TraceStep(false, "Could not reach the Super App", e.message ?: e.toString())
            return SsoOutcome.Failed(trace)
        }
        try {
            val enroll = sso.request(SsoProtocol.MSG_ENROLL, Bundle().apply { putString(SsoProtocol.KEY_CLIENT_ID, clientId) })
            enroll.getString(SsoProtocol.KEY_ERROR)?.let { trace += TraceStep(false, "Enrollment", it); return SsoOutcome.Failed(trace) }

            val bundle = sso.request(SsoProtocol.MSG_GET_BUNDLE)
            bundle.getString(SsoProtocol.KEY_ERROR)?.let {
                trace += TraceStep(false, "No SSO session", it); return SsoOutcome.Failed(trace)
            }
            val idToken = bundle.getString(SsoProtocol.KEY_ID_TOKEN)!!
            val deviceSecret = bundle.getString(SsoProtocol.KEY_DEVICE_SECRET)!!
            val superClient = bundle.getString(SsoProtocol.KEY_SUPER_CLIENT_ID).orEmpty()
            val id = Jose.payload(idToken)
            trace += TraceStep(true, "Received the SSO bundle from $superClient",
                "id_token sid=${id.optString("sid").take(13)}…  ds_hash=${id.optString("ds_hash").take(10)}…  " +
                    "device_secret: opaque ${deviceSecret.count { it == '.' } + 1}-part JWE")

            val proof = sso.request(SsoProtocol.MSG_SIGN_DPOP, Bundle().apply {
                putString(SsoProtocol.KEY_HTM, "POST"); putString(SsoProtocol.KEY_HTU, client.tokenEndpoint)
            })
            val jkt = proof.getString(SsoProtocol.KEY_JKT).orEmpty()
            trace += TraceStep(true, "Super App signed a DPoP proof with key A", "jkt=${jkt.take(16)}…  (the key never left the Super App)")

            val started = System.currentTimeMillis()
            val res = client.nativeSsoExchange(idToken, deviceSecret, scope, proof.getString(SsoProtocol.KEY_PROOF)!!)
            val ms = System.currentTimeMillis() - started
            if (res.status != 200) {
                val hint = if (res.errorDescription.contains("cannot be redeemed")) {
                    "\nThis app already used this Acme sign-in once (replay protection). " +
                        "Log out of all Acme apps in the Super App and sign in again."
                } else ""
                trace += TraceStep(false, "eSignet rejected the token exchange (${res.status})",
                    "${res.error}: ${res.errorDescription}$hint")
                return if (res.error == "interaction_required") {
                    SsoOutcome.ConsentRequired(trace, res.errorDescription.substringAfter(": ", ""))
                } else SsoOutcome.Failed(trace)
            }
            val token = res.json!!.getString("access_token")
            val at = Jose.payload(token)
            trace += TraceStep(true, "eSignet issued a token in $ms ms, no OTP and no consent screen",
                "token_type=${res.json!!.optString("token_type")}  cnf.jkt=key A: ${at.optJSONObject("cnf")?.optString("jkt") == jkt}  " +
                    "act.sub=${at.optJSONObject("act")?.optString("sub")}")
            store.put("access_token", token); store.put("mode", "sso")

            val claims = fetchProfile(sso, token, trace) ?: return SsoOutcome.Failed(trace)
            return SsoOutcome.SignedIn(trace, claims)
        } finally {
            sso.close()
        }
    }

    /** Calls userinfo with the current token; shows that a logout in the Super App cuts it off. */
    suspend fun refreshProfile(): SsoOutcome {
        val trace = mutableListOf<TraceStep>()
        val token = accessToken ?: return SsoOutcome.Failed(listOf(TraceStep(false, "Not signed in", "")))
        if (mode == "own") {
            val res = client.userinfo(token, ownKey.dpopProof("GET", client.userinfoEndpoint, token))
            return profileOutcome(res, trace)
        }
        val sso = try { SsoConnection.bind(context) } catch (e: Exception) {
            return SsoOutcome.Failed(listOf(TraceStep(false, "Bind refused", e.message.orEmpty())))
        }
        try {
            val claims = fetchProfile(sso, token, trace) ?: return SsoOutcome.Failed(trace)
            return SsoOutcome.SignedIn(trace, claims)
        } finally {
            sso.close()
        }
    }

    private suspend fun fetchProfile(sso: SsoConnection, token: String, trace: MutableList<TraceStep>): JSONObject? {
        val proof = sso.request(SsoProtocol.MSG_SIGN_DPOP, Bundle().apply {
            putString(SsoProtocol.KEY_HTM, "GET"); putString(SsoProtocol.KEY_HTU, client.userinfoEndpoint)
            putString(SsoProtocol.KEY_ACCESS_TOKEN, token)
        }).getString(SsoProtocol.KEY_PROOF)!!
        val res = client.userinfo(token, proof)
        return (profileOutcome(res, trace) as? SsoOutcome.SignedIn)?.claims
    }

    private fun profileOutcome(res: com.acme.sso.core.HttpResult, trace: MutableList<TraceStep>): SsoOutcome {
        if (res.status != 200) {
            trace += TraceStep(false, "userinfo rejected (${res.status})",
                if (res.status == 401) "the token was cut off: the SSO session has ended" else res.body.take(160))
            if (res.status == 401) { store.put("access_token", null); store.put("mode", null) }
            return SsoOutcome.Failed(trace)
        }
        val claims = EsignetClient.userinfoClaims(res)
        trace += TraceStep(true, "userinfo returned the consented claims",
            "aud=${claims.optString("aud")}  claims=${claims.keys().asSequence().filter { it !in REGISTERED }.toList()}")
        return SsoOutcome.SignedIn(trace, claims)
    }

    /** Bonus: interactive login with prompt=consent when the original consent does not cover the request. */
    fun startConsentLogin(scope: String): PendingLogin {
        val pending = client.pushAuthorization(scope, ownKey, prompt = "consent")
        store.put("pending", JSONObject().put("verifier", pending.codeVerifier).put("state", pending.state).toString())
        return pending
    }

    fun completeConsentLogin(redirect: Uri): SsoOutcome {
        val trace = mutableListOf<TraceStep>()
        val pendingJson = JSONObject(store.get("pending") ?: error("no login in progress"))
        check(redirect.getQueryParameter("state") == pendingJson.getString("state")) { "state mismatch" }
        val code = redirect.getQueryParameter("code") ?: error(redirect.getQueryParameter("error") ?: "no code")
        val res = client.exchangeCode(code, PendingLogin(pendingJson.getString("verifier"), "", ""), ownKey)
        check(res.status == 200) { "Token request failed: ${res.status} ${res.body}" }
        val token = res.json!!.getString("access_token")
        store.put("access_token", token); store.put("mode", "own"); store.put("pending", null)
        trace += TraceStep(true, "Signed in interactively and recorded this app's own consent", "token bound to the Pharmacy app's own key")
        return profileOutcome(client.userinfo(token, ownKey.dpopProof("GET", client.userinfoEndpoint, token)), trace)
    }

    fun signOut() { store.put("access_token", null); store.put("mode", null) }

    companion object {
        const val REDIRECT_URI = "com.acme.pharmacy://oauth/callback"
        const val RP_ID = "acme-health"
        private val REGISTERED = setOf("iss", "aud", "sub", "exp", "iat", "nbf", "jti")

        @Volatile private var instance: ChildSession? = null
        fun get(context: Context): ChildSession =
            instance ?: synchronized(this) { instance ?: ChildSession(context.applicationContext).also { instance = it } }
    }
}
