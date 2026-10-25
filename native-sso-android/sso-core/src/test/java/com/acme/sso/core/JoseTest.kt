package com.acme.sso.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class JoseTest {
    @Before
    fun useJvmBase64() {
        Jose.b64Encoder = { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        Jose.b64Decoder = { Base64.getUrlDecoder().decode(it) }
    }

    @Test
    fun derSignatureConvertsToVerifiableJoseForm() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(50) {
            val input = "header.payload-$it".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(input); sign() }
            val jose = Jose.derToJose(der)
            assertEquals(64, jose.size)
            // Round-trip: the P1363 (R||S) form must verify with the same key.
            val ok = Signature.getInstance("SHA256withECDSAinP1363Format").run { initVerify(kp.public); update(input); verify(jose) }
            assertTrue("signature $it did not verify after conversion", ok)
        }
    }

    @Test
    fun ecThumbprintFollowsRfc7638() {
        // RFC 7638 §3.1 lists members in lexicographic order without whitespace.
        val jwk = JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU").put("y", "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")
        assertEquals(Jose.sha256B64("""{"crv":"P-256","kty":"EC","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}"""),
            Jose.thumbprint(jwk))
    }

    @Test
    fun rsaThumbprintMatchesRfc7638Example() {
        // RFC 7638 §3.1 example key and its published thumbprint.
        val jwk = JSONObject().put("kty", "RSA").put("e", "AQAB").put("n",
            "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw")
        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", Jose.thumbprint(jwk))
    }

    @Test
    fun ecJwkCoordinatesAreFixedWidth() {
        repeat(20) {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val jwk = Jose.ecJwk(kp.public as ECPublicKey)
            assertEquals(32, Jose.b64urlDecode(jwk.getString("x")).size)
            assertEquals(32, Jose.b64urlDecode(jwk.getString("y")).size)
        }
    }

    @Test
    fun fixedPadsAndStripsSignByte() {
        assertArrayEquals(byteArrayOf(0, 0, 1), Jose.fixed(BigInteger.ONE, 3))
        assertArrayEquals(ByteArray(32) { 0xff.toByte() }, Jose.fixed(BigInteger(1, ByteArray(32) { 0xff.toByte() }), 32))
    }
}
