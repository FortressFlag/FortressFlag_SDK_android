# FortressFlag Android SDK

FortressFlag's Android client SDK (Kotlin, minSdk 26). It embeds in your app, resolves feature
flags for this one device, and is built so a flagging outage can never take your app down:
every read falls back to the last value this device actually saw, then to `false` — never an
exception, never a crash.

**Status: v0.1.0, pre-release.** No Maven Central / registry publication yet; consume as a
source dependency or included build.

## Quickstart

```kotlin
FortressFlag.start(
    context,
    Configuration(
        sdkKey = "ffc_prod_…",       // a client key: read-only, ships in your app by design
        environment = "prod",
    ),
)

if (FortressFlag.isEnabled("new-checkout", default = false)) {
    // …
}
```

The SDK polls `GET /v1/client/flags` (and nothing else), caches the response durably, and
serves every read from memory. `isEnabled`, `stringValue` and `numberValue` never throw.

## Device identity, billing, and two honest notes

The SDK mints a random, pseudonymous device identifier — never derived from `ANDROID_ID` or
any hardware ID — per the cross-platform contract in
[`FortressFlag_Standards/contracts/device-identity.md`](https://github.com/FortressFlag/FortressFlag_Standards/blob/development/contracts/device-identity.md).
FortressFlag bills per device. On Android, two platform facts affect that and are stated here
rather than discovered on an invoice:

- **Identity is per app in v0.1.** If a user runs three of your apps embedding this SDK on one
  phone, that currently bills as up to three devices. App-family sharing (same signing key ⇒
  one identity) is a designed follow-up. iOS behaves the same unless the customer opts into
  the shared keychain access group; single-app customers get per-device semantics regardless.
- **Identity does not survive uninstall.** Keystore-backed app data is wiped on uninstall, so
  a reinstall mints a new identity and counts as a new device. (The iOS keychain persists;
  Android has no equivalent that is also private to your app.)

Emulators mint a `sim_` identity (best-effort detection): served flags normally, never billed.

## Building

JDK 17 + the Android SDK. `./gradlew build lint test` is the local gate; CI additionally runs
the identity tests against a real Keystore on an emulator.

See `CLAUDE.md` for the engineering rules this repo answers to, and `SECURITY.md` for
reporting vulnerabilities.
