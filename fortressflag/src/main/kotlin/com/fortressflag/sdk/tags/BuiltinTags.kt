package com.fortressflag.sdk.tags

import android.content.Context
import android.os.Build
import com.fortressflag.sdk.BuildInfo

/**
 * The tags the SDK sends automatically: mechanically derived facts about this installation,
 * none of them user-identifying. The five names are reserved (contract v1) — a customer tag
 * colliding with one is dropped by [Tags.sanitize].
 */
internal object BuiltinTags {
    val RESERVED_KEYS: Set<String> = setOf("appVersion", "appBuild", "osVersion", "platform", "sdkVersion")

    /** The live set. A value the platform cannot supply simply omits its tag; a rule on the
     * absent key then never matches, which is the contract's absent-tag semantics. */
    fun current(context: Context): Map<String, String> {
        val packageInfo =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: Exception) {
                null
            }
        val appVersion = packageInfo?.versionName

        @Suppress("DEPRECATION")
        val appBuild =
            packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString() else it.versionCode.toString()
            }
        return collect(
            appVersion = appVersion,
            appBuild = appBuild,
            osVersion = Build.VERSION.RELEASE,
        )
    }

    /** The derivation, separated from its inputs so tests can drive it without a device. */
    fun collect(
        appVersion: String?,
        appBuild: String?,
        osVersion: String?,
    ): Map<String, String> {
        val tags =
            mutableMapOf(
                "platform" to "android",
                "sdkVersion" to BuildInfo.VERSION,
            )
        if (!osVersion.isNullOrEmpty()) tags["osVersion"] = osVersion
        if (!appVersion.isNullOrEmpty()) tags["appVersion"] = appVersion
        if (!appBuild.isNullOrEmpty()) tags["appBuild"] = appBuild
        return tags
    }
}
