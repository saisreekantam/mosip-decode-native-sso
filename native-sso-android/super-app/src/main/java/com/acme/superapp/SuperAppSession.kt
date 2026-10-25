package com.acme.superapp

import android.content.Context
import android.net.Uri
import com.acme.sso.core.DevProvisioning
import com.acme.sso.core.EsignetClient
import com.acme.sso.core.Jose
import com.acme.sso.core.KeystoreKey
import com.acme.sso.core.NativeSso
import com.acme.sso.core.PendingLogin
import com.acme.sso.core.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Super App's sign-in state. The ID token and device secret live in Keystore-encrypted storage
 * and key A (the device key) never leaves the Keystore; sibling apps reach them only through
 * [SsoService].
 */
class SuperAppSession private constructor(context: Context) {
    private val store = SecureStore(context, "super_app")
    val deviceKey = KeystoreKey.deviceKey()
    private val clientKey = KeystoreKey.clientKey()
    val clientId = "acme-super-app-" + clientKey.thumbprint.filter(Char::isLetterOrDigit).take(10).lowercase()
    val client = EsignetClient(clientId, REDIRECT_URI, clientKey)

    val idToken: String? get() = store.get("id_token")
    val deviceSecret: String? get() = store.get("device_secret")
    val accessToken: String? get() = store.get("access_token")
    val signedIn: Boolean get() = deviceSecret != null
    val siblings: List<String>
        get() = store.get("siblings")?.let { JSONArray(it) }?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()

    private fun additionalConfig(siblings: List<String>) = JSONObject()
        .put("require_pushed_authorization_requests", true)
        .put("dpop_bound_access_tokens", true)
        .put("require_pkce", true)
        .put("allowed_authorization_scopes", JSONArray().put(NativeSso.SCOPE_DEVICE_SSO))
        .put("native_sso_allowed_client_ids", JSONArray(siblings))

    /** Registers this install with eSignet (local development provisioning). */
    fun ensureRegistered(): String {
        if (store.get("registered") == "1") return "Registered as $clientId"
        val res = DevProvisioning.register(client.issuer, clientId, "Acme Super App", RP_ID, REDIRECT_URI, clientKey,
            listOf("authorization_code"), additionalConfig(siblings))
        val duplicate = res.body.contains("duplicate_client_id")
        check(res.status == 200 && (!res.body.contains("errorCode") || duplicate)) { "Registration failed: ${res.body}" }
        store.put("registered", "1")
        return "Registered $clientId under relying party $RP_ID"
    }

    /** Adds a sibling app to the Native SSO allow-list (vendor onboarding, done on first bind in dev). */
    @Synchronized
    fun enroll(siblingClientId: String): Boolean {
        if (siblingClientId in siblings) return true
        val updated = siblings + siblingClientId
        val res = DevProvisioning.updateAdditionalConfig(client.issuer, clientId, additionalConfig(updated))
        if (res.status != 200 || res.body.contains("errorCode")) return false
        store.put("siblings", JSONArray(updated).toString())
        return true
    }

    fun startLogin(): PendingLogin {
        val pending = client.pushAuthorization("openid profile email ${NativeSso.SCOPE_DEVICE_SSO}", deviceKey)
        store.put("pending", JSONObject().put("verifier", pending.codeVerifier).put("state", pending.state).toString())
        return pending
    }

    /** Completes the login from the redirect URI; returns a human-readable summary. */
    fun completeLogin(redirect: Uri): String {
        val pendingJson = JSONObject(store.get("pending") ?: error("no login in progress"))
        check(redirect.getQueryParameter("state") == pendingJson.getString("state")) { "state mismatch" }
        redirect.getQueryParameter("error")?.let { error("Login failed: $it ${redirect.getQueryParameter("error_description").orEmpty()}") }
        val code = redirect.getQueryParameter("code") ?: error("no authorization code")
        val pending = PendingLogin(pendingJson.getString("verifier"), pendingJson.getString("state"), "")
        val res = client.exchangeCode(code, pending, deviceKey)
        val json = res.json
        check(res.status == 200 && json != null) { "Token request failed: ${res.status} ${res.body}" }
        val ds = json.optString("device_secret")
        check(ds.isNotEmpty()) { "eSignet did not return a device_secret" }
        store.put("id_token", json.getString("id_token"))
        store.put("device_secret", ds)
        store.put("access_token", json.getString("access_token"))
        store.put("pending", null)
        val claims = Jose.payload(json.getString("id_token"))
        return "Signed in. sid=${claims.optString("sid").take(13)}…, ds_hash=${claims.optString("ds_hash").take(10)}…, " +
            "device_secret is a ${ds.count { it == '.' } + 1}-part JWE"
    }

    fun profile(): JSONObject? {
        val token = accessToken ?: return null
        val res = client.userinfo(token, deviceKey.dpopProof("GET", client.userinfoEndpoint, token))
        return if (res.status == 200) EsignetClient.userinfoClaims(res) else null
    }

    /** Ends the SSO session for every app of the family and forgets the local session. */
    fun logoutEverywhere(): String {
        val ds = deviceSecret ?: return "Not signed in"
        val res = client.logoutEverywhere(ds)
        store.put("id_token", null); store.put("device_secret", null); store.put("access_token", null)
        val clients = res.json?.optJSONArray("signed_out_clients")
        return if (res.status == 200) "Signed out of ${clients?.length() ?: 0} app(s): $clients" else "Logout: ${res.status} ${res.body}"
    }

    companion object {
        const val REDIRECT_URI = "com.acme.superapp://oauth/callback"
        const val RP_ID = "acme-health"

        @Volatile private var instance: SuperAppSession? = null
        fun get(context: Context): SuperAppSession =
            instance ?: synchronized(this) { instance ?: SuperAppSession(context.applicationContext).also { instance = it } }
    }
}
