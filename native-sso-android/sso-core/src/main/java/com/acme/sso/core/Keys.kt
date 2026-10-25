package com.acme.sso.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.UUID

/** A non-exportable Android Keystore key: callers can sign with it but never read it. */
class KeystoreKey private constructor(private val alias: String, val alg: String) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val publicJwk: JSONObject
        get() = when (val pub = keyStore.getCertificate(alias).publicKey) {
            is ECPublicKey -> Jose.ecJwk(pub)
            is RSAPublicKey -> Jose.rsaJwk(pub)
            else -> error("unsupported key ${pub.algorithm}")
        }

    val thumbprint: String get() = Jose.thumbprint(publicJwk)

    /** True when the key lives in a StrongBox secure element rather than the TEE. */
    var strongBox: Boolean = false
        private set

    fun sign(input: ByteArray): ByteArray {
        val key = keyStore.getKey(alias, null) as PrivateKey
        return if (alg == "ES256") {
            Jose.derToJose(Signature.getInstance("SHA256withECDSA").run { initSign(key); update(input); sign() })
        } else {
            Signature.getInstance("SHA256withRSA/PSS").run { initSign(key); update(input); sign() }
        }
    }

    /** A DPoP proof (RFC 9449) for [htm] [htu], bound to [accessToken] when given. */
    fun dpopProof(htm: String, htu: String, accessToken: String? = null): String {
        val claims = JSONObject()
            .put("jti", UUID.randomUUID().toString()).put("htm", htm).put("htu", htu)
            .put("iat", System.currentTimeMillis() / 1000)
        if (accessToken != null) claims.put("ath", Jose.sha256B64(accessToken))
        val header = JSONObject().put("typ", "dpop+jwt").put("alg", alg).put("jwk", publicJwk)
        return Jose.signedJwt(header, claims, ::sign)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** The device key (key A): EC P-256, preferring StrongBox, used for DPoP. */
        fun deviceKey(alias: String = "native_sso_device_key"): KeystoreKey = obtain(alias, "ES256") { strongBox ->
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setIsStrongBoxBacked(strongBox)
                        .build(),
                )
            }.generateKeyPair()
        }

        /** The OIDC client authentication key: RSA-2048 for PS256 private_key_jwt. */
        fun clientKey(alias: String = "oidc_client_key"): KeystoreKey = obtain(alias, "PS256") { strongBox ->
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                        .setIsStrongBoxBacked(strongBox)
                        .build(),
                )
            }.generateKeyPair()
        }

        private fun obtain(alias: String, alg: String, generate: (Boolean) -> Unit): KeystoreKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            var strongBox = false
            if (!ks.containsAlias(alias)) {
                strongBox = try {
                    generate(true); true
                } catch (e: StrongBoxUnavailableException) {
                    generate(false); false
                } catch (e: Exception) {
                    generate(false); false
                }
            }
            return KeystoreKey(alias, alg).also { it.strongBox = strongBox || isStrongBox(alias) }
        }

        private fun isStrongBox(alias: String): Boolean = try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = ks.getKey(alias, null) as PrivateKey
            val factory = java.security.KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, android.security.keystore.KeyInfo::class.java)
            android.os.Build.VERSION.SDK_INT >= 31 &&
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } catch (e: Exception) {
            false
        }
    }
}
