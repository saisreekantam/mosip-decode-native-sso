package com.acme.sso.core

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/** Minimal JOSE helpers: base64url, JWK thumbprints and compact JWS assembly. */
object Jose {
    var b64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }
    var b64Decoder: (String) -> ByteArray = { Base64.decode(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    fun b64url(bytes: ByteArray): String = b64Encoder(bytes)
    fun b64urlDecode(s: String): ByteArray = b64Decoder(s)

    fun sha256B64(s: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    /** Unsigned big-endian bytes of [n], left-padded to [size]. */
    fun fixed(n: BigInteger, size: Int): ByteArray {
        val raw = n.toByteArray().let { if (it.size > size && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return ByteArray(size - raw.size) + raw
    }

    fun ecJwk(key: ECPublicKey): JSONObject = JSONObject()
        .put("kty", "EC").put("crv", "P-256")
        .put("x", b64url(fixed(key.w.affineX, 32)))
        .put("y", b64url(fixed(key.w.affineY, 32)))

    fun rsaJwk(key: RSAPublicKey): JSONObject {
        val n = key.modulus.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return JSONObject().put("kty", "RSA").put("n", b64url(n)).put("e", b64url(key.publicExponent.toByteArray()))
    }

    /** RFC 7638 thumbprint over the required members in lexicographic order. */
    fun thumbprint(jwk: JSONObject): String = when (jwk.getString("kty")) {
        "EC" -> sha256B64("""{"crv":"${jwk.getString("crv")}","kty":"EC","x":"${jwk.getString("x")}","y":"${jwk.getString("y")}"}""")
        else -> sha256B64("""{"e":"${jwk.getString("e")}","kty":"RSA","n":"${jwk.getString("n")}"}""")
    }

    /** Converts an ASN.1 DER ECDSA signature (what JCA returns) to the JOSE R||S form. */
    fun derToJose(der: ByteArray, size: Int = 32): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        require(der[i] == 0x02.toByte()) { "not a DER ECDSA signature" }
        val rLen = der[i + 1].toInt()
        val r = BigInteger(1, der.copyOfRange(i + 2, i + 2 + rLen))
        i += 2 + rLen
        require(der[i] == 0x02.toByte()) { "not a DER ECDSA signature" }
        val sLen = der[i + 1].toInt()
        val s = BigInteger(1, der.copyOfRange(i + 2, i + 2 + sLen))
        return fixed(r, size) + fixed(s, size)
    }

    fun signedJwt(header: JSONObject, claims: JSONObject, sign: (ByteArray) -> ByteArray): String {
        val input = b64url(header.toString().toByteArray()) + "." + b64url(claims.toString().toByteArray())
        return input + "." + b64url(sign(input.toByteArray()))
    }

    /** Decodes a compact JWS payload without verifying it (for display and local checks only). */
    fun payload(jwt: String): JSONObject {
        val parts = jwt.split(".")
        require(parts.size == 3) { "not a JWS" }
        return JSONObject(String(b64urlDecode(parts[1])))
    }
}
