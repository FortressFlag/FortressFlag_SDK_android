package com.fortressflag.sdk.tags

import com.fortressflag.sdk.support.Base64Url
import com.fortressflag.sdk.support.Log

/**
 * Device tags (contract v1, `X-FF-Tags`; backend ADR-0004): the values this device reports
 * with every fetch, which the server evaluates targeting rules against. Tags are
 * request-scoped on the server by decision — evaluated and forgotten, never persisted, never
 * logged — but they still transit, so the same rule applies here as everywhere else in this
 * SDK: a tag VALUE may carry anything the customer put in it and never appears in a log
 * line. A tag KEY is the customer's own configuration, loggable when it is well-formed.
 */
internal object Tags {
    /** The request header the merged tag set travels in. */
    const val HEADER_NAME = "X-FF-Tags"

    /**
     * The caps, shared verbatim with the server (contract v1). The server answers 400 to a
     * violation because the SDK enforces the same caps here first — a request the server
     * sees over the caps came from a broken client.
     */
    const val MAX_COUNT = 32
    const val MAX_KEY_LENGTH = 64
    const val MAX_VALUE_BYTES = 256
    const val MAX_DOCUMENT_BYTES = 4096

    /** 1–64 characters of `A–Z a–z 0–9 . _ -`. */
    fun isValidKey(key: String): Boolean {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return false
        return key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
    }

    /**
     * Validates customer-supplied tags, dropping what the contract cannot carry.
     *
     * Dropping, not throwing and not truncating: no SDK call throws (Founding §8.1), and a
     * truncated value would silently match different rules than the one the customer set.
     * Each drop logs a warning naming the KEY only — a key that itself failed the charset
     * check is arbitrary text and is logged as a placeholder instead.
     *
     * A customer key colliding with a [reserved] built-in is dropped: built-ins are
     * mechanically derived truths about the device, and letting configuration overwrite
     * "what version is this app?" would make every version rule lie.
     */
    fun sanitize(
        custom: Map<String, String>,
        reserved: Set<String>,
        log: Log,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(custom.size)
        for ((key, value) in custom) {
            if (!isValidKey(key)) {
                log.warning("dropping tag with a malformed key (1-64 chars of A-Za-z0-9._- required)")
                continue
            }
            if (key in reserved) {
                log.warning("dropping tag '$key': it collides with a built-in tag the SDK sends itself")
                continue
            }
            if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
                log.warning("dropping tag '$key': its value exceeds $MAX_VALUE_BYTES bytes")
                continue
            }
            out[key] = value
        }
        return out
    }

    /**
     * Merges built-ins over custom tags and encodes the result as the header value: unpadded
     * base64url of a JSON object, **keys sorted**. Returns null when there is nothing to
     * send (an absent header means no tags: only default states serve).
     *
     * Deterministic on purpose, and the reason is M3, not tidiness: the ETag design makes a
     * byte-identical request cheap, and a map serialised in iteration order would produce a
     * different header — and a different cache identity — on every launch for the same tags.
     *
     * Over the count or document caps, CUSTOM tags are shed from the end of the sorted order
     * until it fits, each drop logged by key. Built-ins always survive: they are small,
     * bounded, and the ones rules most depend on.
     */
    fun encode(
        builtin: Map<String, String>,
        custom: Map<String, String>,
        log: Log,
    ): String? {
        val kept = custom.toSortedMap()

        val overCount = builtin.size + kept.size - MAX_COUNT
        if (overCount > 0) {
            for (key in kept.keys.toList().takeLast(overCount)) {
                log.warning("dropping tag '$key': more than $MAX_COUNT tags")
                kept.remove(key)
            }
        }

        while (true) {
            val merged = sortedMapOf<String, String>()
            merged.putAll(kept)
            merged.putAll(builtin) // built-ins win a collision, though sanitize prevents one
            if (merged.isEmpty()) return null
            val document = serialize(merged)
            if (document.size <= MAX_DOCUMENT_BYTES) {
                return Base64Url.encode(document)
            }
            val last = kept.keys.lastOrNull()
            if (last == null) {
                log.warning("built-in tags alone exceed the document cap; sending none")
                return null
            }
            log.warning("dropping tag '$last': the encoded tag document exceeds $MAX_DOCUMENT_BYTES bytes")
            kept.remove(last)
        }
    }

    /**
     * JSON with sorted keys, hand-assembled from a sorted map. org.json's JSONObject does
     * not promise key order, and determinism here is a contract requirement, so the
     * serialisation is explicit — escaping delegated to org.json's JSONObject.quote, which
     * is the platform's own string escaper.
     */
    private fun serialize(sorted: java.util.SortedMap<String, String>): ByteArray {
        val builder = StringBuilder(256)
        builder.append('{')
        var first = true
        for ((key, value) in sorted) {
            if (!first) builder.append(',')
            first = false
            builder.append(org.json.JSONObject.quote(key))
            builder.append(':')
            builder.append(org.json.JSONObject.quote(value))
        }
        builder.append('}')
        return builder.toString().toByteArray(Charsets.UTF_8)
    }
}
