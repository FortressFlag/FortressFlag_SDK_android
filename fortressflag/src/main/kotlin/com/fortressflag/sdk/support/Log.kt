package com.fortressflag.sdk.support

import com.fortressflag.sdk.LogPolicy
import android.util.Log as AndroidLog

/**
 * The SDK's only mouth. Routed through one type so the rules have one enforcement point:
 * flag VALUES, tag VALUES and SDK keys never appear in a log line — keys and mechanically
 * derived facts may. `verbose` names the customer's flag keys and is documented as
 * never-in-a-shipping-build for exactly that reason.
 */
internal class Log(
    private val policy: LogPolicy,
    private val category: String,
) {
    fun error(message: String) {
        if (policy == LogPolicy.SILENT) return
        AndroidLog.e(TAG, "[$category] $message")
    }

    fun warning(message: String) {
        if (policy == LogPolicy.SILENT) return
        AndroidLog.w(TAG, "[$category] $message")
    }

    fun debug(message: String) {
        if (policy != LogPolicy.VERBOSE) return
        AndroidLog.d(TAG, "[$category] $message")
    }

    private companion object {
        const val TAG = "FortressFlag"
    }
}
