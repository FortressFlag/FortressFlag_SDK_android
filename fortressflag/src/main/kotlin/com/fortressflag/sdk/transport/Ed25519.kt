package com.fortressflag.sdk.transport

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Verify-only pure Ed25519 (RFC 8032 §5.1), vendored so the SDK keeps its single runtime
 * dependency (ADR-0013) and one code path on every API level — the platform `Ed25519`
 * provider exists only from API 33, and a try-platform-then-vendored split would leave one
 * half untested on any given CI device. Field arithmetic is `BigInteger`: one verification
 * per poll, a few milliseconds, never on the flag-read path.
 *
 * Group equation: the plain `[S]B = R + [k]A` (the RFC's permitted stricter form, the one
 * Go's `crypto/ed25519` — the backend's signer family — also uses), not the cofactored
 * `[8][S]B = [8]R + [8][k]A`. Non-canonical inputs fail: `S ≥ L`, a public key or `R` that
 * does not decompress, a wrong-length key or signature. Pinned to the RFC §7.1 vectors in
 * Ed25519Test; a change here that passes those but alters an edge case is a contract change.
 */
internal object Ed25519 {
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    // TWO is API 33+; minSdk is 26.
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger =
        TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger =
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

    /** sqrt(-1) mod p, used when the first candidate root is wrong (§5.1.3 step 3). */
    private val SQRT_MINUS_ONE: BigInteger =
        TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val SQRT_EXPONENT: BigInteger = P.add(BigInteger.valueOf(3)).shiftRight(3)

    /** Extended twisted-Edwards coordinates (§5.1.4): x = X/Z, y = Y/Z, x*y = T/Z. */
    private class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** The base point B: y = 4/5, x positive (§5.1). */
    private val BASE: Point =
        checkNotNull(
            decompress(
                encodeY(BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P), false),
            ),
        ) { "base point" }

    /**
     * True iff [signature] is a valid pure-Ed25519 signature by [publicKey] over [message].
     * Never throws: every malformed input is simply `false`.
     */
    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        // §8.1 malleability: S must be canonical, below the group order.
        if (s >= L) return false

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(rBytes)
        digest.update(publicKey)
        digest.update(message)
        val k = littleEndian(digest.digest()).mod(L)

        val lhs = scalarMultiply(s, BASE)
        val rhs = add(r, scalarMultiply(k, a))
        return compress(lhs).contentEquals(compress(rhs))
    }

    // --- point decoding / encoding (§5.1.3, §5.1.2) ---

    private fun decompress(encoded: ByteArray): Point? {
        val yBytes = encoded.copyOf()
        val xSign = (yBytes[31].toInt() and 0x80) != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = littleEndian(yBytes)
        if (y >= P) return null

        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        var x = x2.modPow(SQRT_EXPONENT, P)
        if (x.multiply(x).mod(P) != x2) {
            x = x.multiply(SQRT_MINUS_ONE).mod(P)
            if (x.multiply(x).mod(P) != x2) return null
        }
        if (x.signum() == 0 && xSign) return null
        if (x.testBit(0) != xSign) x = P.subtract(x)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun compress(p: Point): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        return encodeY(y, x.testBit(0))
    }

    private fun encodeY(
        y: BigInteger,
        xOdd: Boolean,
    ): ByteArray {
        val out = ByteArray(32)
        val big = y.toByteArray() // big-endian, possibly with a leading zero sign byte
        var i = big.size - 1
        var j = 0
        while (i >= 0 && j < 32) {
            out[j] = big[i]
            i--
            j++
        }
        if (xOdd) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    // --- group arithmetic (§5.1.4) ---

    private fun add(
        p: Point,
        q: Point,
    ): Point {
        val a =
            p.y
                .subtract(p.x)
                .multiply(q.y.subtract(q.x))
                .mod(P)
        val b =
            p.y
                .add(p.x)
                .multiply(q.y.add(q.x))
                .mod(P)
        val c =
            p.t
                .multiply(TWO_D)
                .multiply(q.t)
                .mod(P)
        val d =
            p.z
                .multiply(q.z)
                .shiftLeft(1)
                .mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun double(p: Point): Point {
        val a = p.x.multiply(p.x).mod(P)
        val b = p.y.multiply(p.y).mod(P)
        val c =
            p.z
                .multiply(p.z)
                .shiftLeft(1)
                .mod(P)
        val h = a.add(b)
        val xy = p.x.add(p.y)
        val e = h.subtract(xy.multiply(xy)).mod(P)
        val g = a.subtract(b)
        val f = c.add(g)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    /** Plain double-and-add; timing is irrelevant for verification of public data. */
    private fun scalarMultiply(
        scalar: BigInteger,
        p: Point,
    ): Point {
        var result = IDENTITY
        var base = p
        for (i in 0 until scalar.bitLength()) {
            if (scalar.testBit(i)) result = add(result, base)
            base = double(base)
        }
        return result
    }
}
