package com.fortressflag.sdk

// The version the SDK reports in `X-FF-SDK: android/<VERSION>`. Declared, never read
// server-side today — telemetry for a future, not something behavior may assume the server
// sees. Bumped by release PRs only.
public object BuildInfo {
    public const val VERSION: String = "1.0.0"
}
