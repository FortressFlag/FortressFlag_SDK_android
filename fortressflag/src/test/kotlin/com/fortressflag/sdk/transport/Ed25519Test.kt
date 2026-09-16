package com.fortressflag.sdk.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * RFC 8032 §7.1 vectors — public key, message and signature only; the RFC's secret keys are
 * never copied into this repository. Plus the non-canonical rejections the RFC vectors do not
 * cover (trap: malleability).
 */
class Ed25519Test {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class Vector(
        val name: String,
        val publicKey: String,
        val message: String,
        val signature: String,
    )

    private val vectors =
        listOf(
            Vector(
                "TEST 1",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Vector(
                "TEST 2",
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
            Vector(
                "TEST 3",
                "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            ),
            Vector(
                "TEST SHA(abc)",
                "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf",
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704",
            ),
        )

    @Test
    fun rfc8032Section71VectorsVerify() {
        for (v in vectors) {
            assertTrue(v.name, Ed25519.verify(hex(v.publicKey), hex(v.message), hex(v.signature)))
        }
    }

    @Test
    fun aFlippedSignatureBitFails() {
        for (v in vectors) {
            val sig = hex(v.signature)
            sig[7] = (sig[7].toInt() xor 0x01).toByte()
            assertFalse(v.name + " R bit", Ed25519.verify(hex(v.publicKey), hex(v.message), sig))
            val sig2 = hex(v.signature)
            sig2[40] = (sig2[40].toInt() xor 0x01).toByte()
            assertFalse(v.name + " S bit", Ed25519.verify(hex(v.publicKey), hex(v.message), sig2))
        }
    }

    @Test
    fun aFlippedMessageByteFails() {
        for (v in vectors.drop(1)) {
            val msg = hex(v.message)
            msg[0] = (msg[0].toInt() xor 0x80).toByte()
            assertFalse(v.name, Ed25519.verify(hex(v.publicKey), msg, hex(v.signature)))
        }
        assertFalse("TEST 1 with a byte appended", Ed25519.verify(hex(vectors[0].publicKey), byteArrayOf(0), hex(vectors[0].signature)))
    }

    @Test
    fun nonCanonicalSPlusLIsRejected() {
        // S + L encodes the same residue mod L; a verifier that reduces S first would accept
        // it, and the signature would be malleable (RFC 8032 §8.1). Ours must not.
        val v = vectors[0]
        val sig = hex(v.signature)
        val l = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
        val s = BigInteger(1, sig.copyOfRange(32, 64).reversedArray())
        val sPlusL = s.add(l)
        assertTrue("fits 32 bytes", sPlusL.bitLength() <= 256)
        val encoded = sPlusL.toByteArray().reversedArray().copyOf(32)
        System.arraycopy(encoded, 0, sig, 32, 32)
        assertFalse(Ed25519.verify(hex(v.publicKey), hex(v.message), sig))
    }

    @Test
    fun nonDecompressibleInputsAreRejected() {
        val v = vectors[0]
        // y = p - 18 has no square root for x under the curve equation... rather than rely on
        // that, use y ≥ p, which decompression must refuse outright (top bit clear, all 0x7F
        // in the high byte → 2^255 - 1 > p).
        val badKey = ByteArray(32) { 0xFF.toByte() }
        badKey[31] = 0x7F
        assertFalse("public key with y >= p", Ed25519.verify(badKey, hex(v.message), hex(v.signature)))
        val badR = hex(v.signature)
        for (i in 0 until 32) badR[i] = 0xFF.toByte()
        badR[31] = 0x7F
        assertFalse("R with y >= p", Ed25519.verify(hex(v.publicKey), hex(v.message), badR))
        // x = 0 with the sign bit set is not a valid encoding either (§5.1.3 step 4).
        val zeroWithSign = ByteArray(32)
        zeroWithSign[0] = 0x01 // y = 1 → x = 0
        zeroWithSign[31] = 0x80.toByte()
        assertFalse("x = 0 with sign bit", Ed25519.verify(zeroWithSign, hex(v.message), hex(v.signature)))
    }

    @Test
    fun wrongLengthsAreRejectedNotThrown() {
        val v = vectors[0]
        assertFalse(Ed25519.verify(hex(v.publicKey).copyOf(31), hex(v.message), hex(v.signature)))
        assertFalse(Ed25519.verify(hex(v.publicKey), hex(v.message), hex(v.signature).copyOf(63)))
        assertFalse(Ed25519.verify(ByteArray(0), ByteArray(0), ByteArray(0)))
    }
}
